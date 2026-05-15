package com.fauxx.ui.viewmodels

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fauxx.BuildConfig
import com.fauxx.data.db.ActionLogDao
import com.fauxx.data.model.IntensityLevel
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
import com.fauxx.logging.EncryptedFileTree
import com.fauxx.logging.LogScrubber
import com.fauxx.targeting.TargetingEngine
import com.fauxx.targeting.layer1.DemographicProfileDao
import com.fauxx.targeting.layer2.PlatformProfileDao
import com.fauxx.targeting.layer3.PersonaHistoryDao
import com.fauxx.ui.theme.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val intensity: IntensityLevel = IntensityLevel.MEDIUM,
    val wifiOnly: Boolean = true,
    val batteryThreshold: Int = 20,
    val ignoreBatteryThresholdWhileCharging: Boolean = false,
    val allowedHoursStart: Int = 7,
    val allowedHoursEnd: Int = 23,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val resumeOnBoot: Boolean = true
)

/**
 * UI state for the app-language picker. `userOverride == null` means "follow system locale";
 * any other value is an explicit user selection. `shippedLocales` enumerates locales that
 * are actually selectable; the picker greys out unshipped entries.
 */
data class LanguageUiState(
    val userOverride: SupportedLocale? = null,
    val shippedLocales: Set<SupportedLocale> = emptySet()
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
    private val targetingEngine: TargetingEngine,
    private val encryptedFileTree: EncryptedFileTree,
    private val localeManager: LocaleManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(loadFromProfile())
    val uiState: StateFlow<SettingsUiState> = _uiState

    private val shippedLocales: Set<SupportedLocale> = BuildConfig.SHIPPED_LOCALES
        .mapNotNull { tag -> runCatching { SupportedLocale.fromTag(tag) }.getOrNull() }
        .toSet()
        .ifEmpty { setOf(SupportedLocale.EN) }

    val languageState: StateFlow<LanguageUiState> = localeManager.userOverrideFlow
        .map { override -> LanguageUiState(userOverride = override, shippedLocales = shippedLocales) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = LanguageUiState(
                userOverride = null,
                shippedLocales = shippedLocales
            )
        )

    fun setIntensity(level: IntensityLevel) { update { it.copy(intensity = level) } }
    fun setWifiOnly(v: Boolean) { update { it.copy(wifiOnly = v) } }
    fun setBatteryThreshold(v: Int) { update { it.copy(batteryThreshold = v) } }
    fun setIgnoreBatteryThresholdWhileCharging(v: Boolean) {
        update { it.copy(ignoreBatteryThresholdWhileCharging = v) }
    }
    fun setAllowedHoursStart(v: Int) { update { it.copy(allowedHoursStart = v) } }
    fun setAllowedHoursEnd(v: Int) { update { it.copy(allowedHoursEnd = v) } }
    fun setThemeMode(mode: ThemeMode) { update { it.copy(themeMode = mode) } }
    fun setResumeOnBoot(v: Boolean) { update { it.copy(resumeOnBoot = v) } }

    /**
     * Persist the user's app-language choice and trigger the activity recreate that
     * re-resolves resource strings. Pass null to clear the override and follow the
     * system locale. Selecting a non-shipped locale is a no-op.
     */
    fun setLanguage(locale: SupportedLocale?) {
        if (locale != null && locale !in shippedLocales) return
        viewModelScope.launch { localeManager.setUserOverride(locale) }
        val appCompatLocales = if (locale == null) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(locale.tag)
        }
        AppCompatDelegate.setApplicationLocales(appCompatLocales)
    }

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

    /**
     * Returns scrubbed debug log content suitable for sharing.
     * Flushes pending log lines first, then reads and scrubs all stored logs.
     */
    fun getScrubbedLogs(): String {
        encryptedFileTree.flush()
        val raw = encryptedFileTree.readAllLogs()
        return LogScrubber.scrub(raw)
    }

    private fun update(transform: (SettingsUiState) -> SettingsUiState) {
        val new = transform(_uiState.value)
        _uiState.value = new
        viewModelScope.launch {
            profileRepo.updateProfile { current ->
                current.copy(
                    intensity = new.intensity,
                    wifiOnly = new.wifiOnly,
                    batteryThreshold = new.batteryThreshold,
                    ignoreBatteryThresholdWhileCharging = new.ignoreBatteryThresholdWhileCharging,
                    allowedHoursStart = new.allowedHoursStart,
                    allowedHoursEnd = new.allowedHoursEnd,
                    themeMode = new.themeMode,
                    resumeOnBoot = new.resumeOnBoot
                )
            }
        }
    }

    private fun loadFromProfile(): SettingsUiState {
        val p = profileRepo.getProfile()
        return SettingsUiState(
            intensity = p.intensity,
            wifiOnly = p.wifiOnly,
            batteryThreshold = p.batteryThreshold,
            ignoreBatteryThresholdWhileCharging = p.ignoreBatteryThresholdWhileCharging,
            allowedHoursStart = p.allowedHoursStart,
            allowedHoursEnd = p.allowedHoursEnd,
            themeMode = p.themeMode,
            resumeOnBoot = p.resumeOnBoot
        )
    }
}
