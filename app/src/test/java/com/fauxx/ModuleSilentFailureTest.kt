package com.fauxx

import android.webkit.WebView
import com.fauxx.data.crawllist.CrawlEntry
import com.fauxx.data.crawllist.CrawlListManager
import com.fauxx.data.crawllist.PendingCrawlEntry
import com.fauxx.data.model.ActionType
import com.fauxx.data.model.PoisonProfile
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.modules.AdPollutionModule
import com.fauxx.engine.modules.CookieSaturationModule
import com.fauxx.engine.webview.PhantomWebViewPool
import com.fauxx.support.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ModuleSilentFailureTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    private val webView: WebView = mockk(relaxed = true)
    private val webViewPool: PhantomWebViewPool = mockk(relaxed = true)
    private val crawlListManager: CrawlListManager = mockk(relaxed = true)
    private val profileRepo: PoisonProfileRepository = mockk(relaxed = true)

    private val testEntry = PendingCrawlEntry(
        entry = CrawlEntry("https://example.com/page", "example.com", CategoryPool.GAMING),
        waitMs = 0L
    )

    @Before
    fun setup() {
        coEvery { webViewPool.acquire() } returns webView
    }

    @Test
    fun `AdPollutionModule reports failure when WebView throws`() = runTest(testDispatcher) {
        every { profileRepo.getProfile() } returns PoisonProfile(adPollutionEnabled = true)
        every { crawlListManager.nextUrlOrWait(any()) } returns testEntry
        every { webView.loadUrl(any<String>()) } throws RuntimeException("WebView crashed")

        val module = AdPollutionModule(crawlListManager, webViewPool, profileRepo)
        val result = module.onAction(CategoryPool.GAMING)

        assertFalse("Should report failure when WebView throws", result.success)
    }

    @Test
    fun `AdPollutionModule reports success on normal operation`() = runTest(testDispatcher) {
        every { profileRepo.getProfile() } returns PoisonProfile(adPollutionEnabled = true)
        every { crawlListManager.nextUrlOrWait(any()) } returns testEntry

        val module = AdPollutionModule(crawlListManager, webViewPool, profileRepo)
        val result = module.onAction(CategoryPool.GAMING)

        assertTrue("Should report success on normal load", result.success)
    }

    @Test
    fun `CookieSaturationModule reports failure when WebView throws`() = runTest(testDispatcher) {
        every { profileRepo.getProfile() } returns PoisonProfile(cookieSaturationEnabled = true)
        every { crawlListManager.nextUrlOrWait(any()) } returns testEntry
        every { webView.loadUrl(any<String>()) } throws RuntimeException("WebView crashed")

        val module = CookieSaturationModule(crawlListManager, webViewPool, profileRepo)
        val result = module.onAction(CategoryPool.GAMING)

        assertFalse("Should report failure when WebView throws", result.success)
    }

    @Test
    fun `CookieSaturationModule reports success on normal operation`() = runTest(testDispatcher) {
        every { profileRepo.getProfile() } returns PoisonProfile(cookieSaturationEnabled = true)
        every { crawlListManager.nextUrlOrWait(any()) } returns testEntry

        val module = CookieSaturationModule(crawlListManager, webViewPool, profileRepo)
        val result = module.onAction(CategoryPool.GAMING)

        assertTrue("Should report success on normal load", result.success)
    }

    @Test
    fun `AdPollutionModule visits ad dashboard as AD_CLICK without consulting crawl list`() =
        runTest(testDispatcher) {
            every { profileRepo.getProfile() } returns PoisonProfile(adPollutionEnabled = true)

            // nextFloat() < 0.10 forces the dashboard branch; nextBits() == 0 keeps
            // AD_DASHBOARD_URLS.random(random) at index 0 instead of NPEing on Random.Default.
            val dashboardRandom = object : Random() {
                override fun nextBits(bitCount: Int): Int = 0
                override fun nextFloat(): Float = 0.05f
            }

            val module = AdPollutionModule(crawlListManager, webViewPool, profileRepo, dashboardRandom)
            val result = module.onAction(CategoryPool.GAMING)

            assertEquals(ActionType.AD_CLICK, result.actionType)
            assertTrue("Dashboard URL should be an https link", result.detail.startsWith("https://"))
            assertTrue("Dashboard visit should report success", result.success)
            verify(exactly = 0) { crawlListManager.nextUrlOrWait(any()) }
        }

    @Test
    fun `AdPollutionModule reports failure when no eligible URL for page visit`() =
        runTest(testDispatcher) {
            every { profileRepo.getProfile() } returns PoisonProfile(adPollutionEnabled = true)
            every { crawlListManager.nextUrlOrWait(any()) } returns null

            // nextFloat() >= 0.10 forces the plain page-visit branch.
            val pageVisitRandom = object : Random() {
                override fun nextBits(bitCount: Int): Int = 0
                override fun nextFloat(): Float = 0.5f
            }

            val module = AdPollutionModule(crawlListManager, webViewPool, profileRepo, pageVisitRandom)
            val result = module.onAction(CategoryPool.GAMING)

            assertEquals(ActionType.PAGE_VISIT, result.actionType)
            assertFalse("Should report failure when no eligible URL", result.success)
            assertEquals("No eligible URL", result.detail)
        }

    @Test
    fun `CookieSaturationModule reports failure when no eligible URL`() = runTest(testDispatcher) {
        every { profileRepo.getProfile() } returns PoisonProfile(cookieSaturationEnabled = true)
        every { crawlListManager.nextUrlOrWait(any()) } returns null

        val module = CookieSaturationModule(crawlListManager, webViewPool, profileRepo)
        val result = module.onAction(CategoryPool.GAMING)

        assertEquals(ActionType.COOKIE_HARVEST, result.actionType)
        assertFalse("Should report failure when no eligible URL", result.success)
        assertEquals("No eligible URL available", result.detail)
    }
}
