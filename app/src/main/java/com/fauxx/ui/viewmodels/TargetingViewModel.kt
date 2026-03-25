package com.fauxx.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.targeting.TargetingEngine
import com.fauxx.targeting.layer1.DemographicProfileDao
import com.fauxx.targeting.layer2.PlatformProfileDao
import com.fauxx.targeting.layer2.ScrapeScheduler
import com.fauxx.targeting.layer3.PersonaHistoryDao
import com.fauxx.targeting.layer3.PersonaRotationLayer
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

data class TargetingUiState(
    val layer1Enabled: Boolean = false,
    val layer2Enabled: Boolean = false,
    val layer3Enabled: Boolean = false,
    val hasProfile: Boolean = false,
    val lastScrapeDate: String = "Never",
    val currentPersonaName: String? = null,
    val weights: Map<CategoryPool, Float> = emptyMap()
)

private val DATE_FMT = SimpleDateFormat("MMM d, yyyy", Locale.US)

@HiltViewModel
class TargetingViewModel @Inject constructor(
    private val targetingEngine: TargetingEngine,
    private val profileRepo: PoisonProfileRepository,
    private val demographicDao: DemographicProfileDao,
    private val platformDao: PlatformProfileDao,
    private val personaHistoryDao: PersonaHistoryDao,
    private val personaLayer: PersonaRotationLayer,
    private val scrapeScheduler: ScrapeScheduler
) : ViewModel() {

    private val _state = MutableStateFlow(TargetingUiState())
    val uiState: StateFlow<TargetingUiState> = combine(
        _state,
        demographicDao.observe(),
        platformDao.observeAll(),
        personaLayer.currentPersona,
        targetingEngine.getWeights()
    ) { state, profile, platforms, persona, weights ->
        val lastScrape = platforms.maxOfOrNull { it.lastScraped }
        state.copy(
            hasProfile = profile != null,
            lastScrapeDate = lastScrape?.takeIf { it > 0 }?.let { DATE_FMT.format(Date(it)) } ?: "Never",
            currentPersonaName = persona?.name,
            weights = weights
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TargetingUiState())

    init {
        val profile = profileRepo.getProfile()
        _state.value = _state.value.copy(
            layer1Enabled = profile.layer1Enabled,
            layer2Enabled = profile.layer2Enabled,
            layer3Enabled = profile.layer3Enabled
        )
    }

    fun setLayer1Enabled(enabled: Boolean) {
        targetingEngine.setLayer1Enabled(enabled)
        saveLayerPrefs(layer1 = enabled)
        _state.value = _state.value.copy(layer1Enabled = enabled)
    }

    fun setLayer2Enabled(enabled: Boolean) {
        targetingEngine.setLayer2Enabled(enabled)
        saveLayerPrefs(layer2 = enabled)
        _state.value = _state.value.copy(layer2Enabled = enabled)
    }

    fun setLayer3Enabled(enabled: Boolean) {
        targetingEngine.setLayer3Enabled(enabled)
        saveLayerPrefs(layer3 = enabled)
        _state.value = _state.value.copy(layer3Enabled = enabled)
    }

    fun scrapeNow() {
        scrapeScheduler.schedule()
    }

    fun rotatePersona() {
        personaLayer.rotateNow()
    }

    fun clearProfile() {
        viewModelScope.launch {
            demographicDao.delete()
            platformDao.deleteAll()
            personaHistoryDao.deleteAll()
            targetingEngine.setLayer1Enabled(false)
            targetingEngine.setLayer2Enabled(false)
            targetingEngine.setLayer3Enabled(false)
            saveLayerPrefs(layer1 = false, layer2 = false, layer3 = false)
            _state.value = _state.value.copy(layer1Enabled = false, layer2Enabled = false, layer3Enabled = false)
        }
    }

    private fun saveLayerPrefs(
        layer1: Boolean = _state.value.layer1Enabled,
        layer2: Boolean = _state.value.layer2Enabled,
        layer3: Boolean = _state.value.layer3Enabled
    ) {
        viewModelScope.launch {
            val profile = profileRepo.getProfile()
            profileRepo.saveProfile(
                profile.copy(
                    layer1Enabled = layer1,
                    layer2Enabled = layer2,
                    layer3Enabled = layer3
                )
            )
        }
    }
}
