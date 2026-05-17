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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.fauxx.BuildConfig
import com.fauxx.R
import com.fauxx.util.CrashReportUrlBuilder
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
                text = stringResource(R.string.log_export_scrubbed_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            ExportOption(
                icon = { Icon(Icons.Outlined.BugReport, contentDescription = null, modifier = Modifier.size(24.dp)) },
                label = stringResource(R.string.log_export_option_github),
                description = stringResource(R.string.log_export_option_github_description),
                onClick = {
                    openGitHubIssue(context, fileName, content)
                    onDismiss()
                }
            )

            ExportOption(
                icon = { Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(24.dp)) },
                label = stringResource(R.string.log_export_option_share),
                description = stringResource(R.string.log_export_option_share_description),
                onClick = {
                    shareViaIntent(context, content, fileName, title)
                    onDismiss()
                }
            )

            ExportOption(
                icon = { Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(24.dp)) },
                label = stringResource(R.string.log_export_option_save),
                description = stringResource(R.string.log_export_option_save_description),
                onClick = {
                    saveToDownloads(context, content, fileName)
                    onDismiss()
                }
            )

            ExportOption(
                icon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(24.dp)) },
                label = stringResource(R.string.log_export_option_copy),
                description = stringResource(R.string.log_export_option_copy_description),
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

private fun openGitHubIssue(context: Context, fileName: String, content: String) {
    // Crash reports use the structured form with pre-filled fields. Debug-log exports
    // (non-crash) fall through to the bug report form's deep-link; user fills it out.
    val isCrash = fileName.contains("crash")
    if (!isCrash) {
        openBugReportForm(context)
        return
    }

    val result = CrashReportUrlBuilder.build(
        device = CrashReportUrlBuilder.formatDevice(Build.MANUFACTURER, Build.MODEL),
        androidVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
        appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        flavor = CrashReportUrlBuilder.mapFlavor(BuildConfig.FLAVOR),
        trace = content
    )

    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.url))
    try {
        context.startActivity(intent)
        when (result) {
            is CrashReportUrlBuilder.Result.Embedded -> {
                Toast.makeText(
                    context,
                    context.getString(R.string.log_export_toast_crash_report_ready),
                    Toast.LENGTH_LONG
                ).show()
            }
            is CrashReportUrlBuilder.Result.Truncated -> {
                // Trace too long to fit in the URL — keep clipboard as the secondary
                // channel so the user can paste the full content below the embedded head.
                copyToClipboard(context, result.fullTrace, silent = true)
                Toast.makeText(
                    context,
                    context.getString(R.string.log_export_toast_trace_truncated),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    } catch (_: ActivityNotFoundException) {
        copyToClipboard(context, content, silent = true)
        Toast.makeText(context, context.getString(R.string.log_export_toast_no_browser_clipboard), Toast.LENGTH_LONG).show()
    }
}

private fun openBugReportForm(context: Context) {
    val url = "$GITHUB_ISSUES_URL?template=bug_report.yml"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.log_export_toast_no_browser_issue_tracker), Toast.LENGTH_LONG).show()
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
        putExtra(Intent.EXTRA_TEXT, context.getString(R.string.log_export_share_text, subject))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, subject))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.log_export_toast_no_sharing_app), Toast.LENGTH_LONG).show()
    }
}

private fun saveToDownloads(context: Context, content: String, fileName: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        Toast.makeText(context, context.getString(R.string.log_export_toast_save_requires_android_10), Toast.LENGTH_SHORT).show()
        return
    }
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
    }
    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    if (uri != null) {
        context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
        Toast.makeText(context, context.getString(R.string.log_export_toast_saved_to_downloads, fileName), Toast.LENGTH_SHORT).show()
    } else {
        Toast.makeText(context, context.getString(R.string.log_export_toast_save_failed), Toast.LENGTH_SHORT).show()
    }
}

private fun copyToClipboard(context: Context, content: String, silent: Boolean = false) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Fauxx Logs", content))
    if (!silent) {
        Toast.makeText(context, context.getString(R.string.log_export_toast_copied_to_clipboard), Toast.LENGTH_SHORT).show()
    }
}
