package com.fauxx.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fauxx.data.db.ActionLogDao
import com.fauxx.data.model.IntensityLevel
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.targeting.TargetingEngine
import com.fauxx.targeting.layer1.DemographicProfileDao
import com.fauxx.targeting.layer2.PlatformProfileDao
import com.fauxx.targeting.layer3.PersonaHistoryDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val intensity: IntensityLevel = IntensityLevel.MEDIUM,
    val wifiOnly: Boolean = true,
    val batteryThreshold: Int = 20,
    val allowedHoursStart: Int = 7,
    val allowedHoursEnd: Int = 23
)

/**
 * ViewModel for the Settings screen. Manages global engine configuration and
 * user-initiated data deletion (privacy control).
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val profileRepo: PoisonProfileRepository,
    private val actionLogDao: ActionLogDao,
    private val demographicDao: DemographicProfileDao,
    private val platformDao: PlatformProfileDao,
    private val personaHistoryDao: PersonaHistoryDao,
    private val targetingEngine: TargetingEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(loadFromProfile())
    val uiState: StateFlow<SettingsUiState> = _uiState

    fun setIntensity(level: IntensityLevel) { update { it.copy(intensity = level) } }
    fun setWifiOnly(v: Boolean) { update { it.copy(wifiOnly = v) } }
    fun setBatteryThreshold(v: Int) { update { it.copy(batteryThreshold = v) } }

    /** Delete all locally-stored data and reset settings to defaults. */
    fun resetToDefaults() {
        viewModelScope.launch {
            actionLogDao.deleteOlderThan(Long.MAX_VALUE)
            demographicDao.delete()
            platformDao.deleteAll()
            personaHistoryDao.deleteAll()
            targetingEngine.setLayer1Enabled(false)
            targetingEngine.setLayer2Enabled(false)
            targetingEngine.setLayer3Enabled(false)
            profileRepo.saveProfile(com.fauxx.data.model.PoisonProfile())
            _uiState.value = SettingsUiState()
        }
    }

    private fun update(transform: (SettingsUiState) -> SettingsUiState) {
        val new = transform(_uiState.value)
        _uiState.value = new
        profileRepo.saveProfile(profileRepo.getProfile().copy(
            intensity = new.intensity,
            wifiOnly = new.wifiOnly,
            batteryThreshold = new.batteryThreshold,
            allowedHoursStart = new.allowedHoursStart,
            allowedHoursEnd = new.allowedHoursEnd
        ))
    }

    private fun loadFromProfile(): SettingsUiState {
        val p = profileRepo.getProfile()
        return SettingsUiState(
            intensity = p.intensity,
            wifiOnly = p.wifiOnly,
            batteryThreshold = p.batteryThreshold,
            allowedHoursStart = p.allowedHoursStart,
            allowedHoursEnd = p.allowedHoursEnd
        )
    }
}
