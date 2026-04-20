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
