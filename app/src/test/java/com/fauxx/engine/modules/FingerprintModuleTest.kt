package com.fauxx.engine.modules

import com.fauxx.data.model.ActionType
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.webview.PhantomWebViewPool
import com.fauxx.network.UserAgentPool
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FingerprintModule.onAction] is pure: it pulls a UA from [UserAgentPool], pushes it to
 * [PhantomWebViewPool.setUserAgent], and logs a FINGERPRINT_ROTATE action. No android.*
 * framework is touched (the actual JS injection lives at the WebView layer), so this is a
 * plain-JVM test with no Robolectric runner.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FingerprintModuleTest {

    private val userAgentPool: UserAgentPool = mockk(relaxed = true)
    private val webViewPool: PhantomWebViewPool = mockk(relaxed = true)
    private val profileRepo: PoisonProfileRepository = mockk(relaxed = true)

    private fun newModule() = FingerprintModule(
        userAgentPool = userAgentPool,
        webViewPool = webViewPool,
        profileRepo = profileRepo,
    )

    @Test
    fun `onAction pushes the rotated user agent to the webview pool exactly once`() = runTest {
        val theUA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) FauxxTest/1.0"
        every { userAgentPool.random() } returns theUA

        newModule().onAction(CategoryPool.GAMING)

        verify(exactly = 1) { webViewPool.setUserAgent(theUA) }
    }

    @Test
    fun `onAction returns a FINGERPRINT_ROTATE entry whose detail records the rotated UA`() = runTest {
        val theUA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) FauxxTest/1.0"
        every { userAgentPool.random() } returns theUA

        val result = newModule().onAction(CategoryPool.GAMING)

        assertEquals(
            "UA rotation must log as a FINGERPRINT_ROTATE action",
            ActionType.FINGERPRINT_ROTATE,
            result.actionType
        )
        assertEquals(CategoryPool.GAMING, result.category)
        assertTrue(
            "detail must announce the rotation; was: ${result.detail}",
            result.detail.contains("UA rotated:")
        )
    }
}
