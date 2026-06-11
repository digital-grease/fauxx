package com.fauxx.engine.modules

import android.content.Context
import android.webkit.WebView
import com.fauxx.data.model.ActionType
import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.webview.PhantomWebViewPool
import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
import com.fauxx.support.MainDispatcherRule
import com.fauxx.targeting.layer3.PersonaRotationLayer
import kotlin.random.Random
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
    private val noPersonaLayer: PersonaRotationLayer = mockk {
        every { personaForChannel(any()) } returns null
    }

    @Before
    fun setup() {
        coEvery { webViewPool.acquire() } returns webView
        every { localeManager.currentLocale } returns SupportedLocale.EN
    }

    private fun newModule(
        personaLayer: PersonaRotationLayer = noPersonaLayer,
        random: Random = Random.Default,
    ) = AppSignalModule(
        context = context,
        profileRepo = profileRepo,
        webViewPool = webViewPool,
        localeManager = localeManager,
        personaLayer = personaLayer,
        random = random,
    )

    /** Forces the persona-swap branch deterministically. */
    private class FixedRandom(private val f: Float) : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextFloat(): Float = f
    }

    private fun personaLayerWith(interests: Set<CategoryPool>): PersonaRotationLayer {
        val persona = SyntheticPersona(
            id = "test-persona",
            name = "Test",
            ageRange = "AGE_25_34",
            profession = "ENGINEER",
            region = "US_WEST",
            interests = interests,
            activeUntil = Long.MAX_VALUE
        )
        return mockk { every { personaForChannel(any()) } returns persona }
    }

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

    // E8 (#174) persona-interest bias: an off-interest action is redirected to a persona
    // interest with probability PERSONA_INTEREST_SWAP_FRACTION, and the swapped category
    // is what gets logged. No persona (Layer 3 off) must mean no swap.

    @Test
    fun `off-interest action swaps to a persona interest when the dice say so`() =
        runTest(testDispatcher) {
            val module = newModule(
                personaLayer = personaLayerWith(setOf(CategoryPool.COOKING)),
                random = FixedRandom(0f) // 0 < 0.35 -> swap
            )

            val result = module.onAction(CategoryPool.GAMING)

            assertEquals(
                "logged category must be the persona interest actually searched",
                CategoryPool.COOKING,
                result.category
            )
            assertTrue(
                "URL keywords must match the swapped category; was: ${result.detail}",
                result.detail.contains("recipe+app")
            )
            assertTrue(
                "host invariant must survive the persona swap; was: ${result.detail}",
                result.detail.startsWith("https://play.google.com/store/search")
            )
        }

    @Test
    fun `interest-less persona never swaps and never crashes`() = runTest(testDispatcher) {
        val module = newModule(
            personaLayer = personaLayerWith(emptySet()),
            random = FixedRandom(0f) // swap-favorable dice
        )

        val result = module.onAction(CategoryPool.GAMING)

        assertEquals(CategoryPool.GAMING, result.category)
    }

    @Test
    fun `off-interest action keeps its category when the dice say no swap`() =
        runTest(testDispatcher) {
            val module = newModule(
                personaLayer = personaLayerWith(setOf(CategoryPool.COOKING)),
                random = FixedRandom(0.9f) // 0.9 >= 0.35 -> no swap
            )

            val result = module.onAction(CategoryPool.GAMING)

            assertEquals(CategoryPool.GAMING, result.category)
            assertTrue(result.detail.contains("strategy+games"))
        }

    @Test
    fun `no active persona means no swap even with swap-favorable dice`() =
        runTest(testDispatcher) {
            val module = newModule(random = FixedRandom(0f))

            val result = module.onAction(CategoryPool.GAMING)

            assertEquals(CategoryPool.GAMING, result.category)
        }

    @Test
    fun `persona-aligned action is never swapped`() = runTest(testDispatcher) {
        val module = newModule(
            personaLayer = personaLayerWith(setOf(CategoryPool.GAMING, CategoryPool.COOKING)),
            random = FixedRandom(0f)
        )

        val result = module.onAction(CategoryPool.GAMING)

        assertEquals(CategoryPool.GAMING, result.category)
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
