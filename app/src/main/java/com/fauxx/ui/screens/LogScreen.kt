package com.fauxx.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.fauxx.R
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.model.ActionType
import com.fauxx.ui.format.displayNameRes
import com.fauxx.ui.viewmodels.LogViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DATE_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
private val FULL_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

/**
 * Scrollable, filterable audit log of all synthetic actions.
 * Supports CSV/JSON export via system share sheet.
 */
@Composable
fun LogScreen(
    viewModel: LogViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showExportMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header. Top padding 4dp matches the global Help overlay (NavGraph.kt) so the
        // download IconButton and the help icon share a vertical baseline. End padding
        // 56dp leaves room for the help icon to the right.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 4.dp, end = 56.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.log_screen_title),
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            val chooserTitle = stringResource(R.string.log_export_chooser_title)
            IconButton(onClick = { showExportMenu = true }) {
                Icon(Icons.Default.Download, stringResource(R.string.log_export_content_desc))
                DropdownMenu(
                    expanded = showExportMenu,
                    onDismissRequest = { showExportMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.log_export_csv)) },
                        onClick = {
                            showExportMenu = false
                            viewModel.exportCsv { csv ->
                                shareText(context, csv, "text/csv", "action_log.csv", chooserTitle)
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.log_export_json)) },
                        onClick = {
                            showExportMenu = false
                            viewModel.exportJson { json ->
                                shareText(context, json, "application/json", "action_log.json", chooserTitle)
                            }
                        }
                    )
                }
            }
        }

        // Type filter chips
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChip(
                    selected = uiState.filter == null,
                    onClick = { viewModel.setFilter(null) },
                    label = { Text(stringResource(R.string.log_filter_all)) }
                )
            }
            items(ActionType.values()) { type ->
                FilterChip(
                    selected = uiState.filter == type,
                    onClick = { viewModel.setFilter(type) },
                    label = { Text(stringResource(type.displayNameRes())) }
                )
            }
        }

        // Log list
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(uiState.entries) { entry ->
                LogEntryRow(entry)
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: ActionLogEntity) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = DATE_FORMAT.format(Date(entry.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(entry.actionType.displayNameRes()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(entry.category.displayNameRes()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
                if (!expanded) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = entry.detail.removePrefix("[${entry.category}] ").take(40),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    Spacer(Modifier.height(8.dp))
                    DetailRow(stringResource(R.string.log_detail_type), stringResource(entry.actionType.displayNameRes()))
                    DetailRow(stringResource(R.string.log_detail_category), stringResource(entry.category.displayNameRes()))
                    DetailRow(stringResource(R.string.log_detail_detail), entry.detail.removePrefix("[${entry.category}] "))
                    DetailRow(stringResource(R.string.log_detail_time), FULL_DATE_FORMAT.format(Date(entry.timestamp)))
                    DetailRow(
                        stringResource(R.string.log_detail_status),
                        stringResource(if (entry.success) R.string.log_detail_status_success else R.string.log_detail_status_failed)
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun shareText(
    context: android.content.Context,
    text: String,
    mimeType: String,
    filename: String,
    chooserTitle: String
) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, filename)
    }
    context.startActivity(Intent.createChooser(intent, chooserTitle))
}
