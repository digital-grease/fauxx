package com.fauxx.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Shares text content through the system share sheet as a FILE, never an inline
 * Intent extra.
 *
 * A full action-log export can be several megabytes. Passing it in
 * [Intent.EXTRA_TEXT] serializes the whole string into the Binder transaction
 * that backs `startActivity`, which is capped at ~1MB, so a large export crashes
 * with `TransactionTooLargeException` (issue #239). Instead the content is
 * written to the app cache dir and shared as a [FileProvider] content URI in
 * [Intent.EXTRA_STREAM]; only a short caption rides in [Intent.EXTRA_TEXT], so
 * the transaction stays tiny regardless of log size.
 */
object FileShare {

    /** Write [content] to `<cacheDir>/[fileName]`, returning the file. */
    fun writeToCache(cacheDir: File, fileName: String, content: String): File =
        File(cacheDir, fileName).apply { writeText(content) }

    /**
     * Build the `ACTION_SEND` intent that shares [uri] as [mimeType]. The payload
     * rides [Intent.EXTRA_STREAM]; [Intent.EXTRA_TEXT] carries only the short
     * [caption], so no large string is ever placed in an Intent extra (issue #239).
     */
    fun buildSendIntent(uri: Uri, mimeType: String, subject: String, caption: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, caption)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /**
     * Share [content] as a file named [fileName] of [mimeType] via a chooser
     * titled [chooserTitle]. [caption] is the short body text, never the content
     * itself. Returns `false` if no app can handle the share, so the caller can
     * surface a message.
     */
    fun share(
        context: Context,
        content: String,
        mimeType: String,
        fileName: String,
        chooserTitle: String,
        caption: String,
    ): Boolean {
        val file = writeToCache(context.cacheDir, fileName, content)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = buildSendIntent(uri, mimeType, fileName, caption)
        return try {
            context.startActivity(Intent.createChooser(intent, chooserTitle))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
