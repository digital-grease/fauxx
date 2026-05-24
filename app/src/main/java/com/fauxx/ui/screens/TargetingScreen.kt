package com.fauxx.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.fauxx.R
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.targeting.layer1.InterestMapping
import com.fauxx.targeting.layer1.MappingConfidence
import com.fauxx.targeting.layer2.importers.ImportResult
import com.fauxx.targeting.layer2.importers.ImportSource
import com.fauxx.ui.format.displayNameRes
import com.fauxx.ui.viewmodels.TargetingUiState
import com.fauxx.ui.viewmodels.TargetingViewModel
import androidx.compose.ui.res.stringResource

/**
 * Targeting screen: visualizes the Demographic Distancing Engine state.
 * Shows layer toggles, current weights per category (color-coded), persona card.
 */
@Composable
fun TargetingScreen(
    viewModel: TargetingViewModel = hiltViewModel(),
    onEditProfile: () -> Unit = {}
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
            description = stringResource(R.string.targeting_layer1_description),
            enabled = uiState.layer1Enabled,
            onToggle = { viewModel.setLayer1Enabled(it) },
            statusText = if (uiState.hasProfile) stringResource(R.string.targeting_layer1_status_set)
            else stringResource(R.string.targeting_layer1_status_unset)
        )

        // Saved demographic profile (issue #29 — there was no view-or-edit path
        // for these values once onboarding completed; users had to wipe via
        // "Reset to defaults" to re-enter).
        ProfileSummaryCard(state = uiState, onEditProfile = onEditProfile)

        // Custom interests (part of Layer 1)
        if (uiState.layer1Enabled) {
            CustomInterestsCard(
                mappings = uiState.customInterestMappings,
                onAdd = { viewModel.addCustomInterest(it) },
                onRemove = { viewModel.removeCustomInterest(it) }
            )
        }

        // Layer 2 — user-driven import (replaced the live in-app scraper in v0.3.0 per
        // issue #52; cookie isolation between apps made the old approach unworkable).
        LayerToggleCard(
            layerName = stringResource(R.string.targeting_layer2_name),
            description = stringResource(R.string.targeting_layer2_description),
            enabled = uiState.layer2Enabled,
            onToggle = { viewModel.setLayer2Enabled(it) },
            statusText = stringResource(R.string.targeting_layer2_last_imported, uiState.lastImportedDate)
        )

        if (uiState.layer2Enabled) {
            // 90-day reminder. Muteable (snooze / permanent) so it doesn't nag.
            if (uiState.showImportReminder) {
                ImportReminderBanner(
                    onSnooze = { viewModel.snoozeImportReminder() },
                    onMute = { viewModel.muteImportReminderPermanently() }
                )
            }
            ImportButtonsCard(
                uiState = uiState,
                onImportGoogle = { viewModel.importGoogleTakeout(it) },
                onImportFacebook = { viewModel.importFacebookDyi(it) },
                onDismissResult = { viewModel.dismissImportResult() }
            )
        }

        // Layer 3 toggle
        LayerToggleCard(
            layerName = stringResource(R.string.targeting_layer3_name),
            description = stringResource(R.string.targeting_layer3_description),
            enabled = uiState.layer3Enabled,
            onToggle = { viewModel.setLayer3Enabled(it) },
            statusText = uiState.currentPersonaName?.let { stringResource(R.string.targeting_layer3_status_active, it) }
                ?: stringResource(R.string.targeting_layer3_status_none),
            actionLabel = stringResource(R.string.targeting_layer3_action_rotate_now),
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
            Text(stringResource(R.string.targeting_clear_profile_button))
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.targeting_clear_dialog_title)) },
            text = { Text(stringResource(R.string.targeting_clear_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearProfile()
                    showClearDialog = false
                }) { Text(stringResource(R.string.targeting_clear_dialog_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
private fun ProfileSummaryCard(
    state: TargetingUiState,
    onEditProfile: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.targeting_profile_summary_title),
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))

            if (!state.hasProfile) {
                Text(
                    text = stringResource(R.string.targeting_profile_summary_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onEditProfile,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.targeting_profile_set_up_button)) }
                return@Card
            }

            ProfileSummaryRow(label = stringResource(R.string.targeting_profile_row_age), value = state.ageRange?.let { stringResource(it.displayNameRes()) })
            ProfileSummaryRow(label = stringResource(R.string.targeting_profile_row_gender), value = state.gender?.let { stringResource(it.displayNameRes()) })
            ProfileSummaryRow(label = stringResource(R.string.targeting_profile_row_profession), value = state.profession?.let { stringResource(it.displayNameRes()) })
            ProfileSummaryRow(label = stringResource(R.string.targeting_profile_row_region), value = state.region?.let { stringResource(it.displayNameRes()) })
            val interestsValue = if (state.interests.isEmpty()) null
            else state.interests
                .map { stringResource(it.displayNameRes()) }
                .joinToString(", ")
            ProfileSummaryRow(
                label = stringResource(R.string.targeting_profile_row_interests),
                value = interestsValue
            )

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onEditProfile,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.targeting_profile_edit_button)) }
        }
    }
}

@Composable
private fun ProfileSummaryRow(label: String, value: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value ?: "—",
            style = MaterialTheme.typography.bodySmall,
            color = if (value != null) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace
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
    onAction: (() -> Unit)? = null,
    actionEnabled: Boolean = true,
    actionEmphasizeError: Boolean = false
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
                OutlinedButton(
                    onClick = onAction,
                    enabled = actionEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        actionLabel,
                        color = if (actionEmphasizeError) MaterialTheme.colorScheme.error
                        else Color.Unspecified
                    )
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
                text = stringResource(R.string.targeting_category_weights_title),
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
                text = stringResource(R.string.targeting_custom_interests_description),
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
                    placeholder = { Text(stringResource(R.string.onboarding_custom_interest_placeholder)) },
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
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.onboarding_add_interest_cd))
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
                            stringResource(R.string.targeting_custom_interest_mapped, mapping.interest, categoryLabel)
                        } else {
                            stringResource(R.string.targeting_custom_interest_unmapped, mapping.interest)
                        }
                        InputChip(
                            selected = true,
                            onClick = { onRemove(index) },
                            label = { Text(label, style = MaterialTheme.typography.bodySmall) },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.onboarding_remove_cd),
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

/**
 * Two SAF launchers (Google Takeout ZIP/JSON, Facebook DYI ZIP/JSON) plus the live
 * import progress / last-result feedback. Replaces the v0.2.x "Scrape Now" button.
 *
 * MIME filter passed to OpenDocument is `{application/zip, application/json}` — most
 * file pickers honor this; some (especially OEM-skinned ones) ignore the filter and
 * show all files anyway. We don't reject by MIME on the receiving side because users
 * sometimes get files with surprising or empty MIME types from share-extracted
 * downloads — the importer's content-sniffing handles real-world inputs.
 */
@Composable
private fun ImportButtonsCard(
    uiState: TargetingUiState,
    onImportGoogle: (android.net.Uri) -> Unit,
    onImportFacebook: (android.net.Uri) -> Unit,
    onDismissResult: () -> Unit
) {
    val mimeFilter = remember { arrayOf("application/zip", "application/json", "*/*") }
    val googleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onImportGoogle) }
    val facebookLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onImportFacebook) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.targeting_import_card_title),
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.targeting_import_card_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            val googleBusy = uiState.importInProgress == ImportSource.GOOGLE_TAKEOUT
            val facebookBusy = uiState.importInProgress == ImportSource.FACEBOOK_DYI
            val anyBusy = uiState.importInProgress != null

            OutlinedButton(
                onClick = { googleLauncher.launch(mimeFilter) },
                enabled = !anyBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(
                    if (googleBusy) R.string.targeting_import_google_button_busy
                    else R.string.targeting_import_google_button
                ))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { facebookLauncher.launch(mimeFilter) },
                enabled = !anyBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(
                    if (facebookBusy) R.string.targeting_import_facebook_button_busy
                    else R.string.targeting_import_facebook_button
                ))
            }

            uiState.lastImportResult?.let { result ->
                Spacer(Modifier.height(12.dp))
                ImportResultRow(result = result, onDismiss = onDismissResult)
            }
        }
    }
}

/**
 * Per-result feedback row rendered under the import buttons. Colors itself by outcome
 * (primary tint for success, error tint for failure variants). Stays up for ~4s then
 * auto-clears via the ViewModel's delay; the X gives the user an immediate dismiss.
 */
@Composable
private fun ImportResultRow(result: ImportResult, onDismiss: () -> Unit) {
    val (text, isError) = when (result) {
        is ImportResult.Success -> {
            val n = result.categoryCount
            val countLabel = if (n == 1) stringResource(R.string.targeting_import_count_one)
            else stringResource(R.string.targeting_import_count_many, n)
            stringResource(R.string.targeting_import_success, countLabel, result.source.displayName) to false
        }
        is ImportResult.WrongFormat -> stringResource(result.reasonRes) to true
        is ImportResult.ParseError ->
            stringResource(R.string.targeting_import_parse_error, result.source.displayName, result.message) to true
        is ImportResult.IoError ->
            stringResource(R.string.targeting_import_io_error) to true
    }
    val tint = if (isError) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = tint,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.targeting_import_dismiss_cd),
                modifier = Modifier.size(16.dp),
                tint = tint
            )
        }
    }
}

/**
 * Soft nudge rendered when the most-recent import is > 90 days old. Two dismiss options:
 *  - **Snooze**: re-shows in 30 days. For users who plan to refresh but not right now.
 *  - **Mute**: never shows again (until a successful import resets the pref). For users
 *    who explicitly don't want the reminder.
 */
@Composable
private fun ImportReminderBanner(onSnooze: () -> Unit, onMute: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.targeting_import_reminder_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onSnooze) { Text(stringResource(R.string.targeting_import_snooze_button)) }
                Spacer(Modifier.size(4.dp))
                TextButton(onClick = onMute) { Text(stringResource(R.string.targeting_import_mute_button)) }
            }
        }
    }
}
