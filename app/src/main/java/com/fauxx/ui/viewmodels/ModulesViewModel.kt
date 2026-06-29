package com.fauxx.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.modules.LocationDiagnostics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ModulesUiState(
    val searchEnabled: Boolean = true,
    val cookieEnabled: Boolean = true,
    val dnsEnabled: Boolean = true,
    val fingerprintEnabled: Boolean = true,
    val adEnabled: Boolean = true,
    val locationEnabled: Boolean = false,
    val appSignalEnabled: Boolean = false
)

@HiltViewModel
class ModulesViewModel @Inject constructor(
    private val profileRepo: PoisonProfileRepository,
    private val locationDiagnostics: LocationDiagnostics
) : ViewModel() {

    private val _uiState = MutableStateFlow(loadFromProfile())
    val uiState: StateFlow<ModulesUiState> = _uiState

    val locationStartFailure: StateFlow<LocationDiagnostics.StartFailure> =
        locationDiagnostics.lastStartFailure

    /**
     * True if Fauxx is already designated as the system mock-location provider.
     * Lets the screen skip the setup-hint dialog when the user has already done
     * the Developer Options dance.
     */
    fun isLocationReadyForUse(): Boolean = locationDiagnostics.isMockLocationAppOpAllowed()

    fun setSearchEnabled(v: Boolean) { update { it.copy(searchEnabled = v) } }
    fun setCookieEnabled(v: Boolean) { update { it.copy(cookieEnabled = v) } }
    fun setDnsEnabled(v: Boolean) { update { it.copy(dnsEnabled = v) } }
    fun setFingerprintEnabled(v: Boolean) { update { it.copy(fingerprintEnabled = v) } }
    fun setAdEnabled(v: Boolean) { update { it.copy(adEnabled = v) } }
    fun setLocationEnabled(v: Boolean) {
        update { it.copy(locationEnabled = v) }
        // Engine.start() only invokes module.start() during its own startup loop, so
        // toggling this flag from the UI wouldn't otherwise refresh lastStartFailure
        // until the next engine restart. Kick the module directly so the UI's
        // green-or-red banner reflects the choice immediately.
        if (v) {
            viewModelScope.launch { locationDiagnostics.requestStart() }
        }
    }
    fun setAppSignalEnabled(v: Boolean) { update { it.copy(appSignalEnabled = v) } }

    /**
     * Re-evaluate the mock-location AppOp gate (issue #202). The capability is only checked inside
     * [LocationDiagnostics.requestStart] (LocationSpoofModule.start), which runs at engine start
     * and on toggle-ON. So designating Fauxx as the "Select mock location app" in Developer Options
     * while Location is ALREADY enabled is not detected live — the red NOT_MOCK_APP banner stays
     * stale until the engine is restarted. The screen calls this on ON_RESUME, so returning from
     * the Developer Options deep-link refreshes the banner. Re-runs only when Location is enabled
     * AND the last attempt is in a failure state, to avoid re-kicking start() (and any in-flight
     * spoof route) on every routine return to the screen.
     */
    fun refreshLocationStatus() {
        if (_uiState.value.locationEnabled &&
            locationDiagnostics.lastStartFailure.value != LocationDiagnostics.StartFailure.OK
        ) {
            viewModelScope.launch { locationDiagnostics.requestStart() }
        }
    }

    private fun update(transform: (ModulesUiState) -> ModulesUiState) {
        val new = transform(_uiState.value)
        _uiState.value = new
        saveToProfile(new)
    }

    private fun loadFromProfile(): ModulesUiState {
        val p = profileRepo.getProfile()
        return ModulesUiState(
            searchEnabled = p.searchPoisonEnabled,
            cookieEnabled = p.cookieSaturationEnabled,
            dnsEnabled = p.dnsNoiseEnabled,
            fingerprintEnabled = p.fingerprintEnabled,
            adEnabled = p.adPollutionEnabled,
            locationEnabled = p.locationSpoofEnabled,
            appSignalEnabled = p.appSignalEnabled
        )
    }

    private fun saveToProfile(state: ModulesUiState) {
        viewModelScope.launch {
            profileRepo.saveProfile(profileRepo.getProfile().copy(
                searchPoisonEnabled = state.searchEnabled,
                cookieSaturationEnabled = state.cookieEnabled,
                dnsNoiseEnabled = state.dnsEnabled,
                fingerprintEnabled = state.fingerprintEnabled,
                adPollutionEnabled = state.adEnabled,
                locationSpoofEnabled = state.locationEnabled,
                appSignalEnabled = state.appSignalEnabled
            ))
        }
    }
}
