package com.fauxx.engine.modules

import android.webkit.WebView
import com.fauxx.data.crawllist.CrawlEntry
import com.fauxx.data.crawllist.CrawlListManager
import com.fauxx.data.crawllist.PendingCrawlEntry
import com.fauxx.data.model.ActionType
import com.fauxx.data.model.PoisonProfile
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.webview.PhantomWebViewPool
import com.fauxx.support.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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

/**
 * [DiverseBrowsingModule.onAction] is the Play-safe page-visit path: it pulls a URL from the
 * crawl corpus, loads it in a pooled [WebView] on the main dispatcher, dwells, and logs a
 * PAGE_VISIT action. A WebView crash must be swallowed and reported as a failed visit.
 *
 * WebView is an android.* type, so this runs under Robolectric. The @Config dodges the
 * SQLCipher loadLibrary in FauxxApp by forcing the plain android.app.Application. The module
 * dispatches to Dispatchers.Main inside onAction, so [MainDispatcherRule] swaps that for an
 * [UnconfinedTestDispatcher] and onAction is driven via runTest(testDispatcher).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DiverseBrowsingModuleTest {

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
        // isEnabled() reads adPollutionEnabled; provide a profile that keeps the module on.
        every { profileRepo.getProfile() } returns PoisonProfile(adPollutionEnabled = true)
    }

    private fun newModule() = DiverseBrowsingModule(
        crawlListManager = crawlListManager,
        webViewPool = webViewPool,
        profileRepo = profileRepo,
    )

    @Test
    fun `onAction visits the page and reports a successful PAGE_VISIT`() = runTest(testDispatcher) {
        every { crawlListManager.nextUrlOrWait(any()) } returns testEntry

        val result = newModule().onAction(CategoryPool.GAMING)

        assertEquals(ActionType.PAGE_VISIT, result.actionType)
        assertEquals("detail must be the visited URL", testEntry.entry.url, result.detail)
        assertTrue("a normal load must report success", result.success)
    }

    @Test
    fun `onAction reports failure when the WebView throws`() = runTest(testDispatcher) {
        every { crawlListManager.nextUrlOrWait(any()) } returns testEntry
        every { webView.loadUrl(any<String>()) } throws RuntimeException("WebView crashed")

        val result = newModule().onAction(CategoryPool.GAMING)

        assertEquals(ActionType.PAGE_VISIT, result.actionType)
        assertFalse("a thrown WebView load must report failure", result.success)
    }

    @Test
    fun `onAction reports failure when no eligible URL is available`() = runTest(testDispatcher) {
        every { crawlListManager.nextUrlOrWait(any()) } returns null

        val result = newModule().onAction(CategoryPool.GAMING)

        assertEquals(ActionType.PAGE_VISIT, result.actionType)
        assertFalse("an exhausted corpus must report failure", result.success)
        assertEquals("No eligible URL available", result.detail)
    }
}
