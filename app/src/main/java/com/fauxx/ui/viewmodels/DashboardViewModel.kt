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
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    val estimatedNoiseRatio: Float = 0f
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val actionLogDao: ActionLogDao,
    private val profileRepo: PoisonProfileRepository,
    private val poisonEngine: PoisonEngine,
    private val targetingEngine: TargetingEngine,
    private val personaLayer: PersonaRotationLayer,
    private val dataStore: DataStore<Preferences>
) : ViewModel() {

    private val _enabled = MutableStateFlow(profileRepo.getProfile().enabled)

    /** Whether to show the consent dialog (first-time activation). */
    private val _showConsentDialog = MutableStateFlow(false)
    val showConsentDialog: StateFlow<Boolean> = _showConsentDialog

    val uiState: StateFlow<DashboardUiState> = combine(
        _enabled,
        actionLogDao.countSince(System.currentTimeMillis() - 24 * 60 * 60 * 1000L),
        actionLogDao.countSince(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L),
        targetingEngine.getWeights(),
        personaLayer.currentPersona
    ) { enabled, today, week, weights, persona ->
        DashboardUiState(
            engineEnabled = enabled,
            engineState = poisonEngine.engineState,
            actionsToday = today,
            actionsThisWeek = week,
            categoryDistribution = weights,
            currentPersona = persona,
            estimatedNoiseRatio = computeNoiseRatio(today)
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
}
