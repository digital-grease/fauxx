package com.fauxx.di

import android.content.Context
import com.fauxx.locale.LocaleManager
import com.fauxx.network.BlocklistInterceptor
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
 * Hilt module providing the OkHttp client and its interceptors.
 *
 * ORPHANED TRANSPORT WARNING (#183): after M1 (#168 / #169) routed the synthetic search path onto
 * the real Chromium WebView (PhantomWebViewPool), NO engine module consumes the [OkHttpClient]
 * provided here any more, and DnsNoiseModule uses raw InetAddress. The client,
 * [BlocklistInterceptor], and [HeaderRandomizerInterceptor] are deliberately kept (they retain
 * unit-test coverage, and [UserAgentPool] is still live for the WebView path) but have no outbound
 * consumer. Any NEW OkHttp consumer would ship OkHttp's constant JA3/JA4 TLS fingerprint and fixed
 * header order, re-opening the exact tell #168 / #169 closed. Route any synthetic or search traffic
 * through the Chromium WebView, never OkHttp. The OkHttpOrphanGuardTest fails the build if a new
 * OkHttp request/execution path reappears under app/src/main.
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

    /**
     * Orphaned post-M1 (#183): nothing injects this [OkHttpClient]. Kept for unit-test coverage and
     * documented future re-wiring. Do NOT add a consumer without routing through the Chromium
     * WebView, because OkHttp here carries a constant JA3/JA4 fingerprint and fixed header order
     * (#168 / #169).
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(
        blocklistInterceptor: BlocklistInterceptor,
        headerInterceptor: HeaderRandomizerInterceptor,
    ): OkHttpClient =
        OkHttpClient.Builder()
            // Fail-closed blocklist gate first, so no request reaches a blocked host.
            .addInterceptor(blocklistInterceptor)
            .addInterceptor(headerInterceptor)
            .connectionPool(ConnectionPool(20, 2, TimeUnit.MINUTES))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
}
