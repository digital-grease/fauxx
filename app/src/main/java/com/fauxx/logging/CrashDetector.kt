package com.fauxx.logging

import android.content.Context
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects whether the app previously crashed by checking for the existence
 * of a crash report file on disk. Exposes the crash report content for the
 * UI to display in a share dialog.
 */
@Singleton
class CrashDetector @Inject constructor(
    private val context: Context
) {
    private val crashFile: File
        get() = File(context.filesDir, CrashReportWriter.CRASH_FILE_NAME)

    /**
     * Returns true if a crash report file exists from a previous session.
     */
    fun hasCrashReport(): Boolean = crashFile.exists()

    /**
     * Reads and returns the crash report contents, or null if no report exists.
     */
    fun readCrashReport(): String? {
        return if (crashFile.exists()) {
            try {
                crashFile.readText()
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
    }

    /**
     * Deletes the crash report file after the user has dismissed or shared it.
     */
    fun dismissCrashReport() {
        crashFile.delete()
    }
}
