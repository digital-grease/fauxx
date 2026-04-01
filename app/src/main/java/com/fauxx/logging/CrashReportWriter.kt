package com.fauxx.logging

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Synchronously writes a crash report to disk when an uncaught exception occurs.
 *
 * The report includes the stack trace and the most recent log lines from the
 * [EncryptedFileTree] ring buffer. All writes are synchronous (no coroutines)
 * because the process is dying when this is called.
 *
 * The crash file is written to app-internal files dir (not cache) so it survives
 * across app restarts.
 */
@Singleton
class CrashReportWriter @Inject constructor(
    private val context: Context,
    private val encryptedFileTree: EncryptedFileTree
) {
    companion object {
        const val CRASH_FILE_NAME = "fauxx_crash_report.txt"
        private const val RECENT_LINES_COUNT = 100
        private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
    }

    /**
     * Writes the crash report synchronously. Called from [Thread.UncaughtExceptionHandler].
     * Must not use coroutines or suspend functions — the process is terminating.
     */
    fun writeCrashReport(throwable: Throwable) {
        try {
            val file = File(context.filesDir, CRASH_FILE_NAME)
            FileWriter(file, false).use { writer ->
                writer.write("=== Fauxx Crash Report ===\n")
                writer.write("Time: ${TIMESTAMP_FORMAT.format(Date())}\n")
                writer.write("Thread: ${Thread.currentThread().name}\n\n")

                writer.write("=== Stack Trace ===\n")
                writer.write(throwable.stackTraceToString())
                writer.write("\n\n")

                writer.write("=== Recent Logs ===\n")
                val recentLines = encryptedFileTree.getRecentLines(RECENT_LINES_COUNT)
                val scrubbed = LogScrubber.scrub(recentLines.joinToString("\n"))
                writer.write(scrubbed)
                writer.write("\n")
            }
        } catch (e: Exception) {
            // Best-effort — fall back to logcat if file write fails
            android.util.Log.e("CrashReportWriter", "Failed to write crash report: ${e.message}")
            android.util.Log.e("CrashReportWriter", "Original crash:", throwable)
        }
    }
}
