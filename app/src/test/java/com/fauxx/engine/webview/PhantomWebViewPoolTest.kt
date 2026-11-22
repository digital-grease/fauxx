package com.fauxx.engine.webview

import com.fauxx.data.model.PageRendering
import android.net.Uri
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fauxx.data.db.LogMetadata
import com.fauxx.data.crawllist.DomainBlocklist
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit-level lifecycle contract for [PhantomWebViewPool], the process's only WebView pool.
 *
 * The pool's suspend lifecycle ([initialize]/[acquire]/[release]/[destroy]) hops to
 * `Dispatchers.Main` internally because WebView is thread-affine to its creating (main) thread.
 * Under Robolectric the test runs on the main thread, so a `runBlocking { pool.initialize() }`
 * called inline would post the `Dispatchers.Main` body to the main looper and then block the
 * very thread that drains it — a self-deadlock. Two things avoid that:
 *   1. [ShadowLooper.idleMainLooperConstantly] makes the paused main looper auto-execute every
 *      task posted to it, even while the main thread sits idle.
 *   2. [drive] runs each suspend call inside `runBlocking` on a *background* thread, leaving the
 *      main thread free to service those posted `Dispatchers.Main` continuations.
 * This is deliberately NOT `runTest` + `MainDispatcherRule`: the pool needs the REAL Robolectric
 * Main looper to run its `withContext(Dispatchers.Main)` bodies, not a virtual test dispatcher.
 *
 * Scope note: WebSettings lockdown (allowFileAccess etc.) and `setDataDirectorySuffix` (finding
 * #4) are intentionally NOT covered here — they live in the instrumented
 * `PhantomWebViewPoolInstrumentedTest` / `FauxxApp`. This pins the pooling, permit, state-clear,
 * and teardown behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PhantomWebViewPoolTest {

    /** POOL_SIZE in [PhantomWebViewPool]; mirrored here so the assertions read intentionally. */
    private val poolSize = 2

    private lateinit var pool: PhantomWebViewPool

    /** Records what the pool asked for, so the tests can assert it asked at all. */
    private class RecordingIdentityProvider(var personaId: String?) : PhantomIdentityProvider {
        var activeQueries = 0
        override fun activePersonaId(): String? { activeQueries++; return personaId }
        override fun livePersonaIds(): Set<String> = setOfNotNull(personaId)
    }

    private lateinit var identity: RecordingIdentityProvider

    @Before
    fun setUp() {
        // A REAL PersonaJarStore, not a mock: under Robolectric the MULTI_PROFILE feature check
        // fails, so isSupported is false and the store returns the process-global jar. That makes
        // these tests exercise the unsupported-device fallback, which is the path every handset
        // with an older WebView APK takes.
        identity = RecordingIdentityProvider(personaId = "persona-under-test")
        pool = PhantomWebViewPool(
            RuntimeEnvironment.getApplication(),
            mockk<DomainBlocklist>(relaxed = true),
            PersonaJarStore(),
            identity,
        )
    }

    @Test
    fun `the pool resolves the persona jar itself, with no module involved`() {
        // The defect this pins: per-persona storage used to be bound by FingerprintModule, which
        // does not browse and which a user can switch off. The four modules that DO accumulate
        // tracker cookies would then keep crawling on one shared jar, isolation silently gone,
        // and because a disabled module is never dispatched it could not be fixed from inside it.
        // The pool must therefore ask for the identity on its own, without any module's help.
        drive { pool.initialize() }
        val afterInit = identity.activeQueries
        assertTrue(
            "initialize must resolve the jar before building, not after",
            afterInit > 0
        )

        val wv = drive { pool.acquire() }
        assertTrue(
            "every acquire must re-check the jar, so any module gets the right one",
            identity.activeQueries > afterInit
        )
        drive { pool.release(wv) }
    }

    @Test
    fun `a closed browsing gate refuses to hand out a WebView (#333)`() {
        var reason: String? = "strict DNS, not routed"
        pool = PhantomWebViewPool(
            RuntimeEnvironment.getApplication(),
            mockk<DomainBlocklist>(relaxed = true),
            PersonaJarStore(),
            identity,
            browsingGate = PhantomBrowsingGate { reason },
        )
        drive { pool.initialize() }

        // drive() rethrows a failure wrapped in an AssertionError.
        val refused = runCatching { drive { pool.acquire() } }.exceptionOrNull()
        assertTrue("got $refused", refused?.cause is BrowsingBlockedException)

        // Routing restored: browsing resumes on the next acquire, nothing to rebuild.
        reason = null
        val wv = drive { pool.acquire() }
        drive { pool.release(wv) }
    }

    @Test
    fun `the gate is checked again once the permit is held, and the permit is given back`() {
        // Routing can be lost during the jar swap or the permit wait (up to 30 s), after the first check.
        val checks = java.util.concurrent.atomic.AtomicInteger(0)
        val closeAfterFirst = java.util.concurrent.atomic.AtomicBoolean(true)
        pool = PhantomWebViewPool(
            RuntimeEnvironment.getApplication(),
            mockk<DomainBlocklist>(relaxed = true),
            PersonaJarStore(),
            identity,
            browsingGate = PhantomBrowsingGate {
                if (closeAfterFirst.get() && checks.incrementAndGet() > 1) "routing lost meanwhile" else null
            },
        )
        drive { pool.initialize() }

        val refused = runCatching { drive { pool.acquire() } }.exceptionOrNull()
        assertTrue("got $refused", refused?.cause is BrowsingBlockedException)

        // Both permits must still be there: a leaked one would make the second acquire time out.
        closeAfterFirst.set(false)
        val a = drive { pool.acquire() }
        val b = drive { pool.acquire() }
        drive { pool.release(a) }
        drive { pool.release(b) }
    }

    @Test
    fun `acquire applies the image setting, and a change takes effect on the next acquire`() {
        var loadImages = false
        pool = PhantomWebViewPool(
            RuntimeEnvironment.getApplication(),
            mockk<DomainBlocklist>(relaxed = true),
            PersonaJarStore(),
            identity,
            object : PhantomBrowsingPrefs {
                override fun loadImages(): Boolean = loadImages
            },
        )
        drive { pool.initialize() }

        var wv = drive { pool.acquire() }
        assertTrue("images are skipped by default", wv.settings.blockNetworkImage)
        assertFalse(wv.settings.loadsImagesAutomatically)
        drive { pool.release(wv) }

        // No pool rebuild: the next acquire picks the new value up.
        loadImages = true
        wv = drive { pool.acquire() }
        assertFalse("images load once the user turns them on", wv.settings.blockNetworkImage)
        assertTrue(wv.settings.loadsImagesAutomatically)
        drive { pool.release(wv) }
    }

    /** Records which WebViews each fake on-screen host is asked to show and hide. */
    private class RecordingHosts(private val failing: Boolean = false) : OnscreenHostFactory {
        val created = mutableListOf<Pair<Int, Int>>()
        val shown = mutableListOf<WebView>()
        val hidden = mutableListOf<WebView>()
        var destroyed = 0

        override fun create(width: Int, height: Int): OnscreenHost {
            created += width to height
            return object : OnscreenHost {
                override fun show(webView: WebView) {
                    if (failing) throw IllegalStateException("no virtual display here")
                    shown += webView
                }
                override fun hide(webView: WebView) { hidden += webView }
                override fun destroy() { destroyed++ }
            }
        }
    }

    private fun renderingPool(hosts: OnscreenHostFactory, mode: () -> PageRendering) = PhantomWebViewPool(
        RuntimeEnvironment.getApplication(),
        mockk<DomainBlocklist>(relaxed = true),
        PersonaJarStore(),
        identity,
        object : PhantomBrowsingPrefs {
            override fun loadImages(): Boolean = false
            override fun pageRendering(): PageRendering = mode()
        },
        onscreenHosts = hosts,
    )

    @Test
    fun `with every page drawn, an acquired WebView goes on screen and stays there`() {
        val hosts = RecordingHosts()
        pool = renderingPool(hosts) { PageRendering.ALL_PAGES }
        drive { pool.initialize() }

        val first = drive { pool.acquire() }
        drive { pool.release(first) }
        val again = drive { pool.acquire() }

        assertTrue("the acquired WebView is drawn", first in hosts.shown)
        assertTrue("its display has a real size", hosts.created.all { (w, h) -> w > 0 && h > 0 })
        assertTrue("release does not take it off", first !in hosts.hidden)
        drive { pool.release(again) }
        drive { pool.destroy() }
        assertTrue("destroy releases the displays", hosts.destroyed == hosts.created.size)
    }

    @Test
    fun `with drawing off, nothing goes on screen`() {
        val hosts = RecordingHosts()
        pool = renderingPool(hosts) { PageRendering.OFF }
        drive { pool.initialize() }
        val wv = drive { pool.acquire() }
        drive { pool.startingSearch(wv) }
        drive { pool.release(wv) }
        assertTrue(hosts.created.isEmpty())
    }

    @Test
    fun `with searches only, a search is drawn and its WebView is replaced afterwards`() {
        val hosts = RecordingHosts()
        pool = renderingPool(hosts) { PageRendering.SEARCH_ONLY }
        drive { pool.initialize() }

        val search = drive { pool.acquire() }
        assertTrue("not drawn before the search starts", hosts.shown.isEmpty())
        drive { pool.startingSearch(search) }
        assertEquals(listOf(search), hosts.shown)
        drive { pool.release(search) }

        // A WebView taken off its display reports its page hidden, so it is never handed out again.
        val next = drive { pool.acquire() }
        val other = drive { pool.acquire() }
        assertTrue("the drawn WebView is gone from the pool", next !== search && other !== search)
        assertTrue("and was taken off its display before being destroyed", search in hosts.hidden)
        drive { pool.release(next) }
        drive { pool.release(other) }
    }

    @Test
    fun `switching drawing off replaces a WebView that was drawn`() {
        val hosts = RecordingHosts()
        var mode = PageRendering.ALL_PAGES
        pool = renderingPool(hosts) { mode }
        drive { pool.initialize() }
        val a = drive { pool.acquire() }
        val b = drive { pool.acquire() }
        drive { pool.release(a) }
        drive { pool.release(b) }

        mode = PageRendering.OFF
        val c = drive { pool.acquire() }
        val d = drive { pool.acquire() }
        assertTrue("both slots got fresh WebViews", setOf(c, d).none { it === a || it === b })
        assertEquals("nothing new was drawn", 2, hosts.shown.size)
        drive { pool.release(c) }
        drive { pool.release(d) }
    }

    @Test
    fun `a device that cannot draw pages leaves them detached and stops trying`() {
        val hosts = RecordingHosts(failing = true)
        pool = renderingPool(hosts) { PageRendering.ALL_PAGES }
        drive { pool.initialize() }
        repeat(3) {
            val wv = drive { pool.acquire() }
            assertNotNull(wv)
            drive { pool.release(wv) }
        }
        assertEquals("one failed attempt, then no more", 1, hosts.created.size)
    }

    @Test
    fun `no active persona leaves the pool usable on the shared jar`() {
        // Layer 3 off, or no persona generated yet. Browsing must still work.
        identity.personaId = null
        drive { pool.initialize() }
        val wv = drive { pool.acquire() }
        assertNotNull("pool must still hand out a WebView with no persona", wv)
        drive { pool.release(wv) }
    }

    @Test
    fun `initialize creates POOL_SIZE webviews and is idempotent`() {
        drive { pool.initialize() }
        // A second initialize must be a no-op; if it created more webviews, the pool would hold
        // duplicate-tagged instances and the permit accounting would drift.
        drive { pool.initialize() }

        val first = drive { pool.acquire() }
        val second = drive { pool.acquire() }

        val tags = setOf(first.tag as String, second.tag as String)
        assertEquals("pool must hand out exactly the $poolSize distinct pooled tags", setOf("pool_0", "pool_1"), tags)
    }

    @Test
    fun `acquire then release leaves both permits free and clears acquired state`() {
        drive { pool.initialize() }

        // Full acquire/release cycle on both slots.
        val a = drive { pool.acquire() }
        val b = drive { pool.acquire() }
        drive { pool.release(a) }
        drive { pool.release(b) }

        // If release didn't free the permits and clear `acquired`, these two back-to-back acquires
        // would either block (no permits) or fail to find an un-acquired tag.
        val again1 = drive { pool.acquire() }
        val again2 = drive { pool.acquire() }
        val tags = setOf(again1.tag as String, again2.tag as String)
        assertEquals("after a full cycle both pooled tags must be acquirable again", setOf("pool_0", "pool_1"), tags)
    }

    @Test
    fun `semaphore hands out at most POOL_SIZE distinct webviews`() {
        drive { pool.initialize() }

        val first = drive { pool.acquire() }
        val second = drive { pool.acquire() }

        assertFalse("two acquires must return distinct webview instances", first === second)
        assertEquals(
            "two acquires must return the two distinct pooled tags",
            setOf("pool_0", "pool_1"),
            setOf(first.tag as String, second.tag as String),
        )
    }

    @Test
    fun `acquire blocks a third caller until a release frees a permit`() {
        drive { pool.initialize() }

        // Take both permits; the pool is now exhausted.
        val a = drive { pool.acquire() }
        drive { pool.acquire() }

        // Semaphore.acquire() blocks the CALLING thread, so the 3rd acquire must run on its own
        // thread or it would block the test forever. There is NO tryAcquire/timeout API on the
        // pool, so we assert via a bounded negative: it has not completed while the pool is full.
        val done = CountDownLatch(1)
        val acquired = AtomicReference<WebView?>(null)
        val failure = AtomicReference<Throwable?>(null)
        Thread {
            try {
                acquired.set(runBlocking { pool.acquire() })
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                done.countDown()
            }
        }.apply { isDaemon = true; start() }

        // With both permits held, the third acquire must NOT complete within a short bound.
        assertFalse(
            "a 3rd acquire must block while the pool is full",
            awaitPumping(done, 200),
        )
        assertNull("the blocked acquire must not have produced a webview yet", acquired.get())

        // Freeing one permit must unblock the waiter.
        drive { pool.release(a) }

        assertTrue(
            "the 3rd acquire must complete once a permit is released",
            awaitPumping(done, 5_000),
        )
        failure.get()?.let { throw AssertionError("blocked acquire threw instead of completing", it) }
        assertEquals(
            "the unblocked acquire must receive the just-released slot",
            "pool_0",
            acquired.get()?.tag as String?,
        )
    }

    @Test
    fun `release of a webview with a null tag is a no-op`() {
        drive { pool.initialize() }

        // A foreign WebView whose tag is null must short-circuit before touching permits/state.
        val foreign = mockk<WebView>(relaxed = true)
        every { foreign.tag } returns null

        drive { pool.release(foreign) }

        // Permits/state unchanged: both real pooled slots are still acquirable.
        val first = drive { pool.acquire() }
        val second = drive { pool.acquire() }
        val tags = setOf(first.tag as String, second.tag as String)
        assertEquals("a null-tag release must not alter the pool", setOf("pool_0", "pool_1"), tags)
    }

    @Test
    fun `tracker counts reach the action-log metadata, then the verdict on release (#341)`() {
        val reachability = TrackerReachability(
            windowSize = 10, minAttempts = 1, minVisits = 1, flagAt = 0.6, clearBelow = 0.3,
        )
        pool = PhantomWebViewPool(
            RuntimeEnvironment.getApplication(),
            mockk<DomainBlocklist>(relaxed = true),
            PersonaJarStore(),
            identity,
            trackerHosts = TrackerHosts { setOf("doubleclick.net") },
            trackerReachability = reachability,
        )
        drive { pool.initialize() }

        val wv = drive { pool.acquire() }
        val client = shadowOf(wv).webViewClient
        val reached = trackerRequest("https://doubleclick.net/a.js")
        val blocked = trackerRequest("https://doubleclick.net/b.js")
        client.shouldInterceptRequest(wv, reached)
        client.shouldInterceptRequest(wv, blocked)
        client.onReceivedError(wv, blocked, hostLookupError())

        val metadata = LogMetadata.parse(pool.captureMetadata(wv, "https://news.example/")).toMap()
        assertEquals("2", metadata[LogMetadata.TRACKER_REQUESTS])
        assertEquals("1", metadata[LogMetadata.TRACKER_REQUESTS_BLOCKED])
        assertEquals(
            "nothing is judged until the visit is over",
            TrackerReachabilityState.NOT_ENOUGH_DATA, reachability.state.value,
        )

        drive { pool.release(wv) }
        assertEquals("1 of 2 blocked is under the flag", TrackerReachabilityState.OK, reachability.state.value)

        // The next visit starts from zero, and one that made no tracker requests shows no tracker rows.
        val next = drive { pool.acquire() }
        try {
            val nextMetadata = LogMetadata.parse(pool.captureMetadata(next, "https://news.example/")).toMap()
            assertNull(nextMetadata[LogMetadata.TRACKER_REQUESTS])
            assertNull(nextMetadata[LogMetadata.TRACKER_REQUESTS_BLOCKED])
        } finally {
            drive { pool.release(next) }
        }
    }

    @Test
    fun `tracker image requests follow the image setting (#341)`() {
        var loadImages = false
        pool = PhantomWebViewPool(
            RuntimeEnvironment.getApplication(),
            mockk<DomainBlocklist>(relaxed = true),
            PersonaJarStore(),
            identity,
            object : PhantomBrowsingPrefs {
                override fun loadImages(): Boolean = loadImages
            },
            trackerHosts = TrackerHosts { setOf("doubleclick.net") },
        )
        drive { pool.initialize() }
        val pixel = trackerRequest("https://doubleclick.net/px.gif", accept = "image/webp,*/*")

        var wv = drive { pool.acquire() }
        shadowOf(wv).webViewClient.shouldInterceptRequest(wv, pixel)
        assertNull(
            "an image WebView will not fetch is not an attempt",
            LogMetadata.parse(pool.captureMetadata(wv, "https://news.example/")).toMap()[LogMetadata.TRACKER_REQUESTS],
        )
        drive { pool.release(wv) }

        loadImages = true
        wv = drive { pool.acquire() }
        shadowOf(wv).webViewClient.shouldInterceptRequest(wv, pixel)
        assertEquals(
            "1",
            LogMetadata.parse(pool.captureMetadata(wv, "https://news.example/")).toMap()[LogMetadata.TRACKER_REQUESTS],
        )
        drive { pool.release(wv) }
    }

    private fun trackerRequest(url: String, accept: String = "*/*"): WebResourceRequest {
        val req: WebResourceRequest = mockk()
        every { req.url } returns Uri.parse(url)
        every { req.requestHeaders } returns mapOf("Accept" to accept)
        every { req.isForMainFrame } returns false
        return req
    }

    private fun hostLookupError(): WebResourceError {
        val err: WebResourceError = mockk()
        every { err.errorCode } returns WebViewClient.ERROR_HOST_LOOKUP
        every { err.description } returns "net::ERR_NAME_NOT_RESOLVED"
        return err
    }

    @Test
    fun `release clears per-webview browsing state`() {
        drive { pool.initialize() }

        val wv = drive { pool.acquire() }
        drive { pool.release(wv) }

        val shadow = shadowOf(wv)
        assertTrue("release must clear history", shadow.wasClearHistoryCalled())
        assertTrue("release must clear cache", shadow.wasClearCacheCalled())
        assertEquals("release must blank the page", "about:blank", shadow.lastLoadedUrl)
        assertTrue(
            "release must reset the document via injected JS, got '${shadow.lastEvaluatedJavascript}'",
            shadow.lastEvaluatedJavascript?.contains("document.open") == true,
        )
    }

    @Test
    fun `destroy flushes cookies tears down all webviews and resets initialized`() {
        drive { pool.initialize() }
        val a = drive { pool.acquire() }
        val b = drive { pool.acquire() }
        val shadowA = shadowOf(a)
        val shadowB = shadowOf(b)
        // Release so destroy doesn't leave the semaphore in a partially-acquired state; destroy()
        // itself does not touch permits, but a clean cycle keeps the re-initialize assertion honest.
        drive { pool.release(a) }
        drive { pool.release(b) }

        drive { pool.destroy() }

        assertTrue("destroy must tear down pooled webview pool_0", shadowA.wasDestroyCalled())
        assertTrue("destroy must tear down pooled webview pool_1", shadowB.wasDestroyCalled())

        // initialized must be reset: re-initialize then acquire two distinct slots again.
        drive { pool.initialize() }
        val again1 = drive { pool.acquire() }
        val again2 = drive { pool.acquire() }
        val tags = setOf(again1.tag as String, again2.tag as String)
        assertEquals("destroy must reset state so the pool can be re-initialized", setOf("pool_0", "pool_1"), tags)
    }

    /**
     * Runs [block] inside `runBlocking` on a daemon background thread so the pool's
     * `withContext(Dispatchers.Main)` bodies can be serviced by the (constantly-idled) main
     * looper on the test thread. This also covers [PhantomWebViewPool.destroy]'s documented
     * "never call from runBlocking on the main thread" constraint — here it runs off-main.
     */
    private fun <T> drive(block: suspend () -> T): T {
        val result = AtomicReference<T>()
        val error = AtomicReference<Throwable?>(null)
        val latch = CountDownLatch(1)
        Thread {
            try {
                result.set(runBlocking { block() })
            } catch (t: Throwable) {
                error.set(t)
            } finally {
                latch.countDown()
            }
        }.apply { isDaemon = true; start() }
        assertTrue("suspend lifecycle call did not complete within bound", awaitPumping(latch, 10_000))
        error.get()?.let { throw AssertionError("suspend lifecycle call threw", it) }
        return result.get()
    }

    /**
     * Awaits [latch] up to [timeoutMs], repeatedly draining the (PAUSED) main looper so the
     * background thread's `withContext(Dispatchers.Main)` continuations actually run. Robolectric's
     * default PAUSED looper does not auto-execute posted tasks, and `idleMainLooperConstantly` is
     * unsupported in PAUSED mode, so the test thread pumps the looper explicitly while it waits.
     */
    private fun awaitPumping(latch: CountDownLatch, timeoutMs: Long): Boolean {
        val mainLooper = shadowOf(Looper.getMainLooper())
        var waited = 0L
        while (waited < timeoutMs) {
            if (latch.await(5, TimeUnit.MILLISECONDS)) return true
            mainLooper.idle()
            waited += 5
        }
        return latch.count == 0L
    }
}
