package com.fauxx.di

import android.content.Context
import androidx.room.Room
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fauxx.data.db.ActionLogDao
import com.fauxx.data.db.PhantomDatabase
import com.fauxx.targeting.layer1.DemographicProfileDao
import com.fauxx.targeting.layer2.PlatformProfileDao
import com.fauxx.targeting.layer3.PersonaHistoryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.sqlcipher.database.SupportFactory
import android.content.SharedPreferences
import javax.inject.Singleton

/**
 * Hilt module providing application-level singletons: Room database (SQLCipher-encrypted),
 * OkHttpClient, EncryptedSharedPreferences, and DAOs.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun providePhantomDatabase(@ApplicationContext context: Context): PhantomDatabase {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        // Derive SQLCipher passphrase from the AndroidKeyStore master key's encoded form
        val passphrase = masterKey.toString().toByteArray(Charsets.UTF_8)
            .let { net.sqlcipher.database.SQLiteDatabase.getBytes(
                android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP).toCharArray()
            ) }
        val factory = SupportFactory(passphrase)

        return Room.databaseBuilder(
            context,
            PhantomDatabase::class.java,
            "phantom.db"
        )
            .openHelperFactory(factory)
            .build()
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

    @Provides
    @Singleton
    fun provideEncryptedSharedPreferences(@ApplicationContext context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            "fauxx_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
