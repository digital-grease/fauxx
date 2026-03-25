package com.fauxx.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fauxx.engine.PoisonProfileRepository
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
    private val profileRepo: PoisonProfileRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(loadFromProfile())
    val uiState: StateFlow<ModulesUiState> = _uiState

    fun setSearchEnabled(v: Boolean) { update { it.copy(searchEnabled = v) } }
    fun setCookieEnabled(v: Boolean) { update { it.copy(cookieEnabled = v) } }
    fun setDnsEnabled(v: Boolean) { update { it.copy(dnsEnabled = v) } }
    fun setFingerprintEnabled(v: Boolean) { update { it.copy(fingerprintEnabled = v) } }
    fun setAdEnabled(v: Boolean) { update { it.copy(adEnabled = v) } }
    fun setLocationEnabled(v: Boolean) { update { it.copy(locationEnabled = v) } }
    fun setAppSignalEnabled(v: Boolean) { update { it.copy(appSignalEnabled = v) } }

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
