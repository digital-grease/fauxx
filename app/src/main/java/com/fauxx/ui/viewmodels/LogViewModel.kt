package com.fauxx.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fauxx.data.db.ActionLogDao
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.model.ActionType
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

data class LogUiState(
    val entries: List<ActionLogEntity> = emptyList(),
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
        LogUiState(
            entries = if (filter == null) all else all.filter { it.actionType == filter },
            filter = filter
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LogUiState())

    fun setFilter(type: ActionType?) { _filter.value = type }

    fun exportCsv(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val entries = dao.getAllForExport()
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val csv = buildString {
                appendLine("timestamp,action_type,category,detail,success")
                entries.forEach { e ->
                    appendLine(
                        "${fmt.format(Date(e.timestamp))}," +
                        "${e.actionType},${e.category}," +
                        "\"${e.detail.replace("\"", "\"\"")}\",${e.success}"
                    )
                }
            }
            onReady(csv)
        }
    }

    fun exportJson(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val entries = dao.getAllForExport()
            onReady(Gson().toJson(entries))
        }
    }
}
