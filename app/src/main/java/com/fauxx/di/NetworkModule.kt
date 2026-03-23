package com.fauxx.di

import android.content.Context
import com.fauxx.network.HeaderRandomizerInterceptor
import com.fauxx.network.UserAgentPool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
    fun provideUserAgentPool(@ApplicationContext context: Context): UserAgentPool =
        UserAgentPool(context)

    @Provides
    @Singleton
    fun provideHeaderRandomizerInterceptor(uaPool: UserAgentPool): HeaderRandomizerInterceptor =
        HeaderRandomizerInterceptor(uaPool)

    @Provides
    @Singleton
    fun provideOkHttpClient(interceptor: HeaderRandomizerInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
}
