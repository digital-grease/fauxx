package com.fauxx.targeting.layer2.scrapers

import android.webkit.WebView
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Locks the cleanup-on-timeout contract for issue #44 (60-second perceived freeze
 * when "Scrape Now" was hit while signed out): each scraper's `extractCategories`
 * must remove the JavascriptInterface bridge and stop the WebView's lingering
 * page load when the outer 12-second timeout fires.
 *
 * Without these, a stale `window.FauxxBridge.onResult(...)` from a previous page
 * could resume a dead coroutine (#6) and the WebView's pending JS / network work
 * keeps competing for the Main thread for the next scraper iteration.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScraperTimeoutCleanupTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        // The scrapers do `withContext(Dispatchers.Main) { ... }`. Route Main to
        // the test dispatcher so virtual time advances cover Main-bound work too.
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `google scraper times out and removes bridge + stops loading`() = runTest(testDispatcher) {
        val webView = mockk<WebView>(relaxed = true) {
            every { addJavascriptInterface(any(), any()) } just Runs
            every { loadUrl(any()) } just Runs
            every { evaluateJavascript(any(), any()) } just Runs
            every { stopLoading() } just Runs
            every { removeJavascriptInterface(any()) } just Runs
        }

        // Bridge never fires onResult/onError → withTimeoutOrNull (12s) cancels
        // extractCategories. runTest auto-advances virtual time through the
        // suspended delay/timeout.
        val result = GoogleAdsScraper().scrape(webView)

        assertEquals(emptySet<String>(), result)
        verify(atLeast = 1) { webView.stopLoading() }
        verify(atLeast = 1) { webView.removeJavascriptInterface("FauxxBridge") }
    }

    @Test
    fun `facebook scraper times out and removes bridge + stops loading`() = runTest(testDispatcher) {
        val webView = mockk<WebView>(relaxed = true) {
            every { addJavascriptInterface(any(), any()) } just Runs
            every { loadUrl(any()) } just Runs
            every { evaluateJavascript(any(), any()) } just Runs
            every { stopLoading() } just Runs
            every { removeJavascriptInterface(any()) } just Runs
        }

        val result = FacebookAdsScraper().scrape(webView)

        assertEquals(emptySet<String>(), result)
        verify(atLeast = 1) { webView.stopLoading() }
        verify(atLeast = 1) { webView.removeJavascriptInterface("FauxxBridge") }
    }
}
