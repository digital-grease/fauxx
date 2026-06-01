package com.fauxx.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fauxx.R
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.di.PreferenceKeys
import com.fauxx.di.fauxxDataStore
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.targeting.TargetingEngine
import com.fauxx.targeting.layer1.AgeRange
import com.fauxx.targeting.layer1.CustomInterestMapper
import com.fauxx.targeting.layer1.DemographicProfileDao
import com.fauxx.targeting.layer1.Gender
import com.fauxx.targeting.layer1.InterestMapping
import com.fauxx.targeting.layer1.Profession
import com.fauxx.targeting.layer1.Region
import com.fauxx.targeting.layer1.UserDemographicProfile
import com.fauxx.targeting.layer2.PlatformProfileDao
import com.fauxx.targeting.layer2.importers.AdProfileImporter
import com.fauxx.targeting.layer2.importers.FacebookDyiImporter
import com.fauxx.targeting.layer2.importers.GoogleTakeoutImporter
import com.fauxx.targeting.layer2.importers.ImportResult
import com.fauxx.targeting.layer2.importers.ImportSource
import com.fauxx.targeting.layer3.PersonaHistoryDao
import com.fauxx.targeting.layer3.PersonaRotationLayer
import com.fauxx.util.Clock
import com.fauxx.util.SystemClockImpl
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class TargetingUiState(
    val layer1Enabled: Boolean = false,
    val layer2Enabled: Boolean = false,
    val layer3Enabled: Boolean = false,
    val hasProfile: Boolean = false,
    val ageRange: AgeRange? = null,
    val gender: Gender? = null,
    val profession: Profession? = null,
    val region: Region? = null,
    val interests: Set<CategoryPool> = emptySet(),
    val lastImportedDate: String = "Never",
    val currentPersonaName: String? = null,
    val weights: Map<CategoryPool, Float> = emptyMap(),
    val customInterestMappings: List<InterestMapping> = emptyList(),
    // Layer 2 import (issue #52). [importInProgress] is the source currently mid-import
    // (drives button "Importing…" state); null when idle. [lastImportResult] is the
    // most recent outcome shown to the user — auto-clears after a short display window.
    val importInProgress: ImportSource? = null,
    val lastImportResult: ImportResult? = null,
    // [showImportReminder] fires if the most recent import is > 90 days old and the
    // user hasn't snoozed/muted it. Derived in the combine() block from cache state +
    // the mute-until pref mirrored via [importReminderMutedUntil].
    val showImportReminder: Boolean = false,
    // Internal: mirrored from DataStore by an init-launched collector so the combine()
    // block has it available without exceeding the 5-flow overload limit.
    val importReminderMutedUntil: Long = 0L
)

private const val IMPORT_RESULT_DISPLAY_MS = 4_000L
private const val NINETY_DAYS_MS = 90L * 24 * 60 * 60 * 1000
private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000

// DateFormat is reconstructed per call so it picks up the current locale every time —
// `SimpleDateFormat("...", Locale.US)` previously rendered "May 22, 2026" under any locale,
// breaking the translated UI. `DateFormat.getDateInstance(MEDIUM, Locale.getDefault())`
// emits the locale-conventional medium-length date (22 may 2026 in ES, 22 мая 2026 in RU).

@HiltViewModel
class TargetingViewModel @Inject constructor(
    private val targetingEngine: TargetingEngine,
    private val profileRepo: PoisonProfileRepository,
    private val demographicDao: DemographicProfileDao,
    private val customInterestMapper: CustomInterestMapper,
    private val platformDao: PlatformProfileDao,
    private val personaHistoryDao: PersonaHistoryDao,
    private val personaLayer: PersonaRotationLayer,
    private val googleTakeoutImporter: GoogleTakeoutImporter,
    private val facebookDyiImporter: FacebookDyiImporter,
    @ApplicationContext private val context: Context,
    private val clock: Clock = SystemClockImpl(),
) : ViewModel() {

    private val _state = MutableStateFlow(TargetingUiState())
    val uiState: StateFlow<TargetingUiState> = combine(
        _state,
        demographicDao.observe(),
        platformDao.observeAll(),
        personaLayer.currentPersona,
        targetingEngine.getWeights()
    ) { state, profile, platforms, persona, weights ->
        val lastImportedAt = platforms.maxOfOrNull { it.lastScraped }
        val customInterests = profile?.getCustomInterests().orEmpty()
        // 90-day reminder visibility derived here so the UI doesn't recompute the date math.
        val now = clock.currentTimeMillis()
        val showReminder = lastImportedAt != null &&
            lastImportedAt > 0L &&
            now - lastImportedAt > NINETY_DAYS_MS &&
            now > state.importReminderMutedUntil
        state.copy(
            hasProfile = profile != null,
            ageRange = profile?.ageRange,
            gender = profile?.gender,
            profession = profile?.profession,
            region = profile?.region,
            interests = profile?.getInterests().orEmpty(),
            lastImportedDate = lastImportedAt?.takeIf { it > 0 }?.let {
                java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, Locale.getDefault()).format(Date(it))
            } ?: context.getString(R.string.targeting_import_never_label),
            currentPersonaName = persona?.name,
            weights = weights,
            customInterestMappings = if (customInterests.isNotEmpty())
                customInterestMapper.mapAll(customInterests)
            else emptyList(),
            showImportReminder = showReminder
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TargetingUiState())

    init {
        val profile = profileRepo.getProfile()
        _state.value = _state.value.copy(
            layer1Enabled = profile.layer1Enabled,
            layer2Enabled = profile.layer2Enabled,
            layer3Enabled = profile.layer3Enabled
        )
        // Bridge the 90-day-reminder mute-until pref into _state. Kept off the main
        // combine() because combine() taps out at 5 typed flows — a separate collector
        // preserves reactivity without forcing the nested-combine plumbing.
        viewModelScope.launch {
            context.fauxxDataStore.data.collect { prefs ->
                _state.value = _state.value.copy(
                    importReminderMutedUntil = prefs[PreferenceKeys.IMPORT_REMINDER_MUTED_UNTIL] ?: 0L
                )
            }
        }
    }

    /**
     * Begin a Google Takeout import for [uri] (delivered by the SAF picker on the
     * Targeting screen). No-op if any import is already in flight to prevent the user
     * from double-tapping the picker callback.
     */
    fun importGoogleTakeout(uri: Uri) = runImport(uri, googleTakeoutImporter)

    /** Begin a Facebook DYI import. See [importGoogleTakeout] for behavior. */
    fun importFacebookDyi(uri: Uri) = runImport(uri, facebookDyiImporter)

    private fun runImport(uri: Uri, importer: AdProfileImporter) {
        if (_state.value.importInProgress != null) return
        _state.value = _state.value.copy(
            importInProgress = importer.source,
            lastImportResult = null
        )
        viewModelScope.launch {
            val result = importer.import(uri)
            _state.value = _state.value.copy(
                importInProgress = null,
                lastImportResult = result
            )
            // A successful import is the user actively engaging — clear any prior snooze
            // so the next 90-day cycle counts from this fresh import. Avoid editing prefs
            // when the result is an error to keep the snoozed state stable on retries.
            if (result is ImportResult.Success) {
                context.fauxxDataStore.edit { prefs ->
                    prefs[PreferenceKeys.IMPORT_REMINDER_MUTED_UNTIL] = 0L
                }
            }
            // Auto-clear the result banner after a display window. Re-check the captured
            // result identity so a fast second import doesn't have its result wiped.
            delay(IMPORT_RESULT_DISPLAY_MS)
            if (_state.value.lastImportResult === result) {
                _state.value = _state.value.copy(lastImportResult = null)
            }
        }
    }

    /** Tap on the result banner — clears it without waiting for the auto-dismiss timer. */
    fun dismissImportResult() {
        _state.value = _state.value.copy(lastImportResult = null)
    }

    /** Hide the 90-day-old-import reminder for 30 days. */
    fun snoozeImportReminder() {
        viewModelScope.launch {
            context.fauxxDataStore.edit { prefs ->
                prefs[PreferenceKeys.IMPORT_REMINDER_MUTED_UNTIL] =
                    clock.currentTimeMillis() + THIRTY_DAYS_MS
            }
        }
    }

    /** Hide the 90-day-old-import reminder permanently (user can re-enable by re-importing). */
    fun muteImportReminderPermanently() {
        viewModelScope.launch {
            context.fauxxDataStore.edit { prefs ->
                prefs[PreferenceKeys.IMPORT_REMINDER_MUTED_UNTIL] = Long.MAX_VALUE
            }
        }
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
