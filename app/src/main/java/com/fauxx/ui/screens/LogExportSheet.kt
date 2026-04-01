package com.fauxx.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

private const val GITHUB_ISSUES_URL = "https://github.com/digital-grease/fauxx/issues/new"

/**
 * Bottom sheet with export options for crash reports and debug logs.
 * Gives the user clear choices for what to do with the exported data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogExportSheet(
    title: String,
    content: String,
    fileName: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "All personal data has been scrubbed from the logs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            ExportOption(
                icon = { Icon(Icons.Outlined.BugReport, contentDescription = null, modifier = Modifier.size(24.dp)) },
                label = "File a GitHub Issue",
                description = "Opens a new issue — requires a GitHub account",
                onClick = {
                    copyToClipboard(context, content, silent = true)
                    openGitHubIssue(context, content, fileName)
                    onDismiss()
                }
            )

            ExportOption(
                icon = { Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(24.dp)) },
                label = "Share via...",
                description = "Send to email, Slack, or another app",
                onClick = {
                    shareViaIntent(context, content, fileName, title)
                    onDismiss()
                }
            )

            ExportOption(
                icon = { Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(24.dp)) },
                label = "Save to device",
                description = "Saves to Downloads folder",
                onClick = {
                    saveToDownloads(context, content, fileName)
                    onDismiss()
                }
            )

            ExportOption(
                icon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(24.dp)) },
                label = "Copy to clipboard",
                description = "Copy the full log text",
                onClick = {
                    copyToClipboard(context, content)
                    onDismiss()
                }
            )
        }
    }
}

@Composable
private fun ExportOption(
    icon: @Composable () -> Unit,
    label: String,
    description: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(16.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private const val MAX_URL_LOG_BYTES = 4096

private fun openGitHubIssue(context: Context, content: String, fileName: String) {
    val label = if (fileName.contains("crash")) "crash report" else "debug logs"
    val truncated = content.length > MAX_URL_LOG_BYTES
    val logSnippet = if (truncated) content.takeLast(MAX_URL_LOG_BYTES) else content
    val clipboardNote = if (truncated) {
        "\n\n> Logs were truncated. Full $label copied to your clipboard — paste below if needed.\n"
    } else ""

    val body = "## Description\n\n[Describe what happened]\n\n" +
        "## Logs\n$clipboardNote\n```\n$logSnippet\n```\n"

    val url = "$GITHUB_ISSUES_URL?labels=bug&title=Bug+report+with+$label&body=${Uri.encode(body)}"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    try {
        context.startActivity(intent)
        Toast.makeText(context, "Full logs copied to clipboard", Toast.LENGTH_SHORT).show()
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No browser found — logs copied to clipboard", Toast.LENGTH_LONG).show()
    }
}

private fun shareViaIntent(context: Context, content: String, fileName: String, subject: String) {
    val tempFile = File(context.cacheDir, fileName)
    tempFile.writeText(content)

    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        tempFile
    )

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, "Fauxx $subject — see attached file.")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, subject))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No sharing app found", Toast.LENGTH_LONG).show()
    }
}

private fun saveToDownloads(context: Context, content: String, fileName: String) {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
    }
    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    if (uri != null) {
        context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
        Toast.makeText(context, "Saved to Downloads/$fileName", Toast.LENGTH_SHORT).show()
    } else {
        Toast.makeText(context, "Failed to save file", Toast.LENGTH_SHORT).show()
    }
}

private fun copyToClipboard(context: Context, content: String, silent: Boolean = false) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Fauxx Logs", content))
    if (!silent) {
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}
