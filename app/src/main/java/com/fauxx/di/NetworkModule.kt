package com.fauxx.di

import android.content.Context
import com.fauxx.locale.LocaleManager
import com.fauxx.network.HeaderRandomizerInterceptor
import com.fauxx.network.UserAgentPool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Hilt module providing OkHttp client with header randomization interceptor.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideUserAgentPool(
        @ApplicationContext context: Context,
        profileRepo: com.fauxx.engine.PoisonProfileRepository
    ): UserAgentPool = UserAgentPool(context, profileRepo)

    @Provides
    @Singleton
    fun provideHeaderRandomizerInterceptor(
        uaPool: UserAgentPool,
        localeManager: LocaleManager
    ): HeaderRandomizerInterceptor = HeaderRandomizerInterceptor(uaPool, localeManager)

    @Provides
    @Singleton
    fun provideOkHttpClient(interceptor: HeaderRandomizerInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .connectionPool(ConnectionPool(20, 2, TimeUnit.MINUTES))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
}
