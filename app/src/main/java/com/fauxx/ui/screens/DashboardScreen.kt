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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.EngineState
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
            text = "FAUXX",
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
                text = "Notification permission denied — background activity indicator is hidden. " +
                    "Grant notification permission in Settings to see the status notification.",
                actionLabel = "Open settings",
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
                text = "Android battery optimization is restricting Fauxx. " +
                    "Background activity will pause when the screen is off. " +
                    "Allow unrestricted background usage to keep the engine running.",
                actionLabel = "Allow",
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
            }
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

        // Current persona card
        uiState.currentPersona?.let { persona ->
            PersonaCard(
                name = persona.name,
                ageRange = persona.ageRange,
                profession = persona.profession,
                interests = persona.interests.take(3).joinToString(", ") {
                    it.name.lowercase().replace("_", " ")
                }
            )
        }

        // Noise ratio indicator
        NoiseRatioCard(ratio = uiState.estimatedNoiseRatio)
    }
}

@Composable
private fun ProtectionCard(enabled: Boolean, engineState: EngineState, onToggle: (Boolean) -> Unit) {
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = when {
                        isPaused -> "PAUSED"
                        enabled -> "ACTIVE"
                        else -> "INACTIVE"
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
                        EngineState.ACTIVE -> "Generating synthetic activity"
                        EngineState.PAUSED_WIFI -> "Waiting for WiFi connection"
                        EngineState.PAUSED_BATTERY -> "Battery below threshold"
                        EngineState.PAUSED_RATE_LIMIT -> "Hourly rate limit reached"
                        EngineState.PAUSED_QUIET_HOURS -> "Outside active hours"
                        EngineState.STOPPED -> "Engine stopped"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun CounterRow(actionsToday: Int, actionsThisWeek: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatCard(label = "TODAY", value = actionsToday.toString(), modifier = Modifier.weight(1f))
        StatCard(label = "THIS WEEK", value = actionsThisWeek.toString(), modifier = Modifier.weight(1f))
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
): List<Pair<String, Float>> {
    val total = distribution.values.sum()
    if (total <= 0f) return emptyList()

    val sorted = distribution.entries.sortedByDescending { it.value }
    val top = sorted.take(MAX_CHART_SLICES).map { (cat, w) ->
        cat.name.lowercase().replace("_", " ") to w
    }
    val otherWeight = sorted.drop(MAX_CHART_SLICES).sumOf { it.value.toDouble() }.toFloat()
    return if (otherWeight > 0f) top + ("other" to otherWeight) else top
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
                title = "CATEGORY DISTRIBUTION",
                help = "Shows how your synthetic noise is spread across topic categories. " +
                    "A wider spread means trackers see a more confused profile. " +
                    "Categories are weighted by the targeting engine to maximize distance from your real interests."
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
private fun DonutChart(slices: List<Pair<String, Float>>, total: Float) {
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
    slices: List<Pair<String, Float>>,
    total: Float,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        slices.forEachIndexed { index, (label, weight) ->
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
                    text = "$label $pct%",
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
                title = "ACTIVE PERSONA",
                titleColor = MaterialTheme.colorScheme.secondary,
                help = "A synthetic identity the engine adopts for about a week. " +
                    "Activity is weighted toward this persona's interests to create " +
                    "temporally coherent noise — making it harder for trackers to " +
                    "filter out the fake signal. Personas rotate automatically."
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "$ageRange · $profession",
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
private fun NoiseRatioCard(ratio: Float) {
    val animated by animateFloatAsState(
        targetValue = ratio.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 1000),
        label = "noise_ratio"
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SectionHeaderWithHelp(
                    title = "NOISE RATIO",
                    help = "Estimates how much of your visible browsing profile is synthetic noise " +
                        "vs real activity. Higher is better — at 80%+, a data broker would need " +
                        "to correctly identify 4 out of 5 signals as fake to build an accurate profile."
                )
                Text(
                    text = "${(animated * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.outline)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animated)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary)
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
                text = "Keep Fauxx running in the background",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Android aggressively pauses background apps to save battery. " +
                        "For continuous profile poisoning, Fauxx needs to be exempt from battery optimization.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "On the next screen, choose \"Allow\" so the engine can keep " +
                        "generating synthetic activity while your screen is off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAllow) {
                Text("Continue")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not now")
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
                text = "Before you start",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Fauxx will perform the following background activities:",
                    style = MaterialTheme.typography.bodyMedium
                )
                ConsentBullet("Search diverse topics on Google, Bing, DuckDuckGo, and Yahoo")
                ConsentBullet("Visit a variety of websites to broaden your browsing profile")
                ConsentBullet("Rotate browser fingerprints (User-Agent, language headers)")
                ConsentBullet("Generate DNS lookups across varied domains")
                ConsentBullet("Use battery and mobile data while running in the background")
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "All activity is synthetic and stays on your device. " +
                        "No personal data is collected or transmitted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text("I understand, start")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
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
