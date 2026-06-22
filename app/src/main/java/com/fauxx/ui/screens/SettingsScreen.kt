package com.fauxx.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.OutlinedButton
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
import com.fauxx.data.model.BatteryThresholdType
import com.fauxx.locale.SupportedLocale
import com.fauxx.ui.format.displayNameRes
import com.fauxx.ui.theme.ThemeMode
import com.fauxx.ui.viewmodels.SettingsViewModel
import kotlin.math.roundToInt

/**
 * Global settings screen: Wi-Fi/mobile intensity tiers, battery threshold, active hours, clear data.
 */
@Composable
fun SettingsScreen(
    onNavigateToAbout: () -> Unit = {},
    onNavigateToSync: () -> Unit = {},
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
            // FlowRow (not Row) so the four tiers wrap gracefully onto a second line on
            // narrow screens / large fonts instead of being crushed (issue #76 added Max).
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IntensityLevel.values().forEach { level ->
                    Button(
                        onClick = { viewModel.setIntensity(level) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.intensity == level)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
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
                // The top tier is framed "up to ~N" — N is a Poisson target, not a guarantee,
                // and a soft cap (WebView exec time) keeps it from being literal.
                text = if (uiState.intensity == IntensityLevel.EXTREME) {
                    stringResource(R.string.settings_intensity_actions_per_hour_max, uiState.intensity.actionsPerHour)
                } else {
                    stringResource(R.string.settings_intensity_actions_per_hour, uiState.intensity.actionsPerHour)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Above MEDIUM the cross-niche dwell floor is lowered to hit the rate, which is a
            // more distinguishable pattern — surface that trade-off honestly (issue #76).
            if (uiState.intensity.actionsPerHour > IntensityLevel.MEDIUM.actionsPerHour) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_intensity_detectability_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
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
                    ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system_subtitle)
                    ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light_subtitle)
                    ThemeMode.DARK -> stringResource(R.string.settings_theme_dark_subtitle)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Mobile data intensity (issue #62) — replaces the old all-or-nothing Wi-Fi-only
        // toggle: Off keeps the legacy pause-on-mobile behavior, the tiers run the engine
        // on mobile data at a (typically lower) rate of their own.
        SettingsCard {
            Text(stringResource(R.string.settings_mobile_intensity_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Off first, then the same tier ladder as the Wi-Fi card above.
                (listOf<IntensityLevel?>(null) + IntensityLevel.values()).forEach { level ->
                    Button(
                        onClick = { viewModel.setMobileIntensity(level) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.mobileIntensity == level)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = if (level == null) stringResource(R.string.settings_intensity_off)
                            else stringResource(level.displayNameRes()),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (uiState.mobileIntensity == level)
                                MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            val mobile = uiState.mobileIntensity
            Text(
                text = when {
                    mobile == null -> stringResource(R.string.settings_mobile_intensity_off_description)
                    mobile == IntensityLevel.EXTREME ->
                        stringResource(R.string.settings_intensity_actions_per_hour_max, mobile.actionsPerHour)
                    else -> stringResource(R.string.settings_intensity_actions_per_hour, mobile.actionsPerHour)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if ((mobile?.actionsPerHour ?: 0) > IntensityLevel.MEDIUM.actionsPerHour) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_intensity_detectability_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
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
                    Text(stringResource(R.string.settings_resume_on_boot_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.settings_resume_on_boot_description),
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
            // While on Battery
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.settings_battery_threshold_battery_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    "${uiState.batteryThresholdBattery}%",
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace
                )
            }
            Slider(
                value = uiState.batteryThresholdBattery.toFloat(),
                onValueChange = { viewModel.setBatteryThreshold(it.toInt(), BatteryThresholdType.BATTERY) },
                valueRange = 0f..100f,
                steps = 19
            )
            Spacer(Modifier.height(8.dp))

            // While charging
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.settings_battery_threshold_charging_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    "${uiState.batteryThresholdCharging}%",
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace
                )
            }
            Slider(
                value = uiState.batteryThresholdCharging.toFloat(),
                onValueChange = { viewModel.setBatteryThreshold(it.toInt(), BatteryThresholdType.CHARGING) },
                valueRange = 0f..100f,
                steps = 19
            )
            Spacer(Modifier.height(8.dp))
        }

        // Active hours
        SettingsCard {
            Text(stringResource(R.string.settings_active_hours_title), style = MaterialTheme.typography.titleSmall)
            Text(
                "${uiState.allowedHoursStart}:00 – ${uiState.allowedHoursEnd}:00",
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_active_hours_start_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = uiState.allowedHoursStart.toFloat(),
                // Issue #61: the slider snaps to integer positions visually, but Compose's
                // internal fraction × range arithmetic produces values like 6.99999… for the
                // hour-7 snap (1/23 × 23 doesn't round-trip exactly in float). toInt()
                // truncates 6.99999 to 6, so dragging onto hour 7 silently writes hour 6 —
                // making 7 unreachable. roundToInt() resolves to the nearest integer hour.
                onValueChange = { viewModel.setAllowedHoursStart(it.roundToInt()) },
                valueRange = 0f..23f,
                steps = 22
            )
            Text(
                stringResource(R.string.settings_active_hours_end_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = uiState.allowedHoursEnd.toFloat(),
                // Issue #128: the End bound reaches 24 (Start stays 0..23) so a user can pick
                // a full-day window. "0:00 – 24:00" renders for free and the engine already
                // treats start < end (0 until 24) as always-active, so no engine change.
                onValueChange = { viewModel.setAllowedHoursEnd(it.roundToInt()) },
                valueRange = 0f..24f,
                steps = 23
            )
            Text(
                stringResource(R.string.settings_active_hours_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val windowHours = (uiState.allowedHoursEnd - uiState.allowedHoursStart).let {
                if (it < 0) it + 24 else it
            }
            if (windowHours in 1..8) {
                Text(
                    stringResource(R.string.settings_active_hours_narrow_window_warning, windowHours),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        // Log retention (issue #73)
        SettingsCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.settings_log_retention_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.settings_log_retention_value, uiState.logRetentionDays),
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace
                )
            }
            Slider(
                value = uiState.logRetentionDays.toFloat(),
                onValueChange = { viewModel.setLogRetentionDays(it.roundToInt()) },
                valueRange = 1f..90f
            )
            Text(
                stringResource(R.string.settings_log_retention_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Custom User-Agent (issue #7)
        SettingsCard {
            Text(
                stringResource(R.string.settings_custom_ua_title),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                stringResource(R.string.settings_custom_ua_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            val ctx = LocalContext.current
            OutlinedButton(
                onClick = {
                    val deviceUa = runCatching {
                        // System WebView UA — what most Chromium-based browsers
                        // (Chrome, Edge, Brave, etc.) and any in-app browser send.
                        // Close enough for "match my browser" without asking the user
                        // to know what a User-Agent string is.
                        android.webkit.WebSettings.getDefaultUserAgent(ctx)
                    }.getOrNull()
                    if (!deviceUa.isNullOrBlank()) viewModel.setCustomUserAgent(deviceUa)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_custom_ua_use_device_button)) }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = uiState.customUserAgent,
                onValueChange = { viewModel.setCustomUserAgent(it) },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(
                        stringResource(R.string.settings_custom_ua_field_label),
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                placeholder = {
                    Text(
                        stringResource(R.string.settings_custom_ua_placeholder),
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                singleLine = true
            )
            if (uiState.customUserAgent.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { viewModel.setCustomUserAgent("") }
                ) { Text(stringResource(R.string.settings_custom_ua_clear_button)) }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Clear all data
        Button(
            onClick = { showClearDialog = true },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.settings_clear_all_data_button))
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
            Text(stringResource(R.string.settings_export_debug_logs_button), color = MaterialTheme.colorScheme.onSurface)
        }

        // LAN sync (E13 #178)
        Button(
            onClick = onNavigateToSync,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.settings_lan_sync_button), color = MaterialTheme.colorScheme.onSurface)
        }

        // About & Privacy
        Button(
            onClick = onNavigateToAbout,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.settings_about_privacy_button), color = MaterialTheme.colorScheme.onSurface)
        }

        Spacer(Modifier.height(16.dp))

        // Version info
        Text(
            text = stringResource(R.string.settings_version_info, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
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
            text = { Text(stringResource(R.string.settings_clear_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetToDefaults()
                    showClearDialog = false
                }) { Text(stringResource(R.string.settings_clear_dialog_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (showLogExportSheet) {
        LogExportSheet(
            title = stringResource(R.string.settings_export_debug_logs_button),
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
