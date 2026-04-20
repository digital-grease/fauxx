@file:Suppress("DEPRECATION")

package com.fauxx.di

import android.content.Context
import timber.log.Timber
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fauxx.data.db.ActionLogDao
import com.fauxx.data.db.MIGRATION_1_2
import com.fauxx.data.db.MIGRATION_2_3
import com.fauxx.data.db.PhantomDatabase
import com.fauxx.targeting.layer1.DemographicProfileDao
import com.fauxx.targeting.layer2.PlatformProfileDao
import com.fauxx.targeting.layer3.PersonaHistoryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

/**
 * Hilt module providing application-level singletons: Room database (SQLCipher-encrypted)
 * and DAOs. The database passphrase is encrypted via Tink (AndroidKeyStore-backed).
 *
 * On first launch after upgrading from the legacy EncryptedSharedPreferences version,
 * this module performs a one-time migration of both the DB passphrase and app preferences.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideTinkKeyManager(@ApplicationContext context: Context): TinkKeyManager =
        TinkKeyManager(context)

    @Provides
    @Singleton
    fun providePhantomDatabase(
        @ApplicationContext context: Context,
        tinkKeyManager: TinkKeyManager,
        dataStore: DataStore<Preferences>
    ): PhantomDatabase {
        val masterKey = lazy {
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        }

        // Migrate passphrase from legacy EncryptedSharedPreferences to Tink if needed.
        if (!tinkKeyManager.hasStoredPassphrase()) {
            migratePassphraseFromLegacy(context, masterKey.value, tinkKeyManager)
        }

        // Migrate app preferences from legacy EncryptedSharedPreferences to DataStore.
        migrateAppPrefsFromLegacy(context, masterKey, dataStore)

        val passphrase = tinkKeyManager.getOrCreateDatabasePassphrase()
            .toByteArray(Charsets.UTF_8)
        val factory = SupportOpenHelperFactory(passphrase)

        return Room.databaseBuilder(
            context,
            PhantomDatabase::class.java,
            "phantom.db"
        )
            .openHelperFactory(factory)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    }

    /**
     * One-time migration: reads the SQLCipher passphrase from the old
     * EncryptedSharedPreferences store and writes it to Tink-encrypted storage.
     */
    private fun migratePassphraseFromLegacy(
        context: Context,
        masterKey: MasterKey,
        tinkKeyManager: TinkKeyManager
    ) {
        try {
            val legacyPrefs = EncryptedSharedPreferences.create(
                context,
                "fauxx_db_key_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            val legacyPassphrase = legacyPrefs.getString("db_passphrase", null)
            if (legacyPassphrase != null) {
                tinkKeyManager.storeDatabasePassphrase(legacyPassphrase)
                Timber.i("Migrated DB passphrase from EncryptedSharedPreferences to Tink")
            }
        } catch (e: Exception) {
            Timber.d("No legacy DB passphrase to migrate: ${e.message}")
        }
    }

    /**
     * One-time migration: copies app preferences from the old encrypted SharedPreferences
     * ("fauxx_secure_prefs") into DataStore. Only runs if DataStore is empty and the
     * legacy file exists. Blocking — called during DI graph construction.
     */
    private fun migrateAppPrefsFromLegacy(
        context: Context,
        masterKey: Lazy<MasterKey>,
        dataStore: DataStore<Preferences>
    ) {
        // Check if legacy prefs file exists on disk before attempting to open it.
        val legacyFile = java.io.File(context.filesDir.parentFile, "shared_prefs/fauxx_secure_prefs.xml")
        if (!legacyFile.exists()) return

        try {
            // Only migrate if DataStore has no data yet (i.e. first launch after upgrade).
            // Timeout prevents ANR if DataStore is corrupted or slow.
            val currentPrefs = runBlocking {
                withTimeoutOrNull(3_000L) { dataStore.data.first() }
            }
            if (currentPrefs == null) {
                Timber.w("DataStore read timed out during legacy migration check, skipping")
                return
            }
            if (currentPrefs.asMap().isNotEmpty()) return

            val legacy = EncryptedSharedPreferences.create(
                context,
                "fauxx_secure_prefs",
                masterKey.value,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            runBlocking {
                withTimeoutOrNull(5_000L) {
                    dataStore.edit { prefs ->
                        // Engine state
                        prefs[PreferenceKeys.ENABLED] = legacy.getBoolean("enabled", false)
                        legacy.getString("intensity", null)?.let { prefs[PreferenceKeys.INTENSITY] = it }
                        prefs[PreferenceKeys.WIFI_ONLY] = legacy.getBoolean("wifi_only", true)
                        prefs[PreferenceKeys.BATTERY_THRESHOLD] = legacy.getInt("battery_threshold", 20)
                        prefs[PreferenceKeys.ALLOWED_HOURS_START] = legacy.getInt("allowed_hours_start", 7)
                        prefs[PreferenceKeys.ALLOWED_HOURS_END] = legacy.getInt("allowed_hours_end", 23)

                        // Module toggles
                        prefs[PreferenceKeys.MODULE_SEARCH] = legacy.getBoolean("module_search", true)
                        prefs[PreferenceKeys.MODULE_AD] = legacy.getBoolean("module_ad", true)
                        prefs[PreferenceKeys.MODULE_LOCATION] = legacy.getBoolean("module_location", false)
                        prefs[PreferenceKeys.MODULE_FINGERPRINT] = legacy.getBoolean("module_fingerprint", true)
                        prefs[PreferenceKeys.MODULE_COOKIE] = legacy.getBoolean("module_cookie", true)
                        prefs[PreferenceKeys.MODULE_APPSIGNAL] = legacy.getBoolean("module_appsignal", false)
                        prefs[PreferenceKeys.MODULE_DNS] = legacy.getBoolean("module_dns", true)

                        // Layer toggles
                        prefs[PreferenceKeys.LAYER1_ENABLED] = legacy.getBoolean("layer1_enabled", false)
                        prefs[PreferenceKeys.LAYER2_ENABLED] = legacy.getBoolean("layer2_enabled", false)
                        prefs[PreferenceKeys.LAYER3_ENABLED] = legacy.getBoolean("layer3_enabled", true)

                        // Onboarding
                        prefs[PreferenceKeys.ONBOARDING_COMPLETED] = legacy.getBoolean("onboarding_completed", false)

                        // Post-reboot resume (default on — preserves pre-toggle behavior)
                        prefs[PreferenceKeys.RESUME_ON_BOOT] = legacy.getBoolean("resume_on_boot", true)
                    }
                } ?: Timber.w("DataStore write timed out during legacy migration")
            }
            Timber.i("Migrated app preferences from EncryptedSharedPreferences to DataStore")
        } catch (e: Exception) {
            Timber.w("Failed to migrate legacy app preferences: ${e.message}")
        }
    }

    @Provides
    @Singleton
    fun provideActionLogDao(db: PhantomDatabase): ActionLogDao = db.actionLogDao()

    @Provides
    @Singleton
    fun provideDemographicProfileDao(db: PhantomDatabase): DemographicProfileDao =
        db.demographicProfileDao()

    @Provides
    @Singleton
    fun providePlatformProfileDao(db: PhantomDatabase): PlatformProfileDao =
        db.platformProfileDao()

    @Provides
    @Singleton
    fun providePersonaHistoryDao(db: PhantomDatabase): PersonaHistoryDao =
        db.personaHistoryDao()
}
