package com.fauxx.di

import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.webview.PhantomBrowsingPrefs
import com.fauxx.engine.webview.WebViewCapabilities
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
}
