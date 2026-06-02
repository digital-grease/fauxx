package com.fauxx.ui.viewmodels

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fauxx.data.db.ActionLogDao
import com.fauxx.data.model.PoisonProfile
import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.di.PreferenceKeys
import com.fauxx.engine.EngineState
import com.fauxx.engine.PoisonEngine
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.service.PhantomForegroundService
import com.fauxx.targeting.TargetingEngine
import com.fauxx.targeting.layer3.PersonaRotationLayer
import com.fauxx.util.Clock
import com.fauxx.util.SystemClockImpl
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val engineEnabled: Boolean = false,
    val engineState: EngineState = EngineState.STOPPED,
    val actionsToday: Int = 0,
    val actionsThisWeek: Int = 0,
    val categoryDistribution: Map<CategoryPool, Float> = emptyMap(),
    val currentPersona: SyntheticPersona? = null,
    val estimatedNoiseRatio: Float = 0f,
    val healthWarnings: List<String> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val actionLogDao: ActionLogDao,
    private val profileRepo: PoisonProfileRepository,
    private val poisonEngine: PoisonEngine,
    private val targetingEngine: TargetingEngine,
    private val personaLayer: PersonaRotationLayer,
    private val dataStore: DataStore<Preferences>,
    private val clock: Clock = SystemClockImpl(),
) : ViewModel() {

    private val _enabled = MutableStateFlow(profileRepo.getProfile().enabled)

    /** Whether to show the consent dialog (first-time activation). */
    private val _showConsentDialog = MutableStateFlow(false)
    val showConsentDialog: StateFlow<Boolean> = _showConsentDialog

    /** Whether the "get full version" notice should be shown (Play flavor only, undismissed). */
    val showFullVersionNotice: StateFlow<Boolean> = dataStore.data
        .map { it[PreferenceKeys.FULL_VERSION_NOTICE_DISMISSED] != true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Persists the user's choice to dismiss the full-version notice. */
    fun dismissFullVersionNotice() {
        viewModelScope.launch {
            dataStore.edit { it[PreferenceKeys.FULL_VERSION_NOTICE_DISMISSED] = true }
        }
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        _enabled,
        windowedActionCount(DAY_MS),
        windowedActionCount(WEEK_MS),
        targetingEngine.getWeights(),
        personaLayer.currentPersona,
        poisonEngine.healthWarnings,
        poisonEngine.engineState
    ) { flows ->
        @Suppress("UNCHECKED_CAST")
        val enabled = flows[0] as Boolean
        val today = flows[1] as Int
        val week = flows[2] as Int
        @Suppress("UNCHECKED_CAST")
        val weights = flows[3] as Map<CategoryPool, Float>
        val persona = flows[4] as SyntheticPersona?
        @Suppress("UNCHECKED_CAST")
        val warnings = flows[5] as List<String>
        val state = flows[6] as EngineState
        DashboardUiState(
            engineEnabled = enabled,
            engineState = state,
            actionsToday = today,
            actionsThisWeek = week,
            categoryDistribution = weights,
            currentPersona = persona,
            estimatedNoiseRatio = computeNoiseRatio(today),
            healthWarnings = warnings
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardUiState())

    fun toggleEngine(enabled: Boolean) {
        if (enabled) {
            // Check if user has accepted consent before first activation
            viewModelScope.launch {
                val prefs = dataStore.data.first()
                val consented = prefs[PreferenceKeys.CONSENT_ACCEPTED] ?: false
                if (!consented) {
                    _showConsentDialog.value = true
                    return@launch
                }
                activateEngine()
            }
        } else {
            deactivateEngine()
        }
    }

    /** User accepted the consent dialog. */
    fun acceptConsent() {
        _showConsentDialog.value = false
        viewModelScope.launch {
            dataStore.edit { it[PreferenceKeys.CONSENT_ACCEPTED] = true }
            activateEngine()
        }
    }

    /** User dismissed the consent dialog. */
    fun dismissConsent() {
        _showConsentDialog.value = false
    }

    /**
     * Toggle the wifi-only constraint. Exposed on the Dashboard so a user who sees
     * the engine paused on "Wi-Fi only mode" can opt into mobile data with one tap
     * — the Settings toggle is the same action, but discoverability there is poor
     * (see issue #38).
     */
    fun setWifiOnly(value: Boolean) {
        viewModelScope.launch {
            profileRepo.saveProfile(profileRepo.getProfile().copy(wifiOnly = value))
        }
    }

    private fun activateEngine() {
        viewModelScope.launch {
            profileRepo.saveProfile(profileRepo.getProfile().copy(enabled = true))
        }
        _enabled.value = true
        context.startForegroundService(PhantomForegroundService.startIntent(context))
    }

    private fun deactivateEngine() {
        viewModelScope.launch {
            profileRepo.saveProfile(profileRepo.getProfile().copy(enabled = false))
        }
        _enabled.value = false
        context.startService(PhantomForegroundService.stopIntent(context))
    }

    private fun computeNoiseRatio(actionsToday: Int): Float {
        // Simple heuristic: 0% at 0 actions, 100% at 500+ actions per day
        return (actionsToday / 500f).coerceIn(0f, 1f)
    }

    /**
     * Count of successful actions within a rolling [windowMs] window. The window boundary is
     * recomputed every [WINDOW_REFRESH_MS] via a ticker so it actually SLIDES. Computing the
     * boundary once at flow-construction time froze it, so "today" / "this week" silently
     * widened the longer the dashboard stayed open.
     */
    private fun windowedActionCount(windowMs: Long): Flow<Int> =
        flow {
            while (true) {
                emit(Unit)
                delay(WINDOW_REFRESH_MS)
            }
        }.flatMapLatest {
            actionLogDao.countSince(clock.currentTimeMillis() - windowMs)
        }

    companion object {
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val WEEK_MS = 7 * DAY_MS
        /** How often the rolling-window boundary is recomputed (so the window slides). */
        private const val WINDOW_REFRESH_MS = 60_000L
    }
}
