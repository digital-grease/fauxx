package com.fauxx.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.ui.viewmodels.TargetingViewModel

/**
 * Targeting screen: visualizes the Demographic Distancing Engine state.
 * Shows layer toggles, current weights per category (color-coded), persona card.
 */
@Composable
fun TargetingScreen(
    viewModel: TargetingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "TARGETING ENGINE",
            style = MaterialTheme.typography.titleLarge,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        // Layer 1 toggle
        LayerToggleCard(
            layerName = "Layer 1 — Self Report",
            description = "Boosts noise away from your declared demographics",
            enabled = uiState.layer1Enabled,
            onToggle = { viewModel.setLayer1Enabled(it) },
            statusText = if (uiState.hasProfile) "Profile set" else "No profile"
        )

        // Layer 2 toggle
        LayerToggleCard(
            layerName = "Layer 2 — Adversarial Scraper",
            description = "Reads ad-platform profiles to find confirmed interests",
            enabled = uiState.layer2Enabled,
            onToggle = { viewModel.setLayer2Enabled(it) },
            statusText = "Last scraped: ${uiState.lastScrapeDate}",
            actionLabel = "Scrape Now",
            onAction = { viewModel.scrapeNow() }
        )

        // Layer 3 toggle
        LayerToggleCard(
            layerName = "Layer 3 — Persona Rotation",
            description = "Maintains coherent synthetic personas (rotates weekly)",
            enabled = uiState.layer3Enabled,
            onToggle = { viewModel.setLayer3Enabled(it) },
            statusText = uiState.currentPersonaName?.let { "Persona: $it" } ?: "No persona yet",
            actionLabel = "Rotate Now",
            onAction = { viewModel.rotatePersona() }
        )

        // Weight visualization chart
        if (uiState.weights.isNotEmpty()) {
            WeightChart(weights = uiState.weights)
        }

        Spacer(Modifier.height(8.dp))

        // Destructive clear button
        Button(
            onClick = { showClearDialog = true },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Clear My Profile")
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Profile?") },
            text = {
                Text(
                    "This will delete your demographic profile, all platform data, and persona history. " +
                    "The engine will revert to uniform random targeting."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearProfile()
                    showClearDialog = false
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun LayerToggleCard(
    layerName: String,
    description: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    statusText: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = layerName,
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            if (actionLabel != null && onAction != null && enabled) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun WeightChart(weights: Map<CategoryPool, Float>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "CATEGORY WEIGHTS",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            val maxWeight = weights.values.maxOrNull() ?: 1f
            val median = 1f / weights.size

            weights.entries
                .sortedByDescending { it.value }
                .take(15)
                .forEach { (category, weight) ->
                    val barColor = when {
                        weight < median * 0.5f -> MaterialTheme.colorScheme.error        // Suppressed
                        weight > median * 2f -> MaterialTheme.colorScheme.primary        // Boosted
                        else -> MaterialTheme.colorScheme.secondary                       // Neutral
                    }
                    WeightBar(
                        label = category.name.lowercase().replace("_", " "),
                        value = weight / maxWeight,
                        color = barColor
                    )
                    Spacer(Modifier.height(4.dp))
                }
        }
    }
}

@Composable
private fun WeightBar(label: String, value: Float, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth(0.35f),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.outline)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(value.coerceIn(0f, 1f))
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
    }
}
