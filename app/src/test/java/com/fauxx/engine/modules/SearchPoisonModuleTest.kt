package com.fauxx.engine.modules

import android.webkit.WebView
import com.fauxx.data.crawllist.DomainBlocklist
import com.fauxx.data.model.ActionType
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.data.querybank.GrammarQueryGenerator
import com.fauxx.data.querybank.QueryBankManager
import com.fauxx.data.querybank.QueryBlocklist
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.webview.PhantomWebViewPool
import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
import com.fauxx.network.UserAgentPool
import com.fauxx.support.MainDispatcherRule
import com.fauxx.targeting.layer1.CustomInterestMapper
import com.fauxx.targeting.layer1.DemographicProfileDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Chokepoint #4: final dispatch gate in [SearchPoisonModule.onAction], plus the WebView-routing
 * transport behavior introduced in M1 (#168/#169 — search now runs through the real Chromium
 * stack rather than OkHttp).
 *
 * Safety invariant: a query that matches [QueryBlocklist] (or is blank) must be DROPPED, never
 * dispatched. A dropped action is cheaper than a false user-signal (e.g. a 988 crisis-line query
 * that could trigger a welfare check). After M1 "dispatched" means "loaded in a phantom WebView",
 * so the gate is verified by asserting the WebView pool is never acquired for a blocked query.
 *
 * The "a real request leaves the process" guarantee (previously the OkHttp MockWebServer wire
 * test) now lives in the documented JA3/JA4 + H2 fingerprint-capture procedure, since no JVM mock
 * can observe the TLS handshake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SearchPoisonModuleTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    private val queryBankManager: QueryBankManager = mockk(relaxed = true)
    private val grammarGenerator: GrammarQueryGenerator = mockk(relaxed = true)
    private val profileRepo: PoisonProfileRepository = mockk(relaxed = true) {
        every { getProfile().intensity } returns com.fauxx.data.model.IntensityLevel.MEDIUM
        every { getProfile().mobileIntensity } returns null
    }
    private val webViewPool: PhantomWebViewPool = mockk(relaxed = true)
    private val userAgentPool: UserAgentPool = mockk(relaxed = true)
    private val blocklist: DomainBlocklist = mockk(relaxed = true)
    private val demographicDao: DemographicProfileDao = mockk(relaxed = true)
    private val customInterestMapper: CustomInterestMapper = mockk(relaxed = true)
    private val queryBlocklist: QueryBlocklist = mockk(relaxed = true)
    private val localeManager: LocaleManager = mockk(relaxed = true)
    private val crawlListManager: com.fauxx.data.crawllist.CrawlListManager = mockk(relaxed = true)
    private val webView: WebView = mockk(relaxed = true)

    private fun newModule(
        random: kotlin.random.Random = bankBranchRandom,
        clock: com.fauxx.util.Clock = com.fauxx.support.FakeClock(0L),
    ) = SearchPoisonModule(
        queryBankManager = queryBankManager,
        grammarGenerator = grammarGenerator,
        profileRepo = profileRepo,
        webViewPool = webViewPool,
        userAgentPool = userAgentPool,
        blocklist = blocklist,
        demographicDao = demographicDao,
        customInterestMapper = customInterestMapper,
        queryBlocklist = queryBlocklist,
        localeManager = localeManager,
        crawlListManager = crawlListManager,
        clock = clock,
        // nextFloat()=0.99 forces the query-bank branch (never calls grammarGenerator.generate);
        // nextBits()=0 makes SEARCH_ENGINES.random(this) deterministic (first engine = google)
        // and the Accept-Language / dwell draws deterministic.
        random = random,
    )

    private val bankBranchRandom = object : kotlin.random.Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextFloat(): Float = 0.99f
    }

    /**
     * Maximizes every bounded draw: bank branch (nextFloat=0.99), LAST search engine
     * (yandex), MAX refinement/link counts, identity shuffle, no reformulation —
     * exercises the multi-step session shape FixedRandom-style zeros collapse away.
     */
    private val maxRandom = object : kotlin.random.Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextFloat(): Float = 0.99f
        override fun nextInt(until: Int): Int = until - 1
        override fun nextInt(from: Int, until: Int): Int = until - 1
        override fun nextLong(from: Long, until: Long): Long = from
    }

    private fun bankReturns(query: String) {
        every { queryBankManager.randomQuery(any()) } returns query
    }

    // (a) Blocklisted query -> [BLOCKED] drop, SEARCH_QUERY type, WebView pool NEVER acquired.
    @Test
    fun `blocklisted query is dropped and never dispatched`() = runTest(testDispatcher) {
        bankReturns("how to make a pipe bomb")
        every { queryBlocklist.isBlocked(any()) } returns true
        every { localeManager.currentLocale } returns SupportedLocale.EN

        val result = newModule().onAction(CategoryPool.GAMING)

        assertEquals(ActionType.SEARCH_QUERY, result.actionType)
        assertTrue(
            "detail must be flagged [BLOCKED]; was: ${result.detail}",
            result.detail.startsWith("[BLOCKED]")
        )
        // The safety invariant: a blocked query must never reach the WebView.
        coVerify(exactly = 0) { webViewPool.acquire() }
    }

    // (b) Blank generated query -> same [BLOCKED] drop, no dispatch.
    @Test
    fun `blank query is dropped and never dispatched`() = runTest(testDispatcher) {
        bankReturns("")
        every { queryBlocklist.isBlocked(any()) } returns false
        every { localeManager.currentLocale } returns SupportedLocale.EN

        val result = newModule().onAction(CategoryPool.GAMING)

        assertEquals(ActionType.SEARCH_QUERY, result.actionType)
        assertTrue(result.detail.startsWith("[BLOCKED]"))
        coVerify(exactly = 0) { webViewPool.acquire() }
    }

    // (c) Safe query -> loaded once in the WebView with a coherent, locale-matched header set.
    @Test
    fun `safe query loads once through the WebView with GPC and locale headers`() = runTest(testDispatcher) {
        val safeQuery = "best mechanical keyboards 2026"
        bankReturns(safeQuery)
        every { queryBlocklist.isBlocked(any()) } returns false
        every { blocklist.isUrlBlocked(any()) } returns false
        every { localeManager.currentLocale } returns SupportedLocale.EN
        coEvery { webViewPool.acquire() } returns webView

        val urlSlot = slot<String>()
        val headerSlot = slot<Map<String, String>>()
        every { webView.loadUrl(capture(urlSlot), capture(headerSlot)) } returns Unit

        val result = newModule().onAction(CategoryPool.GAMING)

        coVerify(exactly = 1) { webViewPool.acquire() }
        coVerify(exactly = 1) { webViewPool.release(webView) }
        assertTrue(
            "must load the google SERP URL with the encoded query; was: ${urlSlot.captured}",
            urlSlot.captured.contains("www.google.com/search") &&
                urlSlot.captured.contains("best+mechanical+keyboards+2026")
        )
        assertEquals("Sec-GPC opt-out must ride along", "1", headerSlot.captured["Sec-GPC"])
        assertTrue(
            "Accept-Language must be EN-coherent; was: ${headerSlot.captured["Accept-Language"]}",
            headerSlot.captured["Accept-Language"]?.startsWith("en") == true
        )
        assertEquals(ActionType.SEARCH_QUERY, result.actionType)
        assertTrue(result.detail.contains(safeQuery))
        assertTrue(result.detail.contains(" via "))
        assertTrue("a normal load reports success", result.success)
    }

    // (d) WebView acquire failure -> success=false, but NOT a [BLOCKED] safety drop.
    @Test
    fun `webview failure is a soft failure not a safety drop`() = runTest(testDispatcher) {
        bankReturns("best mechanical keyboards 2026")
        every { queryBlocklist.isBlocked(any()) } returns false
        every { blocklist.isUrlBlocked(any()) } returns false
        every { localeManager.currentLocale } returns SupportedLocale.EN
        coEvery { webViewPool.acquire() } throws RuntimeException("pool exhausted")

        val result = newModule().onAction(CategoryPool.GAMING)

        assertEquals(ActionType.SEARCH_QUERY, result.actionType)
        assertFalse(
            "a transport failure must NOT become a [BLOCKED] drop; was: ${result.detail}",
            result.detail.startsWith("[BLOCKED]")
        )
        assertFalse("a failed load reports failure", result.success)
    }

    // ---- E5 (#175): intent-chain sessions ----

    /** All loadUrl calls with their headers, in order. */
    private fun captureLoads(): Pair<MutableList<String>, MutableList<Map<String, String>>> {
        val urls = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()
        every { webView.loadUrl(capture(urls), capture(headers)) } returns Unit
        return urls to headers
    }

    private fun safeSession(
        query: String,
        intensity: com.fauxx.data.model.IntensityLevel = com.fauxx.data.model.IntensityLevel.MEDIUM,
        mobileIntensity: com.fauxx.data.model.IntensityLevel? = null,
    ) {
        bankReturns(query)
        every { queryBlocklist.isBlocked(any()) } returns false
        every { blocklist.isUrlBlocked(any()) } returns false
        every { localeManager.currentLocale } returns SupportedLocale.EN
        every { profileRepo.getProfile().intensity } returns intensity
        every { profileRepo.getProfile().mobileIntensity } returns mobileIntensity
        coEvery { webViewPool.acquire() } returns webView
    }

    /** Stubs evaluateJavascript: the harvest JS yields [hrefs]; click JS reports clicked. */
    private fun stubSerp(hrefs: List<String>): MutableList<String> {
        val scripts = mutableListOf<String>()
        val outer = com.google.gson.Gson().toJson(com.google.gson.Gson().toJson(hrefs))
        every { webView.evaluateJavascript(capture(scripts), any()) } answers {
            val js = firstArg<String>()
            val cb = secondArg<android.webkit.ValueCallback<String>>()
            if (js.contains("JSON.stringify")) cb.onReceiveValue(outer)
            else cb.onReceiveValue("\"clicked\"")
        }
        return scripts
    }

    // (f) A session issues the goal plus related refinements, each gated individually.
    @Test
    fun `session dispatches goal plus in-topic refinements - all gated`() = runTest(testDispatcher) {
        val goal = "best mechanical keyboards 2026"
        safeSession(goal)
        val (urls, _) = captureLoads()

        val result = newModule().onAction(CategoryPool.GAMING)

        assertTrue("session must dispatch more than the goal", urls.size >= 2)
        val encodedGoal = java.net.URLEncoder.encode(goal, "UTF-8")
        urls.forEach { url ->
            assertTrue(
                "every session query must stay on the chosen SERP and contain the goal; was: $url",
                url.contains("www.google.com/search") && url.contains(encodedGoal)
            )
        }
        // Each query text (goal + every refinement) hit the final dispatch gate.
        verify(exactly = urls.size) { queryBlocklist.isBlocked(any()) }
        // Single action log per session; detail carries only the goal, in the EXACT
        // shape LogScrubber's export coarsening parses — separator drift would leak
        // query text un-coarsened into exports.
        assertTrue(result.detail.contains(goal))
        assertTrue(
            "detail must match the scrubber contract; was: ${result.detail}",
            Regex("""^\[GAMING] .+ · via \S+$""").matches(result.detail)
        )
        assertTrue(result.success)
        coVerify(exactly = 1) { webViewPool.acquire() }
        coVerify(exactly = 1) { webViewPool.release(webView) }
    }

    // (g) A blocked refinement is skipped without aborting the session.
    @Test
    fun `blocked refinement is skipped while the session continues`() = runTest(testDispatcher) {
        val goal = "best mechanical keyboards 2026"
        safeSession(goal)
        // Goal passes; anything longer (every refinement wraps the goal) is blocked.
        every { queryBlocklist.isBlocked(any()) } answers { firstArg<String>() != goal }
        val (urls, _) = captureLoads()

        val result = newModule().onAction(CategoryPool.GAMING)

        assertEquals("only the goal may be dispatched", 1, urls.size)
        assertFalse(result.detail.startsWith("[BLOCKED]"))
        assertTrue("goal already dispatched; session reports success", result.success)
    }

    // (h) Results are CLICKED via the SERP's own anchor: only curated hosts, never
    // engine-internal or uncurated ones. The allow-list is the diff's primary safety
    // gate for the new navigate-to-live-results capability.
    @Test
    fun `session clicks only curated organic results`() = runTest(testDispatcher) {
        safeSession("best mechanical keyboards 2026")
        every { crawlListManager.isCuratedHost(any()) } answers {
            firstArg<String>().endsWith("example.com")
        }
        val scripts = stubSerp(
            listOf(
                "https://www.google.com/preferences",
                "https://reviews.example.com/kb",
                "https://random-uncurated.net/page",
            )
        )
        captureLoads()

        newModule().onAction(CategoryPool.GAMING)

        val clicks = scripts.filter { it.contains(".click()") }
        assertTrue(
            "the curated result must be clicked; clicks: $clicks",
            clicks.any { it.contains("reviews.example.com/kb") }
        )
        assertTrue(
            "engine-internal links must never be clicked",
            clicks.none { it.contains("google.com/preferences") }
        )
        assertTrue(
            "uncurated hosts must never be clicked — the allow-list is the primary gate",
            clicks.none { it.contains("random-uncurated.net") }
        )
    }

    // (h2) Module-level wiring of the denylist on followed links.
    @Test
    fun `denylisted link is never clicked even when curated`() = runTest(testDispatcher) {
        safeSession("best mechanical keyboards 2026")
        every { crawlListManager.isCuratedHost(any()) } returns true
        every { blocklist.isUrlBlocked(any()) } answers {
            firstArg<String>().contains("blocked-host.example.com")
        }
        val scripts = stubSerp(
            listOf(
                "https://blocked-host.example.com/x",
                "https://fine.example.org/y",
            )
        )
        captureLoads()

        newModule().onAction(CategoryPool.GAMING)

        val clicks = scripts.filter { it.contains(".click()") }
        assertTrue(clicks.none { it.contains("blocked-host.example.com") })
        assertTrue(clicks.any { it.contains("fine.example.org") })
    }

    // (h3) Multi-step session: max draws exercise 3 refinements on the last engine.
    @Test
    fun `full session shape - three gated refinements then a click`() = runTest(testDispatcher) {
        val goal = "best mechanical keyboards 2026"
        safeSession(goal)
        every { crawlListManager.isCuratedHost(any()) } returns true
        val scripts = stubSerp(listOf("https://reviews.example.com/kb"))
        val (urls, _) = captureLoads()

        newModule(random = maxRandom).onAction(CategoryPool.GAMING)

        assertEquals("goal + 3 refinements", 4, urls.size)
        assertTrue("maxRandom picks the LAST engine", urls.all { it.contains("yandex.com") })
        // Every refinement text individually hit the dispatch gate.
        verify { queryBlocklist.isBlocked(goal) }
        verify { queryBlocklist.isBlocked("best $goal") }
        verify { queryBlocklist.isBlocked("$goal reviews") }
        verify { queryBlocklist.isBlocked("$goal price") }
        assertTrue(scripts.any { it.contains(".click()") })
    }

    // (h4) A blocked refinement mid-chain is skipped; the REST of the chain continues.
    @Test
    fun `blocked refinement mid-chain does not abort the rest of the session`() = runTest(testDispatcher) {
        val goal = "best mechanical keyboards 2026"
        safeSession(goal)
        every { queryBlocklist.isBlocked(any()) } answers {
            firstArg<String>().contains("price")
        }
        val (urls, _) = captureLoads()

        val result = newModule(random = maxRandom).onAction(CategoryPool.GAMING)

        assertEquals("goal + 2 surviving refinements", 3, urls.size)
        assertTrue(result.success)
    }

    // (i) EXTREME intensity degrades to the pre-E5 single query (issue #76 honesty).
    @Test
    fun `extreme intensity collapses the session to a single query`() = runTest(testDispatcher) {
        safeSession(
            "best mechanical keyboards 2026",
            intensity = com.fauxx.data.model.IntensityLevel.EXTREME
        )
        val (urls, _) = captureLoads()

        newModule().onAction(CategoryPool.GAMING)

        assertEquals(1, urls.size)
        verify(exactly = 0) { webView.evaluateJavascript(any(), any()) }
    }

    // (i2) The HIGHEST configured tier governs, not just Wi-Fi: LOW Wi-Fi + EXTREME
    // mobile must size the session for EXTREME (rate honesty on cellular).
    @Test
    fun `mobile tier participates in the session-size ceiling`() = runTest(testDispatcher) {
        safeSession(
            "best mechanical keyboards 2026",
            intensity = com.fauxx.data.model.IntensityLevel.LOW,
            mobileIntensity = com.fauxx.data.model.IntensityLevel.EXTREME
        )
        val (urls, _) = captureLoads()

        newModule().onAction(CategoryPool.GAMING)

        assertEquals(1, urls.size)
    }

    // (i3) The wall-clock budget trims the session once exhausted.
    @Test
    fun `exhausted session budget trims refinements and links`() = runTest(testDispatcher) {
        safeSession("best mechanical keyboards 2026")
        val tiredClock = mockk<com.fauxx.util.Clock> {
            // First read computes the deadline; every later read is past it.
            every { currentTimeMillis() } returnsMany listOf(0L, 100_000L)
        }
        val (urls, _) = captureLoads()

        val result = newModule(clock = tiredClock).onAction(CategoryPool.GAMING)

        assertEquals("only the goal fits in an exhausted budget", 1, urls.size)
        assertTrue(result.success)
        verify(exactly = 0) { webView.evaluateJavascript(any(), any()) }
    }

    // (j) Session bounds per intensity tier (keyed to the enum, not magic numbers).
    @Test
    fun `session bounds scale down with intensity`() {
        assertEquals(2 to 1, SearchPoisonModule.sessionBounds(com.fauxx.data.model.IntensityLevel.LOW.actionsPerHour))
        assertEquals(3 to 2, SearchPoisonModule.sessionBounds(com.fauxx.data.model.IntensityLevel.MEDIUM.actionsPerHour))
        assertEquals(1 to 1, SearchPoisonModule.sessionBounds(com.fauxx.data.model.IntensityLevel.HIGH.actionsPerHour))
        assertEquals(0 to 0, SearchPoisonModule.sessionBounds(com.fauxx.data.model.IntensityLevel.EXTREME.actionsPerHour))
    }

    // (e) Toggle-decoupling: start() seeds an Android-Chromium UA even if FingerprintModule is off.
    @Test
    fun `start seeds a chromium-android UA independent of FingerprintModule`() = runTest(testDispatcher) {
        val chromeUa = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36"
        every { userAgentPool.randomChromiumAndroid() } returns chromeUa
        coEvery { demographicDao.get() } returns null

        newModule().start()

        verify(exactly = 1) { webViewPool.setUserAgentIfUnset(chromeUa) }
    }
}
