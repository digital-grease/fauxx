package com.fauxx.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Top-level extension property — creates a single DataStore instance for the app. */
val Context.fauxxDataStore: DataStore<Preferences> by preferencesDataStore(name = "fauxx_prefs")

/**
 * Preference keys matching all fields previously stored in EncryptedSharedPreferences.
 */
object PreferenceKeys {
    // Engine state
    val ENABLED = booleanPreferencesKey("enabled")
    val INTENSITY = stringPreferencesKey("intensity")
    val WIFI_ONLY = booleanPreferencesKey("wifi_only")
    val BATTERY_THRESHOLD = intPreferencesKey("battery_threshold")
    val IGNORE_BATTERY_THRESHOLD_WHILE_CHARGING = booleanPreferencesKey("ignore_battery_threshold_while_charging")
    val ALLOWED_HOURS_START = intPreferencesKey("allowed_hours_start")
    val ALLOWED_HOURS_END = intPreferencesKey("allowed_hours_end")

    // Module toggles
    val MODULE_SEARCH = booleanPreferencesKey("module_search")
    val MODULE_AD = booleanPreferencesKey("module_ad")
    val MODULE_LOCATION = booleanPreferencesKey("module_location")
    val MODULE_FINGERPRINT = booleanPreferencesKey("module_fingerprint")
    val MODULE_COOKIE = booleanPreferencesKey("module_cookie")
    val MODULE_APPSIGNAL = booleanPreferencesKey("module_appsignal")
    val MODULE_DNS = booleanPreferencesKey("module_dns")

    // Layer toggles
    val LAYER1_ENABLED = booleanPreferencesKey("layer1_enabled")
    val LAYER2_ENABLED = booleanPreferencesKey("layer2_enabled")
    val LAYER3_ENABLED = booleanPreferencesKey("layer3_enabled")

    // Onboarding
    val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")

    // User consent for background activity
    val CONSENT_ACCEPTED = booleanPreferencesKey("consent_accepted")

    // Play Store flavor: user dismissed the "get full version" notice
    val FULL_VERSION_NOTICE_DISMISSED = booleanPreferencesKey("full_version_notice_dismissed")

    // UI theme preference (SYSTEM / LIGHT / DARK)
    val THEME_MODE = stringPreferencesKey("theme_mode")

    // Post-reboot behavior: when true, BootReceiver posts a "tap to resume" notification
    // if the engine was enabled pre-reboot. Android 14+ blocks true FGS auto-start from
    // BOOT_COMPLETED for our FGS types; this gates the notification path only.
    val RESUME_ON_BOOT = booleanPreferencesKey("resume_on_boot")

    // Issue #7: when set, the engine uses this UA for ALL synthetic traffic
    // instead of randomizing across the user_agents.json pool. Lets users match
    // the synthetic-traffic UA to their real browser so the noise blends with
    // their actual activity. Null/missing = default per-request rotation.
    val CUSTOM_USER_AGENT = stringPreferencesKey("custom_user_agent")

    // App language override. Null/missing = follow system locale (filtered to a
    // SupportedLocale, fallback EN). Otherwise a SupportedLocale.tag value: "en", "es", "fr".
    // Read by com.fauxx.locale.LocaleManager.
    val LANGUAGE_OVERRIDE = stringPreferencesKey("language_override")

    // Foreground-service runtime budget tracker. Android 14+ enforces a 6h cumulative
    // dataSync FGS runtime per rolling 24h while backgrounded; if our in-process timer
    // missed past-session contributions, the next session could still hit the OS limit.
    // FGS_BUDGET_WINDOW_START = wall-clock epoch ms when the current 24h budget window
    // began (set to "now" on first session after a reset); FGS_BUDGET_USED_MS = total
    // FGS runtime credited to that window so far. Reset by FgsBudgetTracker when the
    // window age exceeds 24h.
    val FGS_BUDGET_WINDOW_START = androidx.datastore.preferences.core.longPreferencesKey("fgs_budget_window_start")
    val FGS_BUDGET_USED_MS = androidx.datastore.preferences.core.longPreferencesKey("fgs_budget_used_ms")

    // Layer 2 import (issue #52). The in-app scraper was retired in v0.3.0 — these prefs
    // gate the UI nudges that explain the new user-driven import flow.
    //
    // STALE_SCRAPE_CACHE_NOTICE_SHOWN: true once the user has dismissed the one-time
    // "your old scraped cache is stale, import a fresh archive" notice. Persists across
    // restarts so we never re-nag.
    //
    // IMPORT_REMINDER_MUTED_UNTIL: epoch ms after which the 90-day-old-import banner is
    // allowed to show again. Set to (now + 30 days) on Snooze, set to Long.MAX_VALUE on
    // permanent-mute. 0 (default) means the banner can show now if its 90-day trigger
    // condition is met.
    val STALE_SCRAPE_CACHE_NOTICE_SHOWN = booleanPreferencesKey("stale_scrape_cache_notice_shown")
    val IMPORT_REMINDER_MUTED_UNTIL = androidx.datastore.preferences.core.longPreferencesKey("import_reminder_muted_until")
}

/**
 * Hilt module providing the [DataStore]<[Preferences]> singleton.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.fauxxDataStore
}
