package com.fauxx.locale

import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.fauxx.di.PreferenceKeys
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val context: Context,
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
     * Persist a user choice AND apply it to the system per-app-language store so the UI
     * recreates with the new locale's resources. Pass null to clear the override and
     * follow the system locale.
     *
     * Application path differs by API:
     *  - **API 33+ (Tiramisu)**: directly via [android.app.LocaleManager.setApplicationLocales].
     *    Triggers Activity recreate via the OS per-app-language API.
     *  - **API 26-32**: falls back to [AppCompatDelegate.setApplicationLocales]. **Caveat**:
     *    that path is only reliable when the hosting activity is an `AppCompatActivity`;
     *    [com.fauxx.ui.MainActivity] is a `ComponentActivity`, so the recreate may not
     *    fire on older devices. Pre-Tiramisu users may need to relaunch the app after a
     *    language change for the UI to refresh — a tradeoff worth revisiting if support
     *    requests come in from API 32-and-earlier users.
     */
    suspend fun setUserOverride(locale: SupportedLocale?) {
        dataStore.edit { prefs ->
            if (locale == null) {
                prefs.remove(PreferenceKeys.LANGUAGE_OVERRIDE)
            } else {
                prefs[PreferenceKeys.LANGUAGE_OVERRIDE] = locale.tag
            }
        }
        applyToSystem(locale)
    }

    private fun applyToSystem(locale: SupportedLocale?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val sysLocaleManager = context.getSystemService(android.app.LocaleManager::class.java)
            sysLocaleManager?.applicationLocales = if (locale == null) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(locale.tag)
            }
        } else {
            val appCompatLocales = if (locale == null) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(locale.tag)
            }
            AppCompatDelegate.setApplicationLocales(appCompatLocales)
        }
    }

    private fun resolve(overrideTag: String?): SupportedLocale =
        if (overrideTag.isNullOrBlank()) resolveFromSystem()
        else SupportedLocale.fromTag(overrideTag)

    private fun resolveFromSystem(): SupportedLocale =
        SupportedLocale.fromLocale(Locale.getDefault())
}
