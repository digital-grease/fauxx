package com.fauxx.ui.screens

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily

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

