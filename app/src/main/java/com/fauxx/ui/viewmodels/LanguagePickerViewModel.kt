package com.fauxx.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fauxx.BuildConfig
import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Lightweight ViewModel backing the quick-access language picker in the app's top bar.
 *
 * Pulls only [LocaleManager] — does not depend on [SettingsViewModel]'s repos/DAOs —
 * so opening any screen doesn't transitively instantiate the full Settings graph just
 * to power the picker. The Settings screen's own picker keeps its own state; both
 * derive from the same [LocaleManager.userOverrideFlow] so they stay in sync.
 *
 * Selecting an unshipped locale is a no-op (matches the gating behavior the Settings
 * picker enforces).
 */
@HiltViewModel
class LanguagePickerViewModel @Inject constructor(
    private val localeManager: LocaleManager
) : ViewModel() {

    private val shippedLocales: Set<SupportedLocale> = BuildConfig.SHIPPED_LOCALES
        .mapNotNull { tag -> runCatching { SupportedLocale.fromTag(tag) }.getOrNull() }
        .toSet()
        .ifEmpty { setOf(SupportedLocale.EN) }

    val state: StateFlow<LanguageUiState> = localeManager.userOverrideFlow
        .map { override -> LanguageUiState(userOverride = override, shippedLocales = shippedLocales) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = LanguageUiState(userOverride = null, shippedLocales = shippedLocales)
        )

    /**
     * Persist the override; [LocaleManager.setUserOverride] also applies it to the
     * system per-app-language store, which triggers the activity recreate that
     * re-resolves string resources. Pass null to clear the override and follow the
     * system locale.
     */
    fun setLanguage(locale: SupportedLocale?) {
        if (locale != null && locale !in shippedLocales) return
        viewModelScope.launch { localeManager.setUserOverride(locale) }
    }
}
