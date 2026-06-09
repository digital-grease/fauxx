package com.fauxx.engine.webview

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import com.fauxx.data.crawllist.DomainBlocklist
import com.fauxx.data.db.LogMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** Maximum number of WebView instances in the pool. */
private const val POOL_SIZE = 2

/**
 * Max time to wait for a free pooled WebView before giving up and failing the action, instead of
 * blocking forever. Bounds the [Semaphore] wait so a leaked permit can't permanently stall the
 * engine loop (issue #124).
 */
private const val ACQUIRE_TIMEOUT_MS = 30_000L

/**
 * Max time for a single main-thread WebView operation (the acquire pick/UA-apply, or the release
 * cleanup). If a wedged WebView provider makes a main-thread op hang, the op is abandoned after
 * this and the permit is still returned, so the pool can't deadlock subsequent acquires.
 */
private const val MAIN_OP_TIMEOUT_MS = 10_000L

/**
 * Manages a pool of reusable background [WebView] instances with:
 * - JavaScript enabled for realistic page loading
 * - Third-party cookies accepted (needed for tracker accumulation)
 * - DOM storage enabled
 * - Local file/content access denied (the pool only ever loads remote http(s) URLs)
 *
 * Cookie / storage isolation (finding #4): this pool is the only WebView in the Fauxx process,
 * and its cookies + DOM storage live in Fauxx's own WebView data directory — set once at app
 * startup via `WebView.setDataDirectorySuffix("fauxx_phantom")` (API 28+; see
 * [com.fauxx.FauxxApp]) — separate from the platform-default WebView store. The user's real
 * browser is a different app in a different process and shares no WebView state with Fauxx. The
 * two pooled instances intentionally share this store so trackers accumulate across reuse;
 * per-instance cookie jars are not an Android primitive (`CookieManager` is process-global) and
 * are not wanted here.
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

    /** Per-WebView allowed-resource-request counter, reset on [acquire] (issue #73 metadata). */
    private val resourceCounters = ConcurrentHashMap<String, AtomicInteger>()

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
     * Seed the User-Agent only if none has been set yet. Lets a module (e.g.
     * SearchPoisonModule) guarantee a coherent Android-Chromium UA on the WebView
     * path even when FingerprintModule (the usual UA source) is disabled, without
     * clobbering a UA that Fingerprint has already rotated in.
     */
    fun setUserAgentIfUnset(ua: String) {
        currentUserAgent.compareAndSet(null, ua)
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
     * Acquire a WebView from the pool. Callers should invoke this OFF the main thread (the engine
     * loop runs on [Dispatchers.IO]); the permit wait is forced onto [Dispatchers.IO] regardless so
     * a caller that is already on the main thread can never freeze it (the root cause of issue
     * #124, where the blocking permit wait ran on the main thread inside a `withContext(Main)`
     * block). Waits up to [ACQUIRE_TIMEOUT_MS] for a free instance, then throws so the caller can
     * log a failed action and continue instead of hanging. The brief main-thread pick + UA-apply is
     * bounded by [MAIN_OP_TIMEOUT_MS] and releases the permit on timeout, so a wedged provider can't
     * leak it.
     */
    suspend fun acquire(): WebView {
        val gotPermit = withContext(Dispatchers.IO) {
            poolSemaphore.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        if (!gotPermit) {
            throw IllegalStateException("No pooled WebView available within ${ACQUIRE_TIMEOUT_MS}ms")
        }
        return try {
            withTimeoutOrNull(MAIN_OP_TIMEOUT_MS) {
                withContext(Dispatchers.Main) {
                    val wv = pool.first { acquired.putIfAbsent(it.tag as String, true) == null }
                    resourceCounters[wv.tag as String]?.set(0)
                    currentUserAgent.get()?.let { wv.settings.userAgentString = it }
                    wv
                }
            } ?: throw IllegalStateException("Acquiring a pooled WebView timed out after ${MAIN_OP_TIMEOUT_MS}ms")
        } catch (e: Exception) {
            poolSemaphore.release()
            throw e
        }
    }

    /**
     * Best-effort, scalar metadata about the page currently loaded in [webView] (issue #73):
     * page title, the cookie count in the (process-global) jar for [url], and the number of
     * allowed resource requests since [acquire]. Returns a [LogMetadata] JSON string, or null
     * if nothing could be read.
     *
     * MUST be called on the main thread (i.e. inside the caller's `withContext(Dispatchers.Main)`
     * block) AFTER the dwell and BEFORE [release] — [release] loads `about:blank`, which would
     * null out the title and reset the document. Every read is guarded; this never throws, and a
     * failed read simply omits that field so the action's success is unaffected.
     */
    fun captureMetadata(webView: WebView, url: String, vararg extra: Pair<String, String?>): String? {
        val title = runCatching {
            webView.title?.takeIf { it.isNotBlank() && it != "about:blank" }
        }.getOrNull()
        val cookieCount = runCatching {
            CookieManager.getInstance().getCookie(url)?.split(";")?.count { it.isNotBlank() }
        }.getOrNull()
        val resourceCount = runCatching {
            resourceCounters[webView.tag as? String]?.get()
        }.getOrNull()
        return LogMetadata.toJson(
            *extra,
            LogMetadata.PAGE_TITLE to title,
            LogMetadata.COOKIES_IN_JAR to cookieCount?.toString(),
            LogMetadata.RESOURCES_LOADED to resourceCount?.takeIf { it > 0 }?.toString(),
        )
    }

    /**
     * Release a WebView back to the pool after use. Clears state to prevent accumulated
     * DOM/JS/cookie data across reuses. Call this OFF the main thread (the per-WebView cleanup hops
     * to the main thread internally and is bounded by [MAIN_OP_TIMEOUT_MS]). The permit is ALWAYS
     * returned in the `finally`, even if the main-thread cleanup hangs on a wedged WebView provider,
     * so a stuck teardown can never leak a permit and freeze later acquires (issue #124).
     */
    suspend fun release(webView: WebView) {
        val tag = webView.tag as? String ?: return
        try {
            withTimeoutOrNull(MAIN_OP_TIMEOUT_MS) {
                withContext(Dispatchers.Main) {
                    webView.stopLoading()
                    webView.clearHistory()
                    webView.clearCache(false)
                    webView.evaluateJavascript("document.open();document.close();", null)
                    webView.loadUrl("about:blank")
                    // Note: WebStorage.getInstance().deleteAllData() is intentionally NOT called
                    // here because it's a global singleton that wipes storage for ALL WebView
                    // instances. Per-WebView cleanup (stopLoading + clearHistory + clearCache +
                    // about:blank) is sufficient; accumulated DOM storage/cookies are desired for
                    // tracker accumulation.
                }
            }
        } finally {
            acquired.remove(tag)
            poolSemaphore.release()
        }
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
        // Persist the accumulated cookie jar to Fauxx's WebView data directory before tearing the
        // instances down, so tracker state survives the next process start.
        runCatching { CookieManager.getInstance().flush() }
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
            // Safe Browsing is configured app-wide via the AndroidManifest WebView meta-data
            // (see EnableSafeBrowsing there). Intentionally not set per-WebView, so the manifest
            // configuration is authoritative (a per-WebView setter would override the manifest).

            // Lock down local-resource access. The phantom pool only ever loads remote http(s)
            // crawl URLs, never file:// or content://, but allowFileAccess/allowContentAccess
            // default to true on API 26-28 — leaving a malicious page able to read the app's
            // private files or content-provider data. Deny all of it explicitly.
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
        }

        // Enable third-party cookies for realistic tracker accumulation
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        val resourceCounter = AtomicInteger(0)
        resourceCounters[tag] = resourceCounter
        webView.webViewClient = PhantomWebViewClient(blocklist, resourceCounter = resourceCounter)
        webView.isClickable = false
        webView.isFocusable = false

        return webView
    }
}
