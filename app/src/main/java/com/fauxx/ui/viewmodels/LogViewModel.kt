package com.fauxx.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fauxx.data.db.ActionLogDao
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.db.LogMetadata
import com.fauxx.data.model.ActionType
import com.fauxx.logging.LogScrubber
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** A row in the log list: a calendar-day section header, or an action-log entry (issue #73). */
sealed interface LogListItem {
    /** Section header for a calendar day. [dateKey] is `yyyy-MM-dd` (the stable grouping key). */
    data class DayHeader(val dateKey: String) : LogListItem
    data class Entry(val entity: ActionLogEntity) : LogListItem
}

data class LogUiState(
    val items: List<LogListItem> = emptyList(),
    val filter: ActionType? = null
)

@HiltViewModel
class LogViewModel @Inject constructor(
    private val dao: ActionLogDao
) : ViewModel() {

    private val _filter = MutableStateFlow<ActionType?>(null)

    val uiState: StateFlow<LogUiState> = combine(
        dao.observeAll(),
        _filter
    ) { all, filter ->
        val filtered = if (filter == null) all else all.filter { it.actionType == filter }
        LogUiState(items = groupByDay(filtered), filter = filter)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LogUiState())

    fun setFilter(type: ActionType?) { _filter.value = type }

    /**
     * Group entries (already ordered most-recent-first) into day sections, inserting a
     * [LogListItem.DayHeader] before the first entry of each calendar day. Exposed internal
     * so the grouping can be unit-tested without the Compose layer.
     */
    internal fun groupByDay(entries: List<ActionLogEntity>): List<LogListItem> {
        val items = ArrayList<LogListItem>(entries.size + 8)
        var lastDay: String? = null
        for (e in entries) {
            val day = DAY_KEY_FORMAT.format(Date(e.timestamp))
            if (day != lastDay) {
                items.add(LogListItem.DayHeader(day))
                lastDay = day
            }
            items.add(LogListItem.Entry(e))
        }
        return items
    }

    fun exportCsv(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val entries = dao.getAllForExport()
            val csv = buildString {
                appendLine("timestamp,action_type,category,detail,metadata,success")
                entries.forEach { e ->
                    appendLine(
                        "${EXPORT_DATE_FORMAT.format(Date(e.timestamp))}," +
                            "${e.actionType},${e.category}," +
                            "${csvCell(LogScrubber.scrubForExport(e.actionType, e.detail))}," +
                            "${csvCell(flattenMetadata(e.metadata))},${e.success}"
                    )
                }
            }
            onReady(csv)
        }
    }

    fun exportJson(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val entries = dao.getAllForExport().map {
                it.copy(
                    detail = LogScrubber.scrubForExport(it.actionType, it.detail),
                    metadata = it.metadata?.let { m -> LogScrubber.scrub(m) }
                )
            }
            onReady(Gson().toJson(entries))
        }
    }

    /** Export the full log as a self-contained, scrubbed HTML table (issue #73). */
    fun exportHtml(onReady: (String) -> Unit) {
        viewModelScope.launch {
            onReady(buildHtml(dao.getAllForExport()))
        }
    }

    /**
     * Format a single entry as shareable plain text, scrubbed. Pure function, exposed for the
     * per-entry share action and for unit testing.
     */
    fun formatEntry(e: ActionLogEntity): String = buildString {
        appendLine(EXPORT_DATE_FORMAT.format(Date(e.timestamp)))
        appendLine("${e.actionType} · ${e.category}")
        appendLine(LogScrubber.scrubForExport(e.actionType, e.detail))
        metadataPairs(e.metadata).forEach { (k, v) -> appendLine("$k: $v") }
        append(if (e.success) "Status: success" else "Status: failed")
    }

    private fun buildHtml(entries: List<ActionLogEntity>): String = buildString {
        append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        append("<title>Fauxx action log</title><style>")
        append("body{font-family:sans-serif;margin:16px}table{border-collapse:collapse;width:100%}")
        append("th,td{border:1px solid #ccc;padding:6px;text-align:left;font-size:13px;vertical-align:top}")
        append("th{background:#f0f0f0}</style></head><body>")
        append("<h2>Fauxx action log</h2><table>")
        append("<tr><th>Time</th><th>Type</th><th>Category</th><th>Detail</th><th>Metadata</th><th>Status</th></tr>")
        entries.forEach { e ->
            append("<tr>")
            append("<td>${esc(EXPORT_DATE_FORMAT.format(Date(e.timestamp)))}</td>")
            append("<td>${esc(e.actionType.name)}</td>")
            append("<td>${esc(e.category.name)}</td>")
            append("<td>${esc(LogScrubber.scrubForExport(e.actionType, e.detail))}</td>")
            append("<td>${metadataPairs(e.metadata).joinToString("<br>") { esc("${it.first}: ${it.second}") }}</td>")
            append("<td>${if (e.success) "ok" else "failed"}</td>")
            append("</tr>")
        }
        append("</table></body></html>")
    }

    /** Parse metadata JSON and scrub each value for inclusion in a shareable export. */
    private fun metadataPairs(json: String?): List<Pair<String, String>> =
        LogMetadata.parse(json).map { (k, v) -> k to LogScrubber.scrub(v) }

    private fun flattenMetadata(json: String?): String =
        metadataPairs(json).joinToString("; ") { "${it.first}: ${it.second}" }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /**
     * Render a CSV cell safely. A cell beginning with =, +, -, @, tab, or CR is executed as a
     * formula by spreadsheet apps (CSV injection), so prefix it with a single quote; then
     * RFC-4180-quote the value (double embedded quotes, wrap in quotes). The action-log export is
     * a user-shareable artifact, so detail/metadata are also run through [LogScrubber] first.
     */
    private fun csvCell(raw: String): String {
        val guarded = if (raw.isNotEmpty() && raw.first() in "=+-@\t\r") "'$raw" else raw
        return "\"${guarded.replace("\"", "\"\"")}\""
    }

    companion object {
        private val EXPORT_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        private val DAY_KEY_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}
