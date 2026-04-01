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
 * Rolling policy: files older than [MAX_AGE_MS] (48 hours) or total size exceeding
 * [MAX_TOTAL_BYTES] (5 MB) are pruned on each flush.
 */
class EncryptedFileTree(
    private val context: Context,
    private val tinkKeyManager: TinkKeyManager
) : Timber.Tree() {

    companion object {
        private const val LOG_DIR = "fauxx_logs"
        private const val MAX_AGE_MS = 48 * 60 * 60 * 1000L // 48 hours
        private const val MAX_TOTAL_BYTES = 5 * 1024 * 1024L // 5 MB
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
     * Flushes buffered log lines to an encrypted file on disk and prunes old files.
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
            val hourFile = File(logDir, "log_${DATE_FORMAT.format(Date())}.enc")
            val plaintext = if (hourFile.exists()) {
                val existing = tinkKeyManager.decrypt(hourFile.readBytes(), ASSOCIATED_DATA)
                String(existing, Charsets.UTF_8) + "\n" + lines.joinToString("\n")
            } else {
                lines.joinToString("\n")
            }
            val ciphertext = tinkKeyManager.encrypt(plaintext.toByteArray(Charsets.UTF_8), ASSOCIATED_DATA)
            hourFile.writeBytes(ciphertext)

            pruneOldFiles()
        } catch (e: Exception) {
            Log.w("EncryptedFileTree", "Failed to flush logs to disk: ${e.message}")
            // Re-add lines to buffer on failure so they aren't lost
            lines.forEach { ringBuffer.addFirst(it) }
        }
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

        // Remove oldest files if total size exceeds 5MB
        val remaining = logDir.listFiles { f -> f.name.endsWith(".enc") }
            ?.sortedBy { it.name }
            ?.toMutableList()
            ?: return
        var totalSize = remaining.sumOf { it.length() }
        while (totalSize > MAX_TOTAL_BYTES && remaining.size > 1) {
            val oldest = remaining.removeFirst()
            totalSize -= oldest.length()
            oldest.delete()
        }
    }
}
