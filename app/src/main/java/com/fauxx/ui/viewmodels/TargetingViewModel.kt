package com.fauxx.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.targeting.TargetingEngine
import com.fauxx.targeting.layer1.CustomInterestMapper
import com.fauxx.targeting.layer1.DemographicProfileDao
import com.fauxx.targeting.layer1.InterestMapping
import com.fauxx.targeting.layer1.UserDemographicProfile
import com.fauxx.targeting.layer2.PlatformProfileDao
import com.fauxx.targeting.layer2.ScrapeScheduler
import com.fauxx.targeting.layer2.ScrapeWorker
import com.fauxx.targeting.layer3.PersonaHistoryDao
import com.fauxx.targeting.layer3.PersonaRotationLayer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

/**
 * Lifecycle state of an on-demand "Scrape Now" run.
 *
 * [NEEDS_LOGIN] is the special "no platform returned any categories" outcome — the
 * scraper reads existing ad-platform cookies, so an all-empty result almost always
 * means the user isn't signed into Google Ads Settings or Facebook ad preferences.
 * The UI surfaces a dialog explaining this rather than letting the failure flash by
 * as a generic "Failed".
 */
enum class ScrapeState { IDLE, RUNNING, SUCCESS, FAILED, NEEDS_LOGIN }

data class TargetingUiState(
    val layer1Enabled: Boolean = false,
    val layer2Enabled: Boolean = false,
    val layer3Enabled: Boolean = false,
    val hasProfile: Boolean = false,
    val lastScrapeDate: String = "Never",
    val currentPersonaName: String? = null,
    val weights: Map<CategoryPool, Float> = emptyMap(),
    val customInterestMappings: List<InterestMapping> = emptyList(),
    val scrapeState: ScrapeState = ScrapeState.IDLE
)

private const val SCRAPE_RESULT_DISPLAY_MS = 3_000L

private val DATE_FMT = SimpleDateFormat("MMM d, yyyy", Locale.US)

@HiltViewModel
class TargetingViewModel @Inject constructor(
    private val targetingEngine: TargetingEngine,
    private val profileRepo: PoisonProfileRepository,
    private val demographicDao: DemographicProfileDao,
    private val customInterestMapper: CustomInterestMapper,
    private val platformDao: PlatformProfileDao,
    private val personaHistoryDao: PersonaHistoryDao,
    private val personaLayer: PersonaRotationLayer,
    private val scrapeScheduler: ScrapeScheduler,
    @ApplicationContext private val context: Context
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
        val customInterests = profile?.getCustomInterests().orEmpty()
        state.copy(
            hasProfile = profile != null,
            lastScrapeDate = lastScrape?.takeIf { it > 0 }?.let { DATE_FMT.format(Date(it)) } ?: "Never",
            currentPersonaName = persona?.name,
            weights = weights,
            customInterestMappings = if (customInterests.isNotEmpty())
                customInterestMapper.mapAll(customInterests)
            else emptyList()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TargetingUiState())

    private var scrapeObserverJob: Job? = null

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
        if (enabled) {
            scrapeScheduler.schedule()
        } else {
            scrapeScheduler.cancel()
        }
    }

    fun setLayer3Enabled(enabled: Boolean) {
        targetingEngine.setLayer3Enabled(enabled)
        saveLayerPrefs(layer3 = enabled)
        _state.value = _state.value.copy(layer3Enabled = enabled)
    }

    fun scrapeNow() {
        // Don't allow re-enqueue while one is in flight.
        if (_state.value.scrapeState == ScrapeState.RUNNING) return

        scrapeObserverJob?.cancel()
        val id = scrapeScheduler.scrapeNow()
        _state.value = _state.value.copy(scrapeState = ScrapeState.RUNNING)

        scrapeObserverJob = viewModelScope.launch {
            WorkManager.getInstance(context)
                .getWorkInfoByIdFlow(id)
                .collect { info ->
                    when (info?.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            _state.value = _state.value.copy(scrapeState = ScrapeState.SUCCESS)
                            delay(SCRAPE_RESULT_DISPLAY_MS)
                            _state.value = _state.value.copy(scrapeState = ScrapeState.IDLE)
                        }
                        WorkInfo.State.FAILED,
                        WorkInfo.State.CANCELLED -> {
                            // Worker sets KEY_OUTCOME on the result — distinguish the
                            // common "user isn't signed in" case from real errors so the
                            // UI can show a helpful dialog instead of a generic "Failed".
                            val outcome = info.outputData.getString(ScrapeWorker.KEY_OUTCOME)
                            if (outcome == ScrapeWorker.OUTCOME_NEEDS_LOGIN) {
                                // Don't auto-reset — the user has to act (sign in, then re-tap).
                                _state.value = _state.value.copy(scrapeState = ScrapeState.NEEDS_LOGIN)
                            } else {
                                _state.value = _state.value.copy(scrapeState = ScrapeState.FAILED)
                                delay(SCRAPE_RESULT_DISPLAY_MS)
                                _state.value = _state.value.copy(scrapeState = ScrapeState.IDLE)
                            }
                        }
                        WorkInfo.State.RUNNING,
                        WorkInfo.State.ENQUEUED,
                        WorkInfo.State.BLOCKED -> {
                            _state.value = _state.value.copy(scrapeState = ScrapeState.RUNNING)
                        }
                        null -> { /* work info not yet available */ }
                    }
                }
        }
    }

    /**
     * Called by the screen when the user dismisses the "needs login" dialog. Resets
     * scrape state to IDLE so the button is tappable again (the user has presumably
     * signed in before tapping again).
     */
    fun dismissScrapeNeedsLogin() {
        if (_state.value.scrapeState == ScrapeState.NEEDS_LOGIN) {
            _state.value = _state.value.copy(scrapeState = ScrapeState.IDLE)
        }
    }

    fun rotatePersona() {
        personaLayer.rotateNow()
    }

    fun addCustomInterest(interest: String) {
        val trimmed = interest.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val profile = demographicDao.get() ?: UserDemographicProfile()
            val current = profile.getCustomInterests().toMutableList()
            if (current.any { it.equals(trimmed, ignoreCase = true) }) return@launch
            current.add(trimmed)
            demographicDao.upsert(
                profile.copy(
                    customInterestsJson = UserDemographicProfile.serializeCustomInterests(current)
                )
            )
        }
    }

    fun removeCustomInterest(index: Int) {
        viewModelScope.launch {
            val profile = demographicDao.get() ?: return@launch
            val current = profile.getCustomInterests().toMutableList()
            if (index !in current.indices) return@launch
            current.removeAt(index)
            demographicDao.upsert(
                profile.copy(
                    customInterestsJson = if (current.isNotEmpty())
                        UserDemographicProfile.serializeCustomInterests(current)
                    else null
                )
            )
        }
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
