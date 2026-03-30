package com.fauxx

import android.content.Context
import com.fauxx.logging.CrashReportWriter
import com.fauxx.logging.EncryptedFileTree
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashReportWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var encryptedFileTree: EncryptedFileTree
    private lateinit var writer: CrashReportWriter
    private lateinit var filesDir: File

    @Before
    fun setup() {
        filesDir = tempFolder.newFolder("files")
        context = mockk {
            every { this@mockk.filesDir } returns filesDir
        }
        encryptedFileTree = mockk {
            every { getRecentLines(any()) } returns listOf(
                "2026-03-29 12:00:00 I/Engine: Action dispatched",
                "2026-03-29 12:00:01 D/Search: Query executed"
            )
        }
        writer = CrashReportWriter(context, encryptedFileTree)
    }

    @Test
    fun `writes crash file on exception`() {
        val exception = RuntimeException("Test crash")
        writer.writeCrashReport(exception)

        val crashFile = File(filesDir, CrashReportWriter.CRASH_FILE_NAME)
        assertTrue("Crash file should exist", crashFile.exists())
    }

    @Test
    fun `crash file contains stack trace`() {
        val exception = RuntimeException("Test crash")
        writer.writeCrashReport(exception)

        val content = File(filesDir, CrashReportWriter.CRASH_FILE_NAME).readText()
        assertTrue("Should contain exception message", content.contains("Test crash"))
        assertTrue("Should contain RuntimeException", content.contains("RuntimeException"))
    }

    @Test
    fun `crash file contains header`() {
        val exception = RuntimeException("Test crash")
        writer.writeCrashReport(exception)

        val content = File(filesDir, CrashReportWriter.CRASH_FILE_NAME).readText()
        assertTrue("Should contain header", content.contains("=== Fauxx Crash Report ==="))
        assertTrue("Should contain timestamp label", content.contains("Time:"))
        assertTrue("Should contain thread label", content.contains("Thread:"))
    }

    @Test
    fun `crash file contains recent logs`() {
        val exception = RuntimeException("Test crash")
        writer.writeCrashReport(exception)

        val content = File(filesDir, CrashReportWriter.CRASH_FILE_NAME).readText()
        assertTrue("Should contain recent logs section", content.contains("=== Recent Logs ==="))
        assertTrue("Should contain log line", content.contains("Action dispatched"))
    }

    @Test
    fun `crash file scrubs sensitive data from logs`() {
        every { encryptedFileTree.getRecentLines(any()) } returns listOf(
            "ageRange=25-34 loaded from profile",
            "UserDemographicProfile(ageRange=25-34, gender=MALE)"
        )

        val exception = RuntimeException("Test crash")
        writer.writeCrashReport(exception)

        val content = File(filesDir, CrashReportWriter.CRASH_FILE_NAME).readText()
        assertFalse("Should not contain age range", content.contains("25-34"))
        assertFalse("Should not contain profile dump", content.contains("UserDemographicProfile"))
    }

    @Test
    fun `handles nested exception causes`() {
        val root = IllegalStateException("root cause")
        val exception = RuntimeException("wrapper", root)
        writer.writeCrashReport(exception)

        val content = File(filesDir, CrashReportWriter.CRASH_FILE_NAME).readText()
        assertTrue("Should contain wrapper", content.contains("wrapper"))
        assertTrue("Should contain root cause", content.contains("root cause"))
    }

    @Test
    fun `overwrites previous crash file`() {
        writer.writeCrashReport(RuntimeException("first"))
        writer.writeCrashReport(RuntimeException("second"))

        val content = File(filesDir, CrashReportWriter.CRASH_FILE_NAME).readText()
        assertFalse("Should not contain first crash", content.contains("first"))
        assertTrue("Should contain second crash", content.contains("second"))
    }
}
