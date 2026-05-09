package com.fauxx.locale

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.fauxx.di.PreferenceKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the active app locale and the locale used for synthetic
 * activity (search queries, Accept-Language headers, search-engine URL params, persona
 * regions, crawl URL set).
 *
 * Resolution order:
 *  1. User override (persisted in DataStore as [PreferenceKeys.LANGUAGE_OVERRIDE]).
 *  2. System locale, filtered through [SupportedLocale.fromLocale].
 *  3. Fallback [SupportedLocale.EN].
 *
 * The user-facing UI language and the synthetic-activity language are intentionally
 * coupled: shipping a "Spanish UI but English noise" combination would emit fr-FR /
 * en-US Accept-Language mismatches that data brokers can fingerprint as bot activity.
 * One coherent locale per install is what the spike concluded; see
 * `.devloop/spikes/multilingual-support.md`.
 *
 * Consumers (QueryBankManager, HeaderRandomizerInterceptor, etc.) should subscribe to
 * [currentLocaleFlow] so caches invalidate on locale change. Synchronous reads via
 * [currentLocale] are safe but reflect a snapshot.
 */
@Singleton
class LocaleManager @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Reactive stream of the currently-resolved [SupportedLocale]. Emits whenever the
     * persisted user override changes. Re-evaluates the system-locale fallback on every
     * emission, so a user who changes phone language and then toggles any setting will
     * see the new system locale picked up.
     *
     * For real-time response to system locale changes while the app is foregrounded,
     * the hosting `Activity` is recreated by Android on configuration change; the new
     * `LocaleManager` lookups will see the updated system locale.
     */
    val currentLocaleFlow: StateFlow<SupportedLocale> = dataStore.data
        .map { prefs -> resolve(prefs[PreferenceKeys.LANGUAGE_OVERRIDE]) }
        .distinctUntilChanged()
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = resolveFromSystem()
        )

    /** Snapshot of [currentLocaleFlow]. Safe to call from non-coroutine contexts. */
    val currentLocale: SupportedLocale
        get() = currentLocaleFlow.value

    /**
     * Reactive stream of just the user-override preference (null = follow system).
     * Settings UI binds to this so the picker reflects "System default" vs an
     * explicit choice.
     */
    val userOverrideFlow: Flow<SupportedLocale?> = dataStore.data
        .map { prefs -> prefs[PreferenceKeys.LANGUAGE_OVERRIDE]?.let(SupportedLocale::fromTag) }
        .distinctUntilChanged()

    /**
     * Persist a user choice. Pass null to clear the override and follow system locale.
     *
     * NOTE: This only persists the preference. The caller is responsible for triggering
     * the actual UI re-render via `AppCompatDelegate.setApplicationLocales()` so that
     * resource lookups (`getString(...)`) resolve to the new locale's `values-<tag>/`
     * directory. See [com.fauxx.ui.MainActivity] for the wiring.
     */
    suspend fun setUserOverride(locale: SupportedLocale?) {
        dataStore.edit { prefs ->
            if (locale == null) {
                prefs.remove(PreferenceKeys.LANGUAGE_OVERRIDE)
            } else {
                prefs[PreferenceKeys.LANGUAGE_OVERRIDE] = locale.tag
            }
        }
    }

    private fun resolve(overrideTag: String?): SupportedLocale =
        if (overrideTag.isNullOrBlank()) resolveFromSystem()
        else SupportedLocale.fromTag(overrideTag)

    private fun resolveFromSystem(): SupportedLocale =
        SupportedLocale.fromLocale(Locale.getDefault())
}
