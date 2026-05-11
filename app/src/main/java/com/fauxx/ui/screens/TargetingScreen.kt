package com.fauxx.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.fauxx.R
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.targeting.layer1.InterestMapping
import com.fauxx.targeting.layer1.MappingConfidence
import com.fauxx.ui.format.displayNameRes
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
            text = stringResource(R.string.targeting_title),
            style = MaterialTheme.typography.titleLarge,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        // Layer 1 toggle
        LayerToggleCard(
            layerName = stringResource(R.string.targeting_layer1_name),
            description = stringResource(R.string.targeting_layer1_desc),
            enabled = uiState.layer1Enabled,
            onToggle = { viewModel.setLayer1Enabled(it) },
            statusText = if (uiState.hasProfile) {
                stringResource(R.string.targeting_profile_set)
            } else {
                stringResource(R.string.targeting_no_profile)
            }
        )

        // Custom interests (part of Layer 1)
        if (uiState.layer1Enabled) {
            CustomInterestsCard(
                mappings = uiState.customInterestMappings,
                onAdd = { viewModel.addCustomInterest(it) },
                onRemove = { viewModel.removeCustomInterest(it) }
            )
        }

        // Layer 2 toggle
        LayerToggleCard(
            layerName = stringResource(R.string.targeting_layer2_name),
            description = stringResource(R.string.targeting_layer2_desc),
            enabled = uiState.layer2Enabled,
            onToggle = { viewModel.setLayer2Enabled(it) },
            statusText = stringResource(R.string.targeting_last_scraped, uiState.lastScrapeDate),
            actionLabel = stringResource(R.string.targeting_scrape_now),
            onAction = { viewModel.scrapeNow() }
        )

        // Layer 3 toggle
        LayerToggleCard(
            layerName = stringResource(R.string.targeting_layer3_name),
            description = stringResource(R.string.targeting_layer3_desc),
            enabled = uiState.layer3Enabled,
            onToggle = { viewModel.setLayer3Enabled(it) },
            statusText = uiState.currentPersonaName?.let {
                stringResource(R.string.targeting_persona_status, it)
            } ?: stringResource(R.string.targeting_no_persona),
            actionLabel = stringResource(R.string.targeting_rotate_now),
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
            Text(stringResource(R.string.targeting_clear_profile))
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.targeting_clear_dialog_title)) },
            text = {
                Text(stringResource(R.string.targeting_clear_dialog_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearProfile()
                    showClearDialog = false
                }) { Text(stringResource(R.string.common_clear), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.common_cancel)) }
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
                text = stringResource(R.string.targeting_category_weights),
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
                        label = stringResource(category.displayNameRes()),
                        value = weight / maxWeight,
                        color = barColor
                    )
                    Spacer(Modifier.height(4.dp))
                }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomInterestsCard(
    mappings: List<InterestMapping>,
    onAdd: (String) -> Unit,
    onRemove: (Int) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.targeting_custom_interests_title),
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.targeting_custom_interests_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            var textFieldValue by remember { mutableStateOf("") }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = { textFieldValue = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.targeting_custom_interest_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (textFieldValue.isNotBlank()) {
                            onAdd(textFieldValue)
                            textFieldValue = ""
                        }
                    })
                )
                IconButton(onClick = {
                    if (textFieldValue.isNotBlank()) {
                        onAdd(textFieldValue)
                        textFieldValue = ""
                    }
                }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.targeting_add_interest_cd))
                }
            }

            if (mappings.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    mappings.forEachIndexed { index, mapping ->
        val categoryLabel = mapping.category?.let { stringResource(it.displayNameRes()) }
        val label = if (categoryLabel != null) {
            stringResource(R.string.targeting_interest_mapping, mapping.interest, categoryLabel)
        } else {
            stringResource(
                R.string.targeting_interest_unmapped,
                mapping.interest,
                stringResource(R.string.targeting_unmapped_suffix)
            )
        }
                        InputChip(
                            selected = true,
                            onClick = { onRemove(index) },
                            label = { Text(label, style = MaterialTheme.typography.bodySmall) },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.targeting_remove_cd),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }
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
