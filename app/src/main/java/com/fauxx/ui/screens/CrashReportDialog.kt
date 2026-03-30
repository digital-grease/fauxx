package com.fauxx.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.FileProvider
import java.io.File

/**
 * Dialog shown on app launch after a crash. Offers to share the crash report
 * via the system share sheet or dismiss it.
 */
@Composable
fun CrashReportDialog(
    onDismiss: () -> Unit,
    onShare: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Fauxx Crashed",
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.error
            )
        },
        text = {
            Text(
                "Fauxx crashed during the last session. " +
                    "You can share the crash report to help diagnose the issue. " +
                    "The report has been scrubbed of personal data."
            )
        },
        confirmButton = {
            TextButton(onClick = onShare) {
                Text("Share Report")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    )
}

/**
 * Launches a share intent for the crash report file using FileProvider.
 */
fun shareCrashReport(context: Context, crashReportContent: String) {
    val tempFile = File(context.cacheDir, "fauxx_crash_report.txt")
    tempFile.writeText(crashReportContent)

    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        tempFile
    )

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Fauxx Crash Report")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share Crash Report"))
}
