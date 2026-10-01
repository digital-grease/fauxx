package com.fauxx.di

import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.dns.CustomDns
import com.fauxx.engine.dns.CustomDnsRouter
import com.fauxx.engine.dns.DnsInterceptionProbe
import com.fauxx.engine.dns.NetworkChanges
import com.fauxx.engine.webview.PhantomBrowsingPrefs
import com.fauxx.engine.webview.PhantomProxyAuth
import com.fauxx.engine.webview.WebViewCapabilities
import com.fauxx.engine.webview.WebViewProxyOverride
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.Module
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Bindings that let the phantom WebView pool and the UI read app state and WebView capabilities. */
@Module
@InstallIn(SingletonComponent::class)
object WebViewModule {

    /** Reads the cached profile, so the pool can call it on the main thread during acquire. */
    @Provides
    @Singleton
    fun providePhantomBrowsingPrefs(profileRepo: PoisonProfileRepository): PhantomBrowsingPrefs =
        object : PhantomBrowsingPrefs {
            override fun loadImages(): Boolean = profileRepo.getProfile().loadImages
        }

    @Provides
    @Singleton
    fun provideWebViewCapabilities(): WebViewCapabilities = WebViewCapabilities.SYSTEM

    /** Custom DNS (#227): one router serves the engine's lifecycle and the WebView's proxy auth. */
    @Provides
    @Singleton
    fun provideWebViewProxyOverride(): WebViewProxyOverride = WebViewProxyOverride.SYSTEM

    @Provides
    @Singleton
    fun provideDnsInterceptionProbe(): DnsInterceptionProbe = DnsInterceptionProbe.SYSTEM

    /**
     * Default-network changes, for re-running the plain-DNS interception probe (#227). A VPN
     * switching on or off changes the default network too, so this also catches that.
     */
    @Provides
    @Singleton
    fun provideNetworkChanges(@ApplicationContext context: Context): NetworkChanges = object : NetworkChanges {
        override val changes: Flow<Unit> = callbackFlow {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { trySend(Unit) }
                override fun onLost(network: Network) { trySend(Unit) }
            }
            runCatching { cm.registerDefaultNetworkCallback(callback) }
                .onFailure { close(it); return@callbackFlow }
            awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
        }.conflate()
    }

    @Provides
    fun provideCustomDns(router: CustomDnsRouter): CustomDns = router

    @Provides
    fun providePhantomProxyAuth(router: CustomDnsRouter): PhantomProxyAuth = router
}
