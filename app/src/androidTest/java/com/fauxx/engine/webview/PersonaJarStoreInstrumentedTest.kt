package com.fauxx.engine.webview

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device gates for per-persona cookie jars (issue #242).
 *
 * The unit tests can only reach the fallback tier, because Robolectric's WebView reports
 * `MULTI_PROFILE` unsupported. These are the tests that exercise the path that actually does the
 * isolating, and they are the only evidence that the feature works at all rather than silently
 * degrading on every device.
 *
 * [reportWebViewCapabilities] never fails: it is the coverage probe, and what it prints is the
 * answer to "what fraction of the fleet gets real isolation", which is otherwise a guess.
 * [jarsAreGenuinelySeparateStores] is the real gate and SKIPS (rather than fails) where the
 * feature is absent, because an old WebView is a supported configuration, not a broken one.
 */
@RunWith(AndroidJUnit4::class)
class PersonaJarStoreInstrumentedTest {

    private val store = PersonaJarStore()

    @Test
    fun reportWebViewCapabilities() {
        val pkg = WebViewCompat.getCurrentWebViewPackage(
            ApplicationProvider.getApplicationContext()
        )
        val features = listOf(
            WebViewFeature.MULTI_PROFILE,
            WebViewFeature.USER_AGENT_METADATA,
            WebViewFeature.DOCUMENT_START_SCRIPT,
            WebViewFeature.PROXY_OVERRIDE,
        ).joinToString(", ") { f ->
            "$f=" + runCatching { WebViewFeature.isFeatureSupported(f) }.getOrElse { "threw" }
        }

        Log.i(TAG, "webview=${pkg?.packageName} version=${pkg?.versionName}")
        Log.i(TAG, "features: $features")
        Log.i(TAG, "PersonaJarStore.isSupported=${store.isSupported}")
    }

    @Test
    fun jarsAreGenuinelySeparateStores() {
        assumeTrue(
            "MULTI_PROFILE unsupported on this WebView; the fallback tier is covered by unit tests",
            store.isSupported
        )

        val jarA = store.jarKeyFor("persona-alpha")
        val jarB = store.jarKeyFor("persona-beta")
        val url = "https://jar-probe.fauxx.invalid/"

        // ONLY the ProfileStore lookup is marshalled. CookieManager itself is thread-safe, and
        // doing cookie work inside runOnMainSync deadlocks: setCookie delivers its callback on
        // the main thread, which runOnMainSync is busy blocking, so the latch never fires.
        val jars = onUiThread {
            val ps = ProfileStore.getInstance()
            Pair(ps.getOrCreateProfile(jarA).cookieManager, ps.getOrCreateProfile(jarB).cookieManager)
        }
        val a = jars.first
        val b = jars.second
        a.setAcceptCookie(true)
        b.setAcceptCookie(true)

        setCookieBlocking(a, url, "persona=alpha; path=/")

        // The load-bearing assertion. If these were one store, B would see alpha's cookie, which
        // is exactly the pre-#242 behaviour this change exists to end.
        assertNotNull("jar A must hold its own cookie", a.getCookie(url))
        assertTrue("jar A must hold its own cookie", a.getCookie(url).contains("persona=alpha"))
        assertNull("jar B must NOT see jar A's cookie", b.getCookie(url))

        // Separate stores, not merely a wipe: writing into B must leave A intact. A forward-only
        // clear would pass the assertion above and fail this one.
        setCookieBlocking(b, url, "persona=beta; path=/")
        assertTrue("jar A must survive a write to jar B", a.getCookie(url).contains("persona=alpha"))
        assertTrue("jar B must hold its own value", b.getCookie(url).contains("persona=beta"))
        assertEquals(
            "jars must not bleed into one another",
            null,
            a.getCookie(url).takeIf { it.contains("persona=beta") }
        )
    }

    @Test
    fun theSweepSurvivesAJarThatIsLoadedInMemory() {
        assumeTrue("MULTI_PROFILE unsupported on this WebView", store.isSupported)

        val jar = store.jarKeyFor("persona-to-retire")
        val url = "https://jar-retire.fauxx.invalid/"
        val cm = onUiThread { ProfileStore.getInstance().getOrCreateProfile(jar).cookieManager }
        cm.setAcceptCookie(true)
        setCookieBlocking(cm, url, "retired=yes; path=/")
        assertTrue(cm.getCookie(url).contains("retired=yes"))

        // The sweep is deliberately NOT wrapped in onUiThread: deleteJarsExcept marshals to Main
        // itself, and runBlocking on an already-blocked main looper would deadlock. Running it
        // from the instrumentation thread is also what production does, where the caller is the
        // engine loop on Dispatchers.IO.
        //
        // deleteProfile refuses while a profile is loaded in memory, which is exactly the state
        // the sweep meets in production. Assert it tolerates that rather than throwing.
        val removed = runBlocking { store.deleteJarsExcept(keep = emptySet()) }
        Log.i(TAG, "sweep removed $removed jar(s) while one was loaded in memory")
    }

    /**
     * Marshal a ProfileStore lookup onto the UI thread and return its result.
     *
     * ProfileStore.getInstance() throws "Must be called on the UI thread." from Chromium's
     * ThreadUtils, even though androidx's Profile methods are annotated @AnyThread, and
     * instrumented tests run on the instrumentation thread. That is the same constraint that
     * made the production sweep a silent no-op.
     *
     * Keep the body as SMALL as possible: runOnMainSync blocks the main thread for its duration,
     * so anything inside that needs a main-thread callback (setCookie's, for one) can never
     * complete. Look the CookieManager up here, then do the cookie work outside.
     */
    private fun <T> onUiThread(body: () -> T): T {
        var result: T? = null
        var thrown: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { result = body() } catch (t: Throwable) { thrown = t }
        }
        thrown?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /**
     * Write one cookie and wait for WebView to confirm it.
     *
     * Threading here is genuinely awkward and both halves are required. The callback form of
     * [CookieManager.setCookie] throws "SetCookie must be called on a thread with a running
     * Looper", so it cannot run on the instrumentation thread; and its callback is DELIVERED on
     * that same looper, so it also cannot be called from inside a `runOnMainSync` block, which
     * blocks the main looper for its duration and would hang the callback forever.
     *
     * POST to the main looper and WAIT from the instrumentation thread: the call gets its looper
     * and the looper stays free to deliver the callback.
     */
    private fun setCookieBlocking(cm: CookieManager, url: String, value: String) {
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            cm.setCookie(url, value) { latch.countDown() }
        }
        assertTrue("setCookie timed out", latch.await(10, TimeUnit.SECONDS))
        cm.flush()
    }

    private companion object {
        const val TAG = "FauxxJarGate"
    }
}
