package com.fauxx.engine.webview

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import com.fauxx.data.crawllist.DomainBlocklist
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Maximum number of WebView instances in the pool. */
private const val POOL_SIZE = 3

/**
 * Manages a pool of reusable background [WebView] instances with:
 * - Separate cookie stores from the user's real browser
 * - JavaScript enabled for realistic page loading
 * - Third-party cookies accepted (needed for tracker accumulation)
 * - DOM storage enabled
 * - Process isolation via separate data directories
 *
 * One instance is reserved/tagged for Layer 2 scraping to avoid cookie contamination.
 * All WebViews use [PhantomWebViewClient] which blocks blocklisted domains.
 */
@Singleton
class PhantomWebViewPool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val blocklist: DomainBlocklist
) {
    private val pool = mutableListOf<WebView>()
    private var initialized = false

    /** Tag identifying the scraper-reserved WebView instance. */
    companion object {
        const val SCRAPER_TAG = "scraper"
    }

    /**
     * Initialize the WebView pool on the main thread.
     * Must be called before [acquire].
     */
    suspend fun initialize() = withContext(Dispatchers.Main) {
        if (initialized) return@withContext
        repeat(POOL_SIZE) { index ->
            val webView = createWebView(tag = if (index == 0) SCRAPER_TAG else "pool_$index")
            pool.add(webView)
        }
        initialized = true
    }

    /**
     * Acquire a WebView from the pool for general use (not scraping).
     * Returns the first non-scraper, non-busy instance.
     */
    suspend fun acquire(): WebView = withContext(Dispatchers.Main) {
        pool.firstOrNull { it.tag != SCRAPER_TAG }
            ?: pool.first { it.tag != SCRAPER_TAG }
    }

    /**
     * Acquire the scraper-reserved WebView instance (Layer 2 use only).
     */
    suspend fun acquireForScraper(): WebView = withContext(Dispatchers.Main) {
        pool.first { it.tag == SCRAPER_TAG }
    }

    /**
     * Release a WebView back to the pool after use. Stops loading but preserves cookies.
     */
    suspend fun release(webView: WebView) = withContext(Dispatchers.Main) {
        webView.stopLoading()
        webView.loadUrl("about:blank")
    }

    /**
     * Destroy all WebView instances and release resources.
     */
    suspend fun destroy() = withContext(Dispatchers.Main) {
        pool.forEach { it.destroy() }
        pool.clear()
        initialized = false
    }

    private fun createWebView(tag: String): WebView {
        val webView = WebView(context)
        webView.tag = tag

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            mediaPlaybackRequiresUserGesture = true
            blockNetworkImage = true // Don't download images
            loadsImagesAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        // Enable third-party cookies for realistic tracker accumulation
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = PhantomWebViewClient(blocklist)
        webView.isClickable = false
        webView.isFocusable = false

        return webView
    }
}
