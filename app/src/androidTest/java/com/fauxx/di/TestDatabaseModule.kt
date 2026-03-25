package com.fauxx.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.fauxx.data.db.ActionLogDao
import com.fauxx.data.db.PhantomDatabase
import com.fauxx.targeting.layer1.DemographicProfileDao
import com.fauxx.targeting.layer2.PlatformProfileDao
import com.fauxx.targeting.layer3.PersonaHistoryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Replaces [AppModule] and [DataStoreModule] in instrumented tests with an unencrypted
 * in-memory Room database and a test DataStore so tests run fast and in isolation
 * without requiring AndroidKeyStore, SQLCipher, or Tink to be fully initialized.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [AppModule::class, DataStoreModule::class]
)
object TestDatabaseModule {

    @Provides
    @Singleton
    fun provideTestDatabase(@ApplicationContext context: Context): PhantomDatabase =
        Room.inMemoryDatabaseBuilder(context, PhantomDatabase::class.java)
            .allowMainThreadQueries()
            .build()

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

    @Provides
    @Singleton
    fun provideTinkKeyManager(@ApplicationContext context: Context): TinkKeyManager =
        TinkKeyManager(context)

    @Provides
    @Singleton
    fun provideTestDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile("fauxx_test_prefs")
        }
}
