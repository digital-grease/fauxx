package com.fauxx

import android.webkit.WebView
import com.fauxx.data.crawllist.CrawlEntry
import com.fauxx.data.crawllist.CrawlListManager
import com.fauxx.data.crawllist.PendingCrawlEntry
import com.fauxx.data.model.PoisonProfile
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.modules.AdPollutionModule
import com.fauxx.engine.modules.CookieSaturationModule
import com.fauxx.engine.webview.PhantomWebViewPool
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ModuleSilentFailureTest {

    private val testDispatcher = UnconfinedTestDispatcher()
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
        Dispatchers.setMain(testDispatcher)
        coEvery { webViewPool.acquire() } returns webView
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
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
}
