package com.fauxx.logging

import android.content.Context
import android.util.Log
import com.fauxx.di.TinkKeyManager
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pure-JVM unit tests for [EncryptedFileTree]'s segment-rotation write path and
 * size-based pruning.
 *
 * Tink is mocked with IDENTITY answers (encrypt/decrypt both return their first arg)
 * so on-disk byte size equals plaintext size and [EncryptedFileTree.readAllLogs]
 * reconstructs the exact plaintext that was written. No Robolectric / @Config is
 * needed: the class only touches java.io.File, a mocked Context.filesDir, and the
 * mocked TinkKeyManager. android.util.Log returns defaults via the unit-test
 * `isReturnDefaultValues` option.
 *
 * Memory note: these tests share a single JVM test fork (forkEvery=0) with the whole
 * module. To prove the 5 MB directory cap a flood must move just over 5 MB through the
 * identity Tink mock, and MockK retains every recorded call's argument array. So the
 * flood helper clears the mock's recorded-call history periodically (preserving the
 * stubbed answers via `answers = false`) to keep peak heap bounded to one drained
 * batch rather than the whole flood, and [tearDown] clears the mocks afterwards.
 */
class EncryptedFileTreeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var tink: TinkKeyManager
    private lateinit var tree: EncryptedFileTree
    private lateinit var filesDir: File

    // Mirror of the constants under test (kept private in the production class).
    private val maxTotalBytes = 5 * 1024 * 1024L // 5 MB
    private val maxFileBytes = 256 * 1024L // 256 KB per segment

    @Before
    fun setup() {
        filesDir = tempFolder.newFolder("files")
        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir

        tink = mockk()
        // IDENTITY crypto: ciphertext == plaintext, so file length == plaintext length
        // and readAllLogs returns exactly what was written.
        every { tink.encrypt(any(), any()) } answers { firstArg<ByteArray>() }
        every { tink.decrypt(any(), any()) } answers { firstArg<ByteArray>() }

        tree = EncryptedFileTree(context, tink)
    }

    @After
    fun tearDown() {
        // Release any ByteArrays MockK retained in its recorded-call history so they do
        // not burden the rest of the shared test fork.
        clearMocks(tink, context)
    }

    /** The directory the tree writes into (matches the production LOG_DIR name). */
    private fun logDir(): File = File(filesDir, "fauxx_logs")

    private fun encFiles(): List<File> =
        logDir().listFiles { f -> f.name.endsWith(".enc") }?.toList() ?: emptyList()

    private fun totalEncBytes(): Long = encFiles().sumOf { it.length() }

    /**
     * Drives one log line into the tree via Timber's public `log(priority, message)` API.
     * (The 4-arg `log(priority, tag, message, t)` is a `protected` override and is not
     * accessible from here, so calling it would silently bind to the public vararg overload
     * and store the wrong text. The public 2-arg form routes through the same write path and
     * stores [message] verbatim, with a null tag rendered as "---".)
     */
    private fun logLine(message: String) {
        tree.log(Log.INFO, message)
    }

    /**
     * Floods [times] copies of [message] through the log path, flushing and clearing the
     * Tink mock's recorded-call history every 500 lines so the retained-argument footprint
     * stays bounded to one drained batch (peak heap safety on the shared fork). The stubbed
     * identity answers are preserved (`answers = false`).
     */
    private fun flood(times: Int, message: String) {
        for (i in 0 until times) {
            logLine(message)
            if ((i + 1) % 500 == 0) {
                tree.flush()
                clearMocks(tink, answers = false)
            }
        }
        tree.flush()
    }

    @Test
    fun `a single hour of flooding stays under the directory size cap`() {
        // ~1 KB message; ~6 MB of these would exceed the 5 MB cap if naively appended.
        flood(6000, "x".repeat(1024))

        val total = totalEncBytes()
        // Pruning runs on each flush; the directory total must end at or below the cap.
        // Allow one segment of slack because prune deletes until total <= cap.
        assertTrue(
            "directory total $total should be <= MAX_TOTAL_BYTES ($maxTotalBytes) plus one-segment slack",
            total <= maxTotalBytes + maxFileBytes
        )
    }

    @Test
    fun `no single log file exceeds the per-file cap and rotation produces multiple segments`() {
        // Accumulate a SINGLE flush batch larger than the 256 KB per-file cap (6 x 60 KB = 360 KB),
        // staying under FLUSH_THRESHOLD (50 lines) so it is not auto-flushed first. The explicit
        // flush then forces chunkBySize to split that one batch across multiple capped segments.
        repeat(6) { logLine("y".repeat(60 * 1024)) }
        tree.flush()

        val files = encFiles()
        assertTrue("a >256 KB batch must be split into multiple capped segments", files.size > 1)
        for (f in files) {
            assertTrue(
                "segment ${f.name} length ${f.length()} must be <= MAX_FILE_BYTES ($maxFileBytes)",
                f.length() <= maxFileBytes
            )
        }
    }

    @Test
    fun `flush does not re-encrypt the whole accumulated hour file each time`() {
        val captured = mutableListOf<ByteArray>()
        every { tink.encrypt(capture(captured), any()) } answers { firstArg<ByteArray>() }

        val message = "z".repeat(512)
        // Many explicit flushes within the same hour. With the old decrypt-concat-rewrite
        // bug the captured plaintext would grow toward the whole-hour size; with rotation
        // each encrypt sees at most one batch (bounded by the per-file cap).
        repeat(25) {
            repeat(60) { logLine(message) }
            tree.flush()
        }

        assertTrue("encrypt should have been called at least once", captured.isNotEmpty())
        val maxCaptured = captured.maxOf { it.size }
        assertTrue(
            "max captured plaintext $maxCaptured must stay bounded by MAX_FILE_BYTES ($maxFileBytes), " +
                "not grow toward the whole-hour size",
            maxCaptured <= maxFileBytes
        )
    }

    @Test
    fun `readAllLogs returns every flooded line in chronological order after rotation`() {
        val count = 1500
        for (i in 0 until count) {
            // Distinct, sortable markers; each line is sizeable so rotation kicks in.
            logLine("MARKER-" + i.toString().padStart(6, '0') + "-" + "p".repeat(200))
        }
        tree.flush()

        // Rotation must actually have occurred for this to be a meaningful ordering test.
        assertTrue("rotation should have produced multiple segments", encFiles().size > 1)

        // readAllLogs returns FORMATTED lines ("<ts> <level>/<tag>: <message>"), so match the
        // marker substring rather than the line prefix.
        val markerLines = tree.readAllLogs().split("\n").filter { it.contains("MARKER-") }
        assertEquals("every flooded marker line must be present", count, markerLines.size)
        for (i in 0 until count) {
            val expectedMarker = "MARKER-" + i.toString().padStart(6, '0') + "-"
            assertTrue(
                "line $i should contain the $i-th marker in insertion order, was: ${markerLines[i]}",
                markerLines[i].contains(expectedMarker)
            )
        }
    }

    @Test
    fun `clearLogs removes all rotated segment files and empties recent lines`() {
        flood(1500, "c".repeat(1024))
        assertTrue("precondition: segments exist after flood", encFiles().isNotEmpty())

        tree.clearLogs()

        assertTrue("no .enc files should remain after clearLogs", encFiles().isEmpty())
        assertTrue("recent lines should be empty after clearLogs", tree.getRecentLines().isEmpty())
    }

    @Test
    fun `pruneOldFiles trims the directory below the cap even with only newest files`() {
        val dir = logDir()
        assertTrue("log dir must exist for pre-seeding", dir.exists() || dir.mkdirs())

        // Pre-seed several oversized, FRESH .enc files directly (written one at a time so
        // peak heap is one buffer, not the whole set). The size-trim must still be able to
        // delete them: the old `size > 1` guard could not drive a newest-only directory
        // below the cap. 6 MB total exceeds the 5 MB cap.
        val oneMb = 1024 * 1024
        for (seq in 0 until 6) {
            val seeded = File(dir, "log_2000-01-01_00_" + seq.toString().padStart(4, '0') + ".enc")
            seeded.writeBytes(ByteArray(oneMb))
        }
        assertTrue(
            "precondition: seeded total exceeds the cap",
            totalEncBytes() > maxTotalBytes
        )

        // Trigger a flush (which calls pruneOldFiles). One real batch plus the seeds.
        repeat(10) { logLine("trigger") }
        tree.flush()

        val total = totalEncBytes()
        assertNotNull(total)
        assertTrue(
            "directory total $total should be driven to <= MAX_TOTAL_BYTES ($maxTotalBytes) " +
                "even though all files are newest-only",
            total <= maxTotalBytes
        )
    }
}
