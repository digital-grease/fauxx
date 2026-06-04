package com.fauxx.engine.webview

import android.content.Context
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fauxx.data.crawllist.DomainBlocklist
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies on a real device that WebViews handed out by [PhantomWebViewPool] are locked down the
 * way the pool's contract promises — most importantly that local `file://` and `content://`
 * access is denied. `allowFileAccess`/`allowContentAccess` default to TRUE on API 26-28, so a
 * regression that dropped those settings would silently re-expose the app's private files to a
 * loaded page; this pins them. The functional settings (JS, DOM storage, image blocking) are
 * checked too, so a future hardening change can't quietly break page loading.
 *
 * WebView and its WebSettings are thread-affine to the creating (main) thread, so the pool's
 * suspend lifecycle is driven from the test thread (each call dispatches to Main) while the
 * settings are read inside runOnMainSync.
 */
@RunWith(AndroidJUnit4::class)
class PhantomWebViewPoolInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun acquiredWebView_locksDownLocalAccessAndKeepsPoolContract() = runBlocking {
        val pool = PhantomWebViewPool(context, mockk<DomainBlocklist>(relaxed = true))
        pool.initialize()
        val webView = pool.acquire()
        try {
            val s = readSettingsOnMainThread(webView)

            // Security-critical (finding #5): the pool only loads remote http(s), never local URIs.
            assertFalse("file:// access must be denied", s.allowFileAccess)
            assertFalse("content:// access must be denied", s.allowContentAccess)
            assertFalse("file-URL -> file-URL access must be denied", s.allowFileAccessFromFileURLs)
            assertFalse("file-URL -> universal access must be denied", s.allowUniversalAccessFromFileURLs)

            // Functional contract: hardening must not break realistic page loading.
            assertTrue("JavaScript must stay enabled", s.javaScriptEnabled)
            assertTrue("DOM storage must stay enabled", s.domStorageEnabled)
            assertTrue("images must not be fetched", s.blockNetworkImage)
        } finally {
            pool.release(webView)
            pool.destroy()
        }
    }

    private data class SettingsSnapshot(
        val allowFileAccess: Boolean,
        val allowContentAccess: Boolean,
        val allowFileAccessFromFileURLs: Boolean,
        val allowUniversalAccessFromFileURLs: Boolean,
        val javaScriptEnabled: Boolean,
        val domStorageEnabled: Boolean,
        val blockNetworkImage: Boolean,
    )

    private fun readSettingsOnMainThread(webView: WebView): SettingsSnapshot {
        lateinit var snapshot: SettingsSnapshot
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val s = webView.settings
            @Suppress("DEPRECATION")
            snapshot = SettingsSnapshot(
                allowFileAccess = s.allowFileAccess,
                allowContentAccess = s.allowContentAccess,
                allowFileAccessFromFileURLs = s.allowFileAccessFromFileURLs,
                allowUniversalAccessFromFileURLs = s.allowUniversalAccessFromFileURLs,
                javaScriptEnabled = s.javaScriptEnabled,
                domStorageEnabled = s.domStorageEnabled,
                blockNetworkImage = s.blockNetworkImage,
            )
        }
        return snapshot
    }
}
