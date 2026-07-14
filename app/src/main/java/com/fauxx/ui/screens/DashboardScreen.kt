package com.fauxx.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.fauxx.R
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.EngineState
import com.fauxx.ui.format.displayNameRes
import com.fauxx.ui.format.personaAgeRangeRes
import com.fauxx.ui.format.personaProfessionRes
import com.fauxx.ui.viewmodels.DashboardViewModel

/**
 * Dashboard screen showing: protection on/off toggle, action counters, category distribution
 * donut chart, current persona card, and estimated noise ratio.
 */
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val showConsent by viewModel.showConsentDialog.collectAsState()
    val showFullVersionNotice by viewModel.showFullVersionNotice.collectAsState()
    val context = LocalContext.current

    // POST_NOTIFICATIONS permission (Android 13+)
    var notificationDenied by remember { mutableStateOf(false) }

    // Battery-optimization exemption state. Recomputed on every recomposition so that
    // returning from the system settings screen refreshes the warning card.
    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    var batteryOptimized by remember {
        mutableStateOf(
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) == false
        )
    }
    var showBatteryExplainer by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationDenied = !granted
        // Proceed with engine activation regardless — service works without notification
        viewModel.toggleEngine(true)
        // After notification flow, prompt for battery-optimization exemption if needed.
        if (powerManager?.isIgnoringBatteryOptimizations(context.packageName) == false) {
            showBatteryExplainer = true
        }
    }

    val batterySettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        batteryOptimized =
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) == false
    }

    if (showConsent) {
        ConsentDialog(
            onAccept = { viewModel.acceptConsent() },
            onDismiss = { viewModel.dismissConsent() }
        )
    }

    if (showBatteryExplainer) {
        BatteryOptimizationDialog(
            onAllow = {
                showBatteryExplainer = false
                // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS shows the system dialog
                // directly; falls back to the settings list if unavailable.
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                )
                runCatching { batterySettingsLauncher.launch(intent) }
                    .onFailure {
                        batterySettingsLauncher.launch(
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        )
                    }
            },
            onDismiss = { showBatteryExplainer = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Title
        Text(
            text = stringResource(R.string.dashboard_title),
            style = MaterialTheme.typography.headlineLarge,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        // Play Store flavor: nudge users toward the full F-Droid / GitHub build.
        FullVersionNoticeCard(
            visible = showFullVersionNotice,
            onDismiss = viewModel::dismissFullVersionNotice
        )

        // Notification permission warning
        if (notificationDenied) {
            WarningCard(
                text = stringResource(R.string.dashboard_warning_notification_denied),
                actionLabel = stringResource(R.string.dashboard_warning_action_open_settings),
                onAction = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    runCatching { context.startActivity(intent) }
                }
            )
        }

        // Battery-optimization warning: the OS will doze Fauxx during screen-off if
        // the app isn't on the unrestricted list. Shown until the user grants exemption.
        if (batteryOptimized && uiState.engineEnabled) {
            WarningCard(
                text = stringResource(R.string.dashboard_warning_battery_optimized),
                actionLabel = stringResource(R.string.dashboard_warning_action_allow),
                onAction = { showBatteryExplainer = true }
            )
        }

        // Health warnings from asset loading
        if (uiState.healthWarnings.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    uiState.healthWarnings.forEach { warning ->
                        Text(
                            text = warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        // Protection toggle
        ProtectionCard(
            enabled = uiState.engineEnabled,
            engineState = uiState.engineState,
            onToggle = { enabled ->
                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val hasPermission = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!hasPermission) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        return@ProtectionCard
                    }
                }
                viewModel.toggleEngine(enabled)
            },
            onUseMobileData = { viewModel.enableMobileData() },
            // The one-tap escape hatch only helps when the pause is caused by mobile
            // being Off; with a tier already set, PAUSED_WIFI means "no network at all"
            // and the button could only downgrade the user's chosen tier (issue #62).
            showUseMobileData = uiState.mobileIntensity == null
        )

        // Action counters
        CounterRow(
            actionsToday = uiState.actionsToday,
            actionsThisWeek = uiState.actionsThisWeek
        )

        // Category distribution donut chart
        if (uiState.categoryDistribution.isNotEmpty()) {
            CategoryDonutCard(distribution = uiState.categoryDistribution)
        }

        // Current persona card. Demographics are stored as enum names ("AGE_35_44");
        // resolve to localized labels, falling back to the raw string for personas
        // persisted before the enum-name canonicalization.
        uiState.currentPersona?.let { persona ->
            val interestLabels = persona.interests.take(3)
                .map { stringResource(it.displayNameRes()) }
            PersonaCard(
                name = persona.name,
                ageRange = personaAgeRangeRes(persona.ageRange)
                    ?.let { stringResource(it) } ?: persona.ageRange,
                profession = personaProfessionRes(persona.profession)
                    ?.let { stringResource(it) } ?: persona.profession,
                interests = interestLabels.joinToString(", ")
            )
        }

        // Synthetic-activity rate (issue #160)
        SyntheticActivityCard(actionsPerHour = uiState.syntheticActionsPerHour)

        // Profile-drift indicator (issue #171 E2)
        DriftCard(driftState = uiState.driftState, kl = uiState.driftKlDivergence)

        // Poisoned-vs-control divergence (issue #172 E3) — shown once a control profile is imported.
        if (uiState.hasControlSeries) {
            DriftCard(
                driftState = uiState.controlDivergenceState,
                kl = uiState.controlDivergenceKl,
                title = stringResource(R.string.dashboard_control_divergence_title),
                help = stringResource(R.string.dashboard_control_divergence_help),
            )
        }
    }
}

@Composable
private fun ProtectionCard(
    enabled: Boolean,
    engineState: EngineState,
    onToggle: (Boolean) -> Unit,
    onUseMobileData: () -> Unit,
    showUseMobileData: Boolean
) {
    val isPaused = enabled && engineState != EngineState.ACTIVE && engineState != EngineState.STOPPED

    Card(
        colors = CardDefaults.cardColors(
            containerColor = when {
                isPaused -> MaterialTheme.colorScheme.tertiaryContainer
                enabled -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            isPaused -> stringResource(R.string.dashboard_paused)
                            enabled -> stringResource(R.string.dashboard_active)
                            else -> stringResource(R.string.dashboard_inactive)
                        },
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isPaused -> MaterialTheme.colorScheme.tertiary
                            enabled -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Text(
                        text = when (engineState) {
                            EngineState.ACTIVE -> stringResource(R.string.dashboard_engine_state_active)
                            EngineState.PAUSED_WIFI -> stringResource(R.string.dashboard_engine_state_paused_wifi)
                            EngineState.PAUSED_BATTERY -> stringResource(R.string.dashboard_engine_state_paused_battery)
                            EngineState.PAUSED_RATE_LIMIT -> stringResource(R.string.dashboard_engine_state_paused_rate_limit)
                            EngineState.PAUSED_QUIET_HOURS -> stringResource(R.string.dashboard_engine_state_paused_quiet_hours)
                            EngineState.STOPPED -> stringResource(R.string.dashboard_engine_state_stopped)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            // One-tap "opt into mobile data" for users who didn't realise the mobile-data
            // tier was a setting they could change (issue #38; tier ladder since #62).
            // Only shown when mobile is actually Off — see the call site.
            if (engineState == EngineState.PAUSED_WIFI && showUseMobileData) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onUseMobileData,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.dashboard_action_use_mobile_data))
                }
            }
        }
    }
}

@Composable
private fun CounterRow(actionsToday: Int, actionsThisWeek: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatCard(label = stringResource(R.string.dashboard_today), value = actionsToday.toString(), modifier = Modifier.weight(1f))
        StatCard(label = stringResource(R.string.dashboard_this_week), value = actionsThisWeek.toString(), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private const val MAX_CHART_SLICES = 8

private val chartColors = listOf(
    Color(0xFF00FF88), Color(0xFF00E5FF), Color(0xFFFF6B35),
    Color(0xFFAA00FF), Color(0xFFFFD700), Color(0xFFFF69B4),
    Color(0xFF7CFC00), Color(0xFF00BFFF), Color(0xFF808080)
)

/**
 * Prepares chart data: sorts by weight descending, keeps top [MAX_CHART_SLICES] categories,
 * and aggregates the rest into an "Other" slice.
 */
private fun buildChartSlices(
    distribution: Map<CategoryPool, Float>
): List<Pair<CategoryPool?, Float>> {
    val total = distribution.values.sum()
    if (total <= 0f) return emptyList()

    val sorted = distribution.entries.sortedByDescending { it.value }
    val top: List<Pair<CategoryPool?, Float>> = sorted.take(MAX_CHART_SLICES).map { (cat, w) -> cat to w }
    val otherWeight = sorted.drop(MAX_CHART_SLICES).sumOf { it.value.toDouble() }.toFloat()
    return if (otherWeight > 0f) top + (null to otherWeight) else top
}

@Composable
private fun CategoryDonutCard(distribution: Map<CategoryPool, Float>) {
    val slices = remember(distribution) { buildChartSlices(distribution) }
    val total = remember(slices) { slices.sumOf { it.second.toDouble() }.toFloat() }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeaderWithHelp(
                title = stringResource(R.string.dashboard_category_distribution_title),
                help = stringResource(R.string.dashboard_category_distribution_help)
            )
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DonutChart(slices = slices, total = total)
                ChartLegend(
                    slices = slices,
                    total = total,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun DonutChart(slices: List<Pair<CategoryPool?, Float>>, total: Float) {
    Canvas(modifier = Modifier.size(140.dp)) {
        if (total <= 0f) return@Canvas

        var startAngle = -90f
        slices.forEachIndexed { index, (_, weight) ->
            val sweep = (weight / total) * 360f
            drawArc(
                color = chartColors[index % chartColors.size],
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(size.width * 0.1f, size.height * 0.1f),
                size = Size(size.width * 0.8f, size.height * 0.8f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 24f)
            )
            startAngle += sweep
        }
    }
}

@Composable
private fun ChartLegend(
    slices: List<Pair<CategoryPool?, Float>>,
    total: Float,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val otherLabel = stringResource(R.string.dashboard_category_other)
        slices.forEachIndexed { index, (category, weight) ->
            val label = category?.let { stringResource(it.displayNameRes()) } ?: otherLabel
            val pct = if (total > 0f) (weight / total * 100f).toInt() else 0
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(chartColors[index % chartColors.size])
                )
                Text(
                    text = stringResource(R.string.dashboard_chart_legend_entry, label, pct),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun PersonaCard(name: String, ageRange: String, profession: String, interests: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeaderWithHelp(
                title = stringResource(R.string.dashboard_active_persona_title),
                titleColor = MaterialTheme.colorScheme.secondary,
                help = stringResource(R.string.dashboard_active_persona_help)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.dashboard_persona_subtitle, ageRange, profession),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = interests,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun SectionHeaderWithHelp(
    title: String,
    help: String,
    titleColor: Color = Color.Unspecified
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier.clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = if (titleColor != Color.Unspecified) titleColor
                    else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (expanded) "\u25B2" else "?",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Text(
                text = help,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun SyntheticActivityCard(actionsPerHour: Float) {
    // A plain rate read-out, deliberately NOT a fill bar: the old bar read like a slider and was
    // mistaken for a control (issues #149/#150), and the percentage implied a real-vs-fake ratio
    // Android cannot measure (issue #160).
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SectionHeaderWithHelp(
                title = stringResource(R.string.dashboard_synthetic_activity_title),
                help = stringResource(R.string.dashboard_synthetic_activity_help)
            )
            Text(
                text = stringResource(R.string.dashboard_synthetic_activity_value, actionsPerHour),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun DriftCard(
    driftState: com.fauxx.targeting.layer2.DriftState,
    kl: Double?,
    title: String = stringResource(R.string.dashboard_drift_title),
    help: String = stringResource(R.string.dashboard_drift_help),
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SectionHeaderWithHelp(
                    title = title,
                    help = help
                )
                Text(
                    text = when {
                        driftState == com.fauxx.targeting.layer2.DriftState.AVAILABLE && kl != null ->
                            stringResource(R.string.dashboard_drift_value, kl)
                        // #220: an empty imported profile (e.g. personalized ads off) can never
                        // produce a value, so say so instead of "collecting…" forever.
                        driftState == com.fauxx.targeting.layer2.DriftState.NO_PROFILE ->
                            stringResource(R.string.dashboard_drift_no_profile)
                        else -> stringResource(R.string.dashboard_drift_collecting)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun WarningCard(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            if (actionLabel != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun BatteryOptimizationDialog(onAllow: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.dashboard_battery_dialog_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.dashboard_battery_dialog_body_intro),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.dashboard_battery_dialog_body_action),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAllow) {
                Text(stringResource(R.string.dashboard_battery_dialog_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dashboard_battery_dialog_not_now))
            }
        }
    )
}

@Composable
private fun ConsentDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.consent_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.consent_intro),
                    style = MaterialTheme.typography.bodyMedium
                )
                ConsentBullet(stringResource(R.string.consent_bullet_search))
                ConsentBullet(stringResource(R.string.consent_bullet_browse))
                ConsentBullet(stringResource(R.string.consent_bullet_fingerprint))
                ConsentBullet(stringResource(R.string.consent_bullet_dns))
                ConsentBullet(stringResource(R.string.consent_bullet_battery))
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.consent_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(stringResource(R.string.consent_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun ConsentBullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "\u2022",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
