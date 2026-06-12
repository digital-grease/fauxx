package com.fauxx.ui.viewmodels

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fauxx.data.db.ActionLogDao
import com.fauxx.data.model.IntensityLevel
import com.fauxx.data.model.PoisonProfile
import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.di.PreferenceKeys
import com.fauxx.engine.EngineState
import com.fauxx.engine.PoisonEngine
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.service.PhantomForegroundService
import com.fauxx.targeting.TargetingEngine
import com.fauxx.targeting.layer2.DriftResult
import com.fauxx.targeting.layer2.DriftState
import com.fauxx.targeting.layer2.ProfileDriftMetric
import com.fauxx.targeting.layer2.ProfileSnapshotDao
import com.fauxx.targeting.layer2.SnapshotSeries
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
    val syntheticActionsPerHour: Float = 0f,
    val healthWarnings: List<String> = emptyList(),
    /** Profile-drift (issue #171 E2): KL divergence of the latest import from the baseline. */
    val driftState: DriftState = DriftState.COLLECTING,
    val driftKlDivergence: Double? = null,
    /** Poisoned-vs-control divergence (issue #172 E3): KL of the latest poisoned profile vs the
     *  latest control profile. [hasControlSeries] gates the dashboard card so it appears only once
     *  a control profile has been imported. */
    val controlDivergenceState: DriftState = DriftState.COLLECTING,
    val controlDivergenceKl: Double? = null,
    val hasControlSeries: Boolean = false,
    /** Mobile-data tier (issue #62); gates the "use mobile data" one-tap action — the
     *  button only helps when this is null (mobile off), not when there's no network. */
    val mobileIntensity: IntensityLevel? = null
)

/** Bundles the two snapshot-derived dashboard metrics so combine() keeps a single snapshot flow. */
private data class DriftBundle(
    val drift: DriftResult,
    val control: DriftResult,
    val hasControl: Boolean,
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
    private val profileSnapshotDao: ProfileSnapshotDao,
    private val profileDriftMetric: ProfileDriftMetric,
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
        poisonEngine.engineState,
        profileRepo.profiles,
        profileSnapshotDao.observeAll().map { snaps ->
            DriftBundle(
                // E2 profile-drift is the poisoned series only; control snapshots must not skew it.
                drift = profileDriftMetric.compute(snaps.filter { it.series == SnapshotSeries.POISONED }),
                control = profileDriftMetric.computeControlDivergence(snaps),
                hasControl = snaps.any { it.series == SnapshotSeries.CONTROL },
            )
        }
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
        val profile = flows[7] as PoisonProfile
        val drift = flows[8] as DriftBundle
        DashboardUiState(
            engineEnabled = enabled,
            engineState = state,
            actionsToday = today,
            actionsThisWeek = week,
            categoryDistribution = weights,
            currentPersona = persona,
            syntheticActionsPerHour = computeSyntheticActionRate(today),
            healthWarnings = warnings,
            driftState = drift.drift.state,
            driftKlDivergence = drift.drift.klDivergence,
            controlDivergenceState = drift.control.state,
            controlDivergenceKl = drift.control.klDivergence,
            hasControlSeries = drift.hasControl,
            mobileIntensity = profile.mobileIntensity
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
     * One-tap opt-in to mobile data, exposed on the Dashboard for a user who sees the
     * engine paused waiting for a network — the Settings control is the same action, but
     * discoverability there is poor (see issue #38). Sets the mobile tier to LOW (issue
     * #62 replaced the old wifi-only toggle with a per-network rate): conservative on
     * data, and adjustable upward in Settings.
     */
    fun enableMobileData() {
        viewModelScope.launch {
            // Never clobber an already-configured tier: the button is only SHOWN when the
            // tier is null, but a stale tap (state changed under the user's finger) must
            // not downgrade a deliberate HIGH/EXTREME mobile choice to LOW.
            profileRepo.updateProfile { it.copy(mobileIntensity = it.mobileIntensity ?: IntensityLevel.LOW) }
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

    private fun computeSyntheticActionRate(actionsToday: Int): Float {
        // Average synthetic actions per hour over the last 24h: an honest measure of how much
        // cover traffic Fauxx adds. NOT a ratio against the user's real traffic, which Android
        // exposes no per-app provenance for (issue #160).
        return actionsToday / 24f
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
