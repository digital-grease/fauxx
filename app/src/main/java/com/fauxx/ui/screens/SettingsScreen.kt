package com.fauxx.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.fauxx.BuildConfig
import com.fauxx.data.model.IntensityLevel
import com.fauxx.ui.theme.ThemeMode
import com.fauxx.ui.viewmodels.SettingsViewModel

/**
 * Global settings screen: intensity, wifi-only, battery threshold, active hours, clear data.
 */
@Composable
fun SettingsScreen(
    onNavigateToAbout: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }
    var showIntensityMenu by remember { mutableStateOf(false) }
    var showLogExportSheet by remember { mutableStateOf(false) }
    var exportedLogs by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "SETTINGS",
            style = MaterialTheme.typography.titleLarge,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        // Intensity
        SettingsCard {
            Text("Intensity", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IntensityLevel.values().forEach { level ->
                    Button(
                        onClick = { viewModel.setIntensity(level) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.intensity == level)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = level.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (uiState.intensity == level)
                                MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            Text(
                text = "${uiState.intensity.actionsPerHour} actions/hour",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Theme
        SettingsCard {
            Text("Theme", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.values().forEach { mode ->
                    Button(
                        onClick = { viewModel.setThemeMode(mode) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.themeMode == mode)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = mode.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (uiState.themeMode == mode)
                                MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            Text(
                text = when (uiState.themeMode) {
                    ThemeMode.SYSTEM -> "Follows your device theme"
                    ThemeMode.LIGHT -> "Always light"
                    ThemeMode.DARK -> "Always dark"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Wi-Fi only toggle
        SettingsCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Wi-Fi Only", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Pause when on mobile data",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = uiState.wifiOnly,
                    onCheckedChange = { viewModel.setWifiOnly(it) }
                )
            }
        }

        // Battery threshold
        SettingsCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Pause below", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${uiState.batteryThreshold}%",
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace
                )
            }
            Slider(
                value = uiState.batteryThreshold.toFloat(),
                onValueChange = { viewModel.setBatteryThreshold(it.toInt()) },
                valueRange = 10f..50f,
                steps = 7
            )
        }

        // Active hours
        SettingsCard {
            Text("Active Hours", style = MaterialTheme.typography.titleSmall)
            Text(
                "${uiState.allowedHoursStart}:00 – ${uiState.allowedHoursEnd}:00",
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Start",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = uiState.allowedHoursStart.toFloat(),
                onValueChange = { viewModel.setAllowedHoursStart(it.toInt()) },
                valueRange = 0f..23f,
                steps = 22
            )
            Text(
                "End",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = uiState.allowedHoursEnd.toFloat(),
                onValueChange = { viewModel.setAllowedHoursEnd(it.toInt()) },
                valueRange = 0f..23f,
                steps = 22
            )
            Text(
                "Activity is paused outside these hours",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val windowHours = (uiState.allowedHoursEnd - uiState.allowedHoursStart).let {
                if (it < 0) it + 24 else it
            }
            if (windowHours in 1..8) {
                Text(
                    "A narrow activity window (${windowHours}h) can itself be a trackable signal. " +
                        "Wider windows (12h+) are harder for trackers to distinguish from real usage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Clear all data
        Button(
            onClick = { showClearDialog = true },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Clear All Data")
        }

        // Export debug logs
        Button(
            onClick = {
                val logs = viewModel.getScrubbedLogs()
                if (logs.isNotBlank()) {
                    exportedLogs = logs
                    showLogExportSheet = true
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Export Debug Logs", color = MaterialTheme.colorScheme.onSurface)
        }

        // About & Privacy
        Button(
            onClick = onNavigateToAbout,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("About & Privacy Policy", color = MaterialTheme.colorScheme.onSurface)
        }

        Spacer(Modifier.height(16.dp))

        // Version info
        Text(
            text = "Fauxx v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear All Data?") },
            text = {
                Text(
                    "This will permanently delete:\n" +
                    "\u2022 All action logs\n" +
                    "\u2022 Your demographic profile\n" +
                    "\u2022 Ad platform profile cache\n" +
                    "\u2022 Persona generation history\n\n" +
                    "All settings will be reset to defaults. " +
                    "The engine will stop and return to Layer 0 (uniform noise).\n\n" +
                    "This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetToDefaults()
                    showClearDialog = false
                }) { Text("Clear Everything", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showLogExportSheet) {
        LogExportSheet(
            title = "Export Debug Logs",
            content = exportedLogs,
            fileName = "fauxx_debug_logs.txt",
            onDismiss = { showLogExportSheet = false }
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}
