package com.fauxx.ui.screens

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.EngineState
import com.fauxx.ui.viewmodels.DashboardViewModel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dashboard screen showing: protection on/off toggle, action counters, category distribution
 * donut chart, current persona card, and estimated noise ratio.
 */
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

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

        // Protection toggle
        ProtectionCard(
            enabled = uiState.engineEnabled,
            engineState = uiState.engineState,
            onToggle = { viewModel.toggleEngine(it) }
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

@Composable
private fun CategoryDonutCard(distribution: Map<CategoryPool, Float>) {
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

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                DonutChart(data = distribution)
            }
        }
    }
}

@Composable
private fun DonutChart(data: Map<CategoryPool, Float>) {
    val colors = listOf(
        Color(0xFF00FF88), Color(0xFF00E5FF), Color(0xFFFF6B35),
        Color(0xFFAA00FF), Color(0xFFFFD700), Color(0xFFFF69B4),
        Color(0xFF7CFC00), Color(0xFF00BFFF)
    )

    Canvas(modifier = Modifier.size(180.dp)) {
        val total = data.values.sum()
        if (total <= 0f) return@Canvas

        var startAngle = -90f
        data.entries.toList().take(8).forEachIndexed { index, (_, weight) ->
            val sweep = (weight / total) * 360f
            drawArc(
                color = colors[index % colors.size],
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(size.width * 0.1f, size.height * 0.1f),
                size = Size(size.width * 0.8f, size.height * 0.8f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 30f)
            )
            startAngle += sweep
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
