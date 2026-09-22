package com.fauxx.engine.webview

import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import com.fauxx.data.crawllist.DomainBlocklist
import com.fauxx.data.db.LogMetadata
import com.fauxx.data.device.DeviceProfile
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
import timber.log.Timber

/** Maximum number of WebView instances in the pool. */
private const val POOL_SIZE = 2

/**
 * Outcome of [PhantomWebViewPool.setPersonaJar] (issue #242).
 *
 * Deliberately not a Boolean. Three different situations used to collapse to `false`, and the
 * caller has to tell them apart: on [UNSUPPORTED] it must still apply the persona's User-Agent
 * (that whole population of devices would otherwise freeze on one UA forever), while on
 * [DEFERRED] and [FAILED] it must NOT, because the pool is still serving the previous persona's
 * jar and pairing it with the new persona's UA is exactly the cookie-cannot-change-handsets
 * contradiction the feature exists to remove.
 */
enum class JarSwap {
    /** The pool is now on this persona's jar. */
    SWAPPED,

    /** Already on this jar; nothing to do. */
    UNCHANGED,

    /** No multi-profile support on this device; the shared jar is in use and always will be. */
    UNSUPPORTED,

    /** The pool was busy and the swap was abandoned; the old jar is still live. Retry later. */
    DEFERRED,

    /** The rebuild threw. The pool was left for re-initialization and the jar is unpublished. */
    FAILED;

    /**
     * Whether the pool's jar matches the persona the caller asked for, and therefore whether it
     * is safe to present that persona's device identity.
     */
    val jarMatchesPersona: Boolean get() = this == SWAPPED || this == UNCHANGED || this == UNSUPPORTED
}

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
 * Per-permit wait when draining the pool for a persona jar rotation (issue #242). Generous
 * because a crawl page load legitimately takes seconds; if the pool is still busy after this the
 * rotation is deferred to the next fingerprint action rather than forced, so a long load is never
 * destroyed mid-flight for the sake of a jar swap.
 */
private const val REBUILD_DRAIN_TIMEOUT_MS = 15_000L

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
 * browser is a different app in a different process and shares no WebView state with Fauxx.
 *
 * Within that directory the pool is further partitioned PER PERSONA (issue #242): see
 * [setPersonaJar] and [PersonaJarStore]. The two pooled instances share the ACTIVE persona's jar,
 * so trackers still accumulate across reuse, but a jar does not outlive the persona and device
 * it belongs to. This KDoc previously claimed per-instance cookie jars "are not an Android
 * primitive (`CookieManager` is process-global)". That holds for [android.webkit.CookieManager]'s
 * process-global instance and not for WebView as a whole: `androidx.webkit`'s multi-profile API
 * gives each named profile its own cookie store. On a device whose WebView lacks that API the
 * pool silently keeps the old single-jar behaviour.
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
    private val blocklist: DomainBlocklist,
    private val jarStore: PersonaJarStore
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
     * Issue #268: the first main-frame load error seen on each pooled WebView since [acquire],
     * or null if the load succeeded. Mirrors [resourceCounters] — per-instance state the pool owns,
     * reset on acquire, read by modules via [lastLoadError] to decide the action's success.
     */
    private val loadErrors = ConcurrentHashMap<String, AtomicReference<String?>>()

    /** Total renderer-process deaths recovered from (issue #210), for diagnostics. */
    private val rendererDeaths = AtomicInteger(0)

    /**
     * Current User-Agent string to apply to WebViews on acquire.
     * Updated by [FingerprintModule] on each rotation action.
     */
    private val currentUserAgent = AtomicReference<String?>(null)

    /**
     * Current persona device (issue #242): its UA is applied to WebViews on acquire and its fixed
     * navigator values are injected on page load (via the client's device provider). Null when Layer
     * 3 is off, in which case the injected navigator values fall back to fixed defaults.
     */
    private val currentDevice = AtomicReference<DeviceProfile?>(null)

    /**
     * Jar the pooled WebViews are currently bound to (issue #242), or null for the shared
     * process-global jar (no persona yet, or a device without multi-profile support).
     *
     * Only ever written from [setPersonaJar]'s rebuild, and only once the new instances exist,
     * so it always names the jar the live WebViews actually read. Writing it earlier would point
     * cookie reads at a jar the pool is not on.
     */
    private val currentJarKey = AtomicReference<String?>(null)

    /** Jar the pool is currently bound to, or null for the shared jar. Diagnostics and tests. */
    fun boundJarKey(): String? = currentJarKey.get()

    /**
     * Set the User-Agent string that will be applied to WebViews when they are acquired.
     * Called by FingerprintModule when a UA rotation action fires.
     */
    fun setUserAgent(ua: String) {
        currentUserAgent.set(ua)
    }

    /**
     * Bind the active persona's [device] (issue #242): applies its UA on the next acquire and makes
     * the injected navigator overrides use its fixed hardwareConcurrency/deviceMemory. Called by
     * FingerprintModule when a persona is active.
     */
    fun setDevice(device: DeviceProfile) {
        currentDevice.set(device)
        currentUserAgent.set(device.userAgent)
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
     * Point the pool at [personaId]'s own cookie jar and site storage (issue #242).
     *
     * Called on every fingerprint action, so the unchanged case must stay free: it is a single
     * atomic read. When the jar HAS changed, the pooled WebViews have to be rebuilt, because
     * `setProfile` cannot re-profile a live WebView. The jar therefore turns over in the same
     * instant as the persona's User-Agent, which is the coherence the whole change exists for:
     * a cookie cannot follow a user from one handset model to another, so a jar that outlived
     * its device would be a contradiction a tracker could read in a single pass.
     *
     * A rebuild first drains every pool permit, so no in-flight crawl has its WebView destroyed
     * underneath it. If the pool cannot be drained (a wedged or long-running load), the rotation
     * is ABANDONED rather than forced: [currentJarKey] is left alone, the old jar stays live, and
     * the next fingerprint action retries. Continuing to accumulate into the previous persona's
     * jar for a few more minutes is a much smaller problem than tearing down a WebView mid-load.
     */
    suspend fun setPersonaJar(personaId: String): JarSwap {
        if (!jarStore.isSupported) return JarSwap.UNSUPPORTED
        val jarKey = jarStore.jarKeyFor(personaId)
        if (currentJarKey.get() == jarKey) return JarSwap.UNCHANGED
        // `initialized` is written on the main thread, so test it there rather than from the
        // caller's dispatcher. Reading it here raced a queued destroy() and could run a rebuild
        // and an initialize() against the same empty pool, leaving four WebViews with duplicate
        // tags; the later twin then overwrote resourceCounters/loadErrors while acquire() kept
        // handing out the earlier one, silently reverting issues #268 and #73 for the session.
        val needsRebuild = withContext(Dispatchers.Main) {
            if (initialized) {
                true
            } else {
                // Nothing to rebuild yet; initialize() binds this jar when it builds the pool.
                currentJarKey.set(jarKey)
                false
            }
        }
        if (!needsRebuild) return JarSwap.SWAPPED
        return rebuildForJar(jarKey)
    }

    /**
     * Tear the pool down and rebuild it bound to [jarKey]. See [setPersonaJar] for why this is a
     * rebuild rather than a re-bind, and why failing to drain is a no-op rather than a forced
     * teardown.
     */
    private suspend fun rebuildForJar(jarKey: String): JarSwap {
        // `drained` lives OUTSIDE the withContext and the try wraps the drain itself.
        // tryAcquire(timeout) is a blocking JDK call that coroutine cancellation cannot
        // interrupt, so permits are genuinely taken; if cancellation then discarded the
        // withContext result, the finally never ran and the permits were lost for the life of
        // the singleton (destroy() resets the pool, not the semaphore). One lost permit defers
        // every future rotation forever; two make every acquire() fail.
        var drained = 0
        try {
            withContext(Dispatchers.IO) {
                repeat(POOL_SIZE) {
                    if (poolSemaphore.tryAcquire(REBUILD_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        drained++
                    }
                }
            }
            if (drained < POOL_SIZE) {
                Timber.w(
                    "Jar rotation deferred: drained $drained/$POOL_SIZE permits, pool still busy"
                )
                return JarSwap.DEFERRED
            }
            return withContext(Dispatchers.Main) {
                runCatching { jarStore.cookieManagerFor(currentJarKey.get()).flush() }
                pool.forEach { runCatching { it.destroy() } }
                pool.clear()
                // Build into a local list and swap in only once ALL instances exist. A partial
                // build used to leave the pool short with initialized still true, which made
                // acquire()'s pool.first{} throw on every action until the service restarted,
                // with currentJarKey already advanced so no later rotation would retry.
                // Throwable, not Exception: the realistic failure is OutOfMemoryError while
                // allocating two fresh WebViews right after destroying two.
                val rebuilt = mutableListOf<WebView>()
                try {
                    currentJarKey.set(jarKey)
                    repeat(POOL_SIZE) { index -> rebuilt.add(createWebView(tag = "pool_$index")) }
                } catch (t: Throwable) {
                    rebuilt.forEach { runCatching { it.destroy() } }
                    // Unpublish the jar so the next fingerprint action retries the swap, then
                    // rebuild UNBOUND so the engine keeps crawling on the shared jar instead of
                    // stalling. A degraded identity beats a dead pool, and nothing else calls
                    // initialize() mid-session to recover one.
                    currentJarKey.set(null)
                    initialized = false
                    runCatching { ensurePool() }
                        .onFailure { Timber.e(it, "Pool heal after failed jar rebuild also failed") }
                    Timber.e(t, "Jar rebuild failed; pool rebuilt on the shared jar")
                    return@withContext JarSwap.FAILED
                }
                pool.addAll(rebuilt)
                initialized = true
                Timber.d("Rebuilt phantom pool on the active persona's jar")
                JarSwap.SWAPPED
            }
        } finally {
            repeat(drained) { poolSemaphore.release() }
        }
    }

    /**
     * Initialize the WebView pool on the main thread.
     * Must be called before [acquire].
     */
    suspend fun initialize() = withContext(Dispatchers.Main) {
        if (initialized) return@withContext
        ensurePool()
    }

    /**
     * Fill the pool up to [POOL_SIZE], reusing whichever tags are free. Main thread only.
     *
     * Idempotent and self-healing, which matters because a failed jar rebuild can leave the pool
     * short or empty part-way through a session. Nothing calls [initialize] after module start,
     * so without a heal on the [acquire] path every subsequent action would throw
     * `NoSuchElementException` out of `pool.first {}` until the foreground service restarted.
     *
     * Tags are assigned from the free set rather than from the loop index so a partial pool does
     * not end up with duplicates, which would silently detach `resourceCounters` and `loadErrors`
     * from the instance [acquire] hands out (issues #268 and #73).
     */
    private fun ensurePool() {
        while (pool.size < POOL_SIZE) {
            val used = pool.mapNotNull { it.tag as? String }.toSet()
            val tag = (0 until POOL_SIZE).map { "pool_$it" }.firstOrNull { it !in used } ?: break
            pool.add(createWebView(tag = tag))
        }
        initialized = pool.isNotEmpty()
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
                    // Heal a pool left short by a failed jar rebuild before picking from it.
                    if (pool.size < POOL_SIZE) ensurePool()
                    val wv = pool.first { acquired.putIfAbsent(it.tag as String, true) == null }
                    resourceCounters[wv.tag as String]?.set(0)
                    loadErrors[wv.tag as String]?.set(null)
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
    /**
     * The first main-frame load error on [webView] since [acquire], or null if the main frame
     * loaded cleanly (issue #268).
     *
     * Modules use this to decide whether an action actually succeeded. Before this existed, a load
     * that failed at the network layer still counted as a success: the module fired `loadUrl`,
     * dwelled, and returned true regardless, so users running a DNS blocker saw "Success" lines for
     * pages that never loaded. Safe to call from any thread.
     */
    fun lastLoadError(webView: WebView): String? =
        runCatching { loadErrors[webView.tag as? String]?.get() }.getOrNull()

    /**
     * Reset the recorded load error for [webView] (issue #268). Callers that perform SEVERAL
     * navigations on one acquired instance (SearchPoisonModule loads a goal SERP then a chain of
     * refinements) must call this before each one, otherwise the first failure would mark every
     * later navigation in the session as failed too.
     */
    fun clearLoadError(webView: WebView) {
        runCatching { loadErrors[webView.tag as? String]?.set(null) }
    }

    fun captureMetadata(webView: WebView, url: String, vararg extra: Pair<String, String?>): String? {
        val title = runCatching {
            webView.title?.takeIf { it.isNotBlank() && it != "about:blank" }
        }.getOrNull()
        val cookieCount = runCatching {
            jarStore.cookieManagerFor(currentJarKey.get())
                .getCookie(url)?.split(";")?.count { it.isNotBlank() }
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
                    // Guard the cleanup: after a renderer death (issue #210) this instance may be
                    // broken or already destroyed + replaced in the pool, so these ops can throw.
                    // The bookkeeping in the finally must still run so the permit is returned.
                    runCatching {
                        webView.stopLoading()
                        webView.clearHistory()
                        webView.clearCache(false)
                        webView.evaluateJavascript("document.open();document.close();", null)
                        webView.loadUrl("about:blank")
                    }
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
        runCatching { jarStore.cookieManagerFor(currentJarKey.get()).flush() }
        pool.forEach { it.destroy() }
        pool.clear()
        initialized = false
    }

    /**
     * Recover from a system-WebView renderer-process death (issue #210). [PhantomWebViewClient]
     * returns true from onRenderProcessGone (so Android never terminates the whole app process)
     * and routes the dead instance here. We destroy it and swap a freshly-created WebView into the
     * same pool slot (same tag), so the engine can keep running on the next acquire.
     *
     * Always invoked on the main thread — WebView callbacks are delivered on the creating thread,
     * which is also where [pool] is mutated in [initialize]/[acquire]/[destroy] — so the list swap
     * here cannot race those. A WebView whose renderer died but that is still acquired and in-flight
     * is destroyed here; the engine's current action on it fails and is caught by the engine's
     * per-action try/catch, and the subsequent [release] is defended with runCatching.
     */
    private fun handleRendererGone(dead: WebView) {
        val total = rendererDeaths.incrementAndGet()
        val tag = dead.tag as? String
        val index = pool.indexOfFirst { it === dead }
        if (index < 0) {
            // Already swapped out (e.g. a duplicate callback for the same instance). Just destroy.
            Timber.w("Renderer death for an already-replaced WebView (tag=$tag, total=$total)")
            runCatching { dead.destroy() }
            return
        }
        Timber.w("Rebuilding phantom WebView pool slot after renderer death (tag=$tag, total=$total)")
        val replacement = createWebView(tag = tag ?: "pool_$index")
        pool[index] = replacement
        runCatching { dead.destroy() }
    }

    private fun createWebView(tag: String): WebView {
        val webView = WebView(context)
        webView.tag = tag

        // Bind the persona's jar FIRST (issue #242). setProfile throws once the WebView has
        // navigated or run script, and a WebView can never be re-profiled, so construction is
        // the only moment this can happen. That is also why a jar change rebuilds the pool
        // instead of re-binding it in place. A failed or unsupported bind returns null and the
        // instance stays on the shared jar rather than the engine losing a WebView.
        val requestedJarKey = currentJarKey.get()
        val boundJarKey = requestedJarKey?.takeIf { jarStore.bind(webView, it) }

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

        // Enable third-party cookies for realistic tracker accumulation. This MUST be the
        // jar the WebView was just bound to: acceptance is configured per CookieManager, so
        // configuring the global one while the WebView reads a profile-scoped one would stop
        // accumulation entirely, with no crash and no log line to notice it by.
        val cookies = jarStore.cookieManagerFor(boundJarKey)
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(webView, true)

        val resourceCounter = AtomicInteger(0)
        resourceCounters[tag] = resourceCounter
        val loadError = AtomicReference<String?>(null)
        loadErrors[tag] = loadError
        webView.webViewClient = PhantomWebViewClient(
            blocklist,
            resourceCounter = resourceCounter,
            onRenderGone = ::handleRendererGone,
            deviceProvider = { currentDevice.get() },
            // Issue #268: record only the FIRST main-frame error of a load. A failed navigation can
            // emit several callbacks, and the first one is the one that describes what went wrong.
            onMainFrameError = { loadError.compareAndSet(null, it) },
        )
        webView.isClickable = false
        webView.isFocusable = false

        return webView
    }
}
