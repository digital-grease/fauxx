package com.fauxx.di

import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.dns.CustomDns
import com.fauxx.engine.dns.CustomDnsRouter
import com.fauxx.engine.webview.PhantomBrowsingPrefs
import com.fauxx.engine.webview.PhantomProxyAuth
import com.fauxx.engine.webview.WebViewCapabilities
import com.fauxx.engine.webview.WebViewProxyOverride
import dagger.Module
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
    fun provideCustomDns(router: CustomDnsRouter): CustomDns = router

    @Provides
    fun providePhantomProxyAuth(router: CustomDnsRouter): PhantomProxyAuth = router
}
