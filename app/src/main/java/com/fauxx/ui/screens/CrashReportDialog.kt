package com.fauxx.ui.screens

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.fauxx.R

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
                stringResource(R.string.crash_dialog_title),
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.error
            )
        },
        text = {
            Text(stringResource(R.string.crash_dialog_body))
        },
        confirmButton = {
            TextButton(onClick = onShare) {
                Text(stringResource(R.string.crash_dialog_share))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.crash_dialog_dismiss))
            }
        }
    )
}
