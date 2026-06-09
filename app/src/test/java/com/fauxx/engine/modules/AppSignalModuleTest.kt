package com.fauxx.engine.modules

import android.content.Context
import android.webkit.WebView
import com.fauxx.data.model.ActionType
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.webview.PhantomWebViewPool
import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
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
 * [AppSignalModule.onAction] builds a localized Play Store search URL from the active locale's
 * keyword bank, loads it in a pooled [WebView] on the main dispatcher, and logs a
 * DEEP_LINK_VISIT. A WebView crash must be swallowed and reported as a failed visit.
 *
 * WebView is an android.* type, so this runs under Robolectric. The @Config dodges the
 * SQLCipher loadLibrary in FauxxApp by forcing the plain android.app.Application. The module
 * dispatches to Dispatchers.Main inside onAction, so [MainDispatcherRule] swaps that for an
 * [UnconfinedTestDispatcher] and onAction is driven via runTest(testDispatcher).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AppSignalModuleTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    private val context: Context = mockk(relaxed = true)
    private val profileRepo: PoisonProfileRepository = mockk(relaxed = true)
    private val webView: WebView = mockk(relaxed = true)
    private val webViewPool: PhantomWebViewPool = mockk(relaxed = true)
    private val localeManager: LocaleManager = mockk(relaxed = true)

    @Before
    fun setup() {
        coEvery { webViewPool.acquire() } returns webView
        every { localeManager.currentLocale } returns SupportedLocale.EN
    }

    private fun newModule() = AppSignalModule(
        context = context,
        profileRepo = profileRepo,
        webViewPool = webViewPool,
        localeManager = localeManager,
    )

    @Test
    fun `onAction builds the localized play store URL and reports a successful DEEP_LINK_VISIT`() =
        runTest(testDispatcher) {
            val result = newModule().onAction(CategoryPool.GAMING)

            assertEquals(
                "app signal must log as a DEEP_LINK_VISIT action",
                ActionType.DEEP_LINK_VISIT,
                result.actionType
            )
            assertTrue(
                "detail must be a Play Store search URL; was: ${result.detail}",
                result.detail.startsWith("https://play.google.com/store/search")
            )
            assertTrue(
                "detail must localize via the EN hl param; was: ${result.detail}",
                result.detail.contains("hl=en")
            )
            assertTrue("a normal load must report success", result.success)
        }

    @Test
    fun `onAction reports failure when the WebView throws`() = runTest(testDispatcher) {
        every { webView.loadUrl(any<String>(), any()) } throws RuntimeException("WebView crashed")

        val result = newModule().onAction(CategoryPool.GAMING)

        assertEquals(
            "a thrown WebView load must NOT change the logged action type",
            ActionType.DEEP_LINK_VISIT,
            result.actionType
        )
        assertFalse("a thrown WebView load must report failure", result.success)
    }
}
