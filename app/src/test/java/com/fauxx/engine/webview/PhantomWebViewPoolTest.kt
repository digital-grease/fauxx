package com.fauxx.engine.webview

import android.os.Looper
import android.webkit.WebView
import com.fauxx.data.crawllist.DomainBlocklist
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Before
    fun setUp() {
        pool = PhantomWebViewPool(
            RuntimeEnvironment.getApplication(),
            mockk<DomainBlocklist>(relaxed = true),
        )
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
