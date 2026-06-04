package com.fauxx.logging

import android.content.Context
import android.util.Log
import com.fauxx.di.TinkKeyManager
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * A [Timber.Tree] that writes log lines to an encrypted rolling file on disk.
 *
 * Log lines are accumulated in a thread-safe in-memory ring buffer and flushed
 * to disk periodically or when the buffer reaches [FLUSH_THRESHOLD] entries.
 * On-disk logs are encrypted with the app's Tink AEAD keyset (AndroidKeyStore-backed).
 *
 * Write policy: each flush appends its drained batch as one or more new encrypted
 * segment files named `log_<yyyy-MM-dd_HH>_<seq>.enc`, where `seq` is a zero-padded
 * (width 4) sequence number derived from the number of existing segments for the
 * current hour. Only the batch is encrypted (no decrypt-concat-rewrite of the hour
 * file), so flushing stays O(batch) instead of O(hour-so-far). A single batch whose
 * plaintext would exceed [MAX_FILE_BYTES] is split across multiple segments so no
 * segment exceeds the per-file cap. Zero-padding keeps lexical name order equal to
 * chronological order, which [readAllLogs] relies on.
 *
 * Rolling policy: files older than [MAX_AGE_MS] (48 hours) are pruned on each flush,
 * and the oldest segments are deleted until the directory total is at or below
 * [MAX_TOTAL_BYTES] (5 MB), including the newest file if it alone exceeds the cap.
 */
class EncryptedFileTree(
    private val context: Context,
    private val tinkKeyManager: TinkKeyManager
) : Timber.Tree() {

    companion object {
        private const val LOG_DIR = "fauxx_logs"
        private const val MAX_AGE_MS = 48 * 60 * 60 * 1000L // 48 hours
        private const val MAX_TOTAL_BYTES = 5 * 1024 * 1024L // 5 MB
        private const val MAX_FILE_BYTES = 256 * 1024L // 256 KB per segment
        private const val FLUSH_THRESHOLD = 50
        private const val RING_BUFFER_CAPACITY = 500
        private val ASSOCIATED_DATA = "fauxx_log_file".toByteArray()
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd_HH", Locale.US)
        private val LINE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    private val ringBuffer = ConcurrentLinkedDeque<String>()
    private val logDir: File by lazy {
        File(context.filesDir, LOG_DIR).also { dir ->
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w("EncryptedFileTree", "Failed to create log directory: ${dir.absolutePath}")
            }
        }
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val level = when (priority) {
            Log.VERBOSE -> "V"
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            Log.ASSERT -> "A"
            else -> "?"
        }
        val timestamp = LINE_FORMAT.format(Date())
        val line = "$timestamp $level/${tag ?: "---"}: $message"
        val fullLine = if (t != null) "$line\n${t.stackTraceToString()}" else line

        ringBuffer.addLast(fullLine)
        while (ringBuffer.size > RING_BUFFER_CAPACITY) {
            ringBuffer.pollFirst()
        }

        if (ringBuffer.size >= FLUSH_THRESHOLD) {
            flush()
        }
    }

    /**
     * Returns the most recent [count] log lines from the in-memory ring buffer.
     * Used by [CrashReportWriter] to capture recent context during a crash.
     */
    fun getRecentLines(count: Int = RING_BUFFER_CAPACITY): List<String> =
        ringBuffer.toList().takeLast(count)

    /**
     * Flushes buffered log lines to disk by writing the drained batch as one or more
     * new encrypted segment files, then prunes old/oversized files.
     *
     * Each segment is named `log_<yyyy-MM-dd_HH>_<seq>.enc`. The batch is split into
     * chunks so no single segment's plaintext exceeds [MAX_FILE_BYTES]; only the chunk
     * is encrypted, so flushing never decrypts or rewrites the rest of the hour.
     */
    @Synchronized
    fun flush() {
        val lines = mutableListOf<String>()
        while (true) {
            val line = ringBuffer.pollFirst() ?: break
            lines.add(line)
        }
        if (lines.isEmpty()) return

        try {
            val hourBase = "log_${DATE_FORMAT.format(Date())}_"
            var seq = logDir.listFiles { f ->
                f.name.startsWith(hourBase) && f.name.endsWith(".enc")
            }?.size ?: 0

            // Split the batch into chunks so no segment plaintext exceeds MAX_FILE_BYTES.
            for (chunk in chunkBySize(lines, MAX_FILE_BYTES)) {
                val segment = File(logDir, "$hourBase${String.format(Locale.US, "%04d", seq)}.enc")
                val ciphertext = tinkKeyManager.encrypt(
                    chunk.joinToString("\n").toByteArray(Charsets.UTF_8),
                    ASSOCIATED_DATA
                )
                segment.writeBytes(ciphertext)
                seq++
            }

            pruneOldFiles()
        } catch (e: Exception) {
            Log.w("EncryptedFileTree", "Failed to flush logs to disk: ${e.message}")
            // Re-add lines to buffer on failure so they aren't lost.
            // Re-add in reverse so original insertion order is preserved at the head.
            lines.asReversed().forEach { ringBuffer.addFirst(it) }
        }
    }

    /**
     * Splits [lines] into consecutive groups whose joined plaintext (lines separated by
     * a single newline) stays at or below [maxBytes]. A single line larger than [maxBytes]
     * is emitted as its own group (it cannot be split further without corrupting a log line).
     */
    private fun chunkBySize(lines: List<String>, maxBytes: Long): List<List<String>> {
        val chunks = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var currentBytes = 0L
        for (line in lines) {
            val lineBytes = line.toByteArray(Charsets.UTF_8).size.toLong()
            // +1 for the newline separator that joins this line to the previous one.
            val added = if (current.isEmpty()) lineBytes else lineBytes + 1
            if (current.isNotEmpty() && currentBytes + added > maxBytes) {
                chunks.add(current)
                current = mutableListOf()
                currentBytes = 0L
                current.add(line)
                currentBytes = lineBytes
            } else {
                current.add(line)
                currentBytes += added
            }
        }
        if (current.isNotEmpty()) chunks.add(current)
        return chunks
    }

    /**
     * Reads all encrypted log files and returns their decrypted contents
     * concatenated in chronological order.
     */
    fun readAllLogs(): String {
        val files = logDir.listFiles { f -> f.name.endsWith(".enc") }
            ?.sortedBy { it.name }
            ?: return ""

        return files.mapNotNull { file ->
            try {
                String(tinkKeyManager.decrypt(file.readBytes(), ASSOCIATED_DATA), Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }.joinToString("\n")
    }

    /**
     * Deletes all encrypted log files.
     */
    fun clearLogs() {
        logDir.listFiles()?.forEach { it.delete() }
        ringBuffer.clear()
    }

    private fun pruneOldFiles() {
        val files = logDir.listFiles { f -> f.name.endsWith(".enc") }
            ?.sortedBy { it.name }
            ?: return
        val now = System.currentTimeMillis()

        // Remove files older than 48h
        files.filter { now - it.lastModified() > MAX_AGE_MS }.forEach { it.delete() }

        // Remove oldest files until total size is at or below 5MB. The guard is
        // `isNotEmpty()` (not `size > 1`) so the directory total can be driven below
        // the cap even when the only files present are the newest segments, otherwise
        // a single flooded hour could grow unbounded.
        val remaining = logDir.listFiles { f -> f.name.endsWith(".enc") }
            ?.sortedBy { it.name }
            ?.toMutableList()
            ?: return
        var totalSize = remaining.sumOf { it.length() }
        while (totalSize > MAX_TOTAL_BYTES && remaining.isNotEmpty()) {
            val oldest = remaining.removeAt(0)
            totalSize -= oldest.length()
            oldest.delete()
        }
    }
}
