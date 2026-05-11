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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.fauxx.BuildConfig
import com.fauxx.R
import com.fauxx.data.model.IntensityLevel
import com.fauxx.locale.SupportedLocale
import com.fauxx.ui.format.displayNameRes
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
    val languageState by viewModel.languageState.collectAsState()
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
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.titleLarge,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        // Intensity
        SettingsCard {
            Text(stringResource(R.string.settings_intensity_title), style = MaterialTheme.typography.titleSmall)
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
                            text = stringResource(level.displayNameRes()),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (uiState.intensity == level)
                                MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.settings_actions_per_hour, uiState.intensity.actionsPerHour),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // App language
        SettingsCard {
            Text(
                stringResource(R.string.settings_language_title),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(8.dp))
            LanguagePickerOption(
                label = stringResource(R.string.settings_language_system_default),
                selected = languageState.userOverride == null,
                enabled = true,
                onClick = { viewModel.setLanguage(null) }
            )
            SupportedLocale.values().forEach { locale ->
                val shipped = locale in languageState.shippedLocales
                LanguagePickerOption(
                    label = locale.displayName,
                    selected = languageState.userOverride == locale,
                    enabled = shipped,
                    suffix = if (!shipped) stringResource(R.string.settings_language_coming_soon) else null,
                    onClick = { viewModel.setLanguage(locale) }
                )
            }
            Text(
                text = if (languageState.userOverride == null) {
                    stringResource(R.string.settings_language_subtitle_system)
                } else {
                    stringResource(R.string.settings_language_subtitle_explicit)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Theme
        SettingsCard {
            Text(stringResource(R.string.settings_theme_title), style = MaterialTheme.typography.titleSmall)
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
                            text = stringResource(mode.displayNameRes()),
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
                    ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system_desc)
                    ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light_desc)
                    ThemeMode.DARK -> stringResource(R.string.settings_theme_dark_desc)
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
                    Text(stringResource(R.string.settings_wifi_only_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.settings_wifi_only_desc),
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

        // Resume after reboot
        SettingsCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_resume_reboot_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.settings_resume_reboot_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = uiState.resumeOnBoot,
                    onCheckedChange = { viewModel.setResumeOnBoot(it) }
                )
            }
        }

        // Battery threshold
        SettingsCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.settings_pause_below_title), style = MaterialTheme.typography.titleSmall)
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
            Text(stringResource(R.string.settings_active_hours_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(
                    R.string.settings_active_hours_value,
                    uiState.allowedHoursStart,
                    uiState.allowedHoursEnd
                ),
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_active_hours_start),
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
                stringResource(R.string.settings_active_hours_end),
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
                stringResource(R.string.settings_active_hours_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val windowHours = (uiState.allowedHoursEnd - uiState.allowedHoursStart).let {
                if (it < 0) it + 24 else it
            }
            if (windowHours in 1..8) {
                Text(
                    stringResource(R.string.settings_active_hours_narrow_warning, windowHours),
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
            Text(stringResource(R.string.settings_clear_all_data))
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
            Text(stringResource(R.string.settings_export_debug_logs), color = MaterialTheme.colorScheme.onSurface)
        }

        // About & Privacy
        Button(
            onClick = onNavigateToAbout,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.settings_about_privacy_policy), color = MaterialTheme.colorScheme.onSurface)
        }

        Spacer(Modifier.height(16.dp))

        // Version info
        Text(
            text = stringResource(
                R.string.settings_version_format,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE
            ),
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
            title = { Text(stringResource(R.string.settings_clear_dialog_title)) },
            text = {
                Text(stringResource(R.string.settings_clear_dialog_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetToDefaults()
                    showClearDialog = false
                }) { Text(stringResource(R.string.settings_clear_dialog_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showLogExportSheet) {
        LogExportSheet(
            title = stringResource(R.string.settings_export_debug_logs),
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

@Composable
private fun LanguagePickerOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    suffix: String? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    selected -> MaterialTheme.colorScheme.onPrimary
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
            if (suffix != null) {
                Text(
                    text = suffix,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}
