package com.fauxx.engine.modules

import android.webkit.WebView
import com.fauxx.data.crawllist.DomainBlocklist
import com.fauxx.data.model.ActionType
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.data.querybank.MarkovQueryGenerator
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
    private val markovGenerator: MarkovQueryGenerator = mockk(relaxed = true)
    private val profileRepo: PoisonProfileRepository = mockk(relaxed = true)
    private val webViewPool: PhantomWebViewPool = mockk(relaxed = true)
    private val userAgentPool: UserAgentPool = mockk(relaxed = true)
    private val blocklist: DomainBlocklist = mockk(relaxed = true)
    private val demographicDao: DemographicProfileDao = mockk(relaxed = true)
    private val customInterestMapper: CustomInterestMapper = mockk(relaxed = true)
    private val queryBlocklist: QueryBlocklist = mockk(relaxed = true)
    private val localeManager: LocaleManager = mockk(relaxed = true)
    private val webView: WebView = mockk(relaxed = true)

    private fun newModule() = SearchPoisonModule(
        queryBankManager = queryBankManager,
        markovGenerator = markovGenerator,
        profileRepo = profileRepo,
        webViewPool = webViewPool,
        userAgentPool = userAgentPool,
        blocklist = blocklist,
        demographicDao = demographicDao,
        customInterestMapper = customInterestMapper,
        queryBlocklist = queryBlocklist,
        localeManager = localeManager,
        // nextFloat()=0.99 forces the query-bank branch (never calls markovGenerator.generate);
        // nextBits()=0 makes SEARCH_ENGINES.random(this) deterministic (first engine = google)
        // and the Accept-Language / dwell draws deterministic.
        random = bankBranchRandom,
    )

    private val bankBranchRandom = object : kotlin.random.Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextFloat(): Float = 0.99f
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
