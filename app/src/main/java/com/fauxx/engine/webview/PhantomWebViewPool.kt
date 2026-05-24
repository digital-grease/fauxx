package com.fauxx.engine.webview

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import com.fauxx.data.crawllist.DomainBlocklist
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** Maximum number of WebView instances in the pool. */
private const val POOL_SIZE = 2

/**
 * Manages a pool of reusable background [WebView] instances with:
 * - Separate cookie stores from the user's real browser
 * - JavaScript enabled for realistic page loading
 * - Third-party cookies accepted (needed for tracker accumulation)
 * - DOM storage enabled
 * - Process isolation via separate data directories
 *
 * All WebViews use [PhantomWebViewClient] which blocks blocklisted domains.
 *
 * Pool size was reduced from 3 to 2 in v0.3.0 when the scraper-reserved slot was
 * retired alongside the in-app Layer 2 scraper (issue #52). AdPollution + Cookie
 * + DiverseBrowsing modules share the remaining slots; concurrent acquires block
 * via [poolSemaphore].
 */
@Singleton
class PhantomWebViewPool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val blocklist: DomainBlocklist
) {
    private val pool = mutableListOf<WebView>()
    private var initialized = false

    /** Semaphore controlling access to pooled WebViews. */
    private val poolSemaphore = Semaphore(POOL_SIZE)

    /** Tracks which WebViews are currently acquired (tag -> true). */
    private val acquired = ConcurrentHashMap<String, Boolean>()

    /**
     * Current User-Agent string to apply to WebViews on acquire.
     * Updated by [FingerprintModule] on each rotation action.
     */
    private val currentUserAgent = AtomicReference<String?>(null)

    /**
     * Set the User-Agent string that will be applied to WebViews when they are acquired.
     * Called by FingerprintModule when a UA rotation action fires.
     */
    fun setUserAgent(ua: String) {
        currentUserAgent.set(ua)
    }

    /**
     * Initialize the WebView pool on the main thread.
     * Must be called before [acquire].
     */
    suspend fun initialize() = withContext(Dispatchers.Main) {
        if (initialized) return@withContext
        repeat(POOL_SIZE) { index ->
            val webView = createWebView(tag = "pool_$index")
            pool.add(webView)
        }
        initialized = true
    }

    /**
     * Acquire a WebView from the pool. Suspends if all instances are in use until
     * one is released.
     */
    suspend fun acquire(): WebView {
        poolSemaphore.acquire()
        return try {
            withContext(Dispatchers.Main) {
                val wv = pool.first { acquired.putIfAbsent(it.tag as String, true) == null }
                currentUserAgent.get()?.let { wv.settings.userAgentString = it }
                wv
            }
        } catch (e: Exception) {
            poolSemaphore.release()
            throw e
        }
    }

    /**
     * Release a WebView back to the pool after use. Clears state to prevent
     * accumulated DOM/JS/cookie data across reuses.
     */
    suspend fun release(webView: WebView) = withContext(Dispatchers.Main) {
        val tag = webView.tag as? String ?: return@withContext
        webView.stopLoading()
        webView.clearHistory()
        webView.clearCache(false)
        webView.evaluateJavascript("document.open();document.close();", null)
        webView.loadUrl("about:blank")
        // Note: WebStorage.getInstance().deleteAllData() is intentionally NOT called here
        // because it's a global singleton that wipes storage for ALL WebView instances.
        // Per-WebView cleanup (stopLoading + clearHistory + clearCache + about:blank) is
        // sufficient; accumulated DOM storage/cookies are desired for tracker accumulation.
        acquired.remove(tag)
        poolSemaphore.release()
    }

    /**
     * Destroy all WebView instances and release resources.
     *
     * WebView is thread-affine: [WebView.destroy] must be called on the thread that
     * created the WebView (the main thread here; see [initialize]). This function
     * dispatches to [Dispatchers.Main] internally — **never call it from `runBlocking`
     * on the main thread**, which would self-deadlock. Invoke it from a coroutine on
     * a background dispatcher or from a launched cleanup scope.
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
            safeBrowsingEnabled = true // Google Safe Browsing — real-time malicious URL checks
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
