package com.fauxx.di

import android.content.Context
import android.content.SharedPreferences
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
 * Replaces [AppModule] in instrumented tests with an unencrypted in-memory Room database
 * and plain [SharedPreferences] so tests run fast and in isolation without requiring
 * AndroidKeyStore or SQLCipher native libraries to be fully initialized.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [AppModule::class])
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
    fun provideSharedPreferences(@ApplicationContext context: Context): SharedPreferences =
        context.getSharedPreferences("fauxx_test_prefs", Context.MODE_PRIVATE)
}
