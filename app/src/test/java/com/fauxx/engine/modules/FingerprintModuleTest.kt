package com.fauxx.engine.modules

import com.fauxx.data.device.Brand
import com.fauxx.data.device.DeviceDeriver
import com.fauxx.data.device.DeviceProfile
import com.fauxx.data.device.FormFactor
import com.fauxx.data.model.ActionType
import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.webview.PhantomWebViewPool
import com.fauxx.network.UserAgentPool
import com.fauxx.targeting.layer3.PersonaChannel
import com.fauxx.targeting.layer3.PersonaRotationLayer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FingerprintModule.onAction] presents the ACTIVE PERSONA'S stable device (issue #242): it reads the
 * persona via the staggered [PersonaChannel.DEVICE] accessor, derives the mobile [DeviceProfile], and
 * pushes its UA to [PhantomWebViewPool]. It no longer draws a fresh random UA per action. With no
 * active persona (Layer 3 off) it holds one stable UA instead of rotating. Plain-JVM test (no
 * Robolectric); the actual JS injection lives at the WebView layer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FingerprintModuleTest {

    private val userAgentPool: UserAgentPool = mockk(relaxed = true)
    private val webViewPool: PhantomWebViewPool = mockk(relaxed = true)
    private val profileRepo: PoisonProfileRepository = mockk(relaxed = true)
    private val personaRotationLayer: PersonaRotationLayer = mockk(relaxed = true)
    private val deviceDeriver: DeviceDeriver = mockk(relaxed = true)

    private fun newModule() = FingerprintModule(
        userAgentPool = userAgentPool,
        webViewPool = webViewPool,
        profileRepo = profileRepo,
        personaRotationLayer = personaRotationLayer,
        deviceDeriver = deviceDeriver,
    )

    private fun persona() = SyntheticPersona(
        id = "p", name = "n", ageRange = "AGE_35_44", profession = "ENGINEER",
        region = "US_MIDWEST", interests = setOf(CategoryPool.TECHNOLOGY),
        createdAt = 1L, activeUntil = 2L,
    )

    private fun device(ua: String) = DeviceProfile(
        formFactor = FormFactor.MOBILE, userAgent = ua, platform = "Android",
        platformVersion = "14.0.0", model = "Pixel 8", isMobile = true,
        brands = listOf(Brand("Chromium", "142")), screenWidth = 412, screenHeight = 915,
        devicePixelRatio = 2.625f, hardwareConcurrency = 8, deviceMemory = 8,
    )

    @Test
    fun `onAction presents the active persona's stable device UA, without drawing a random UA`() = runTest {
        val ua = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36"
        val p = persona()
        val dev = device(ua)
        every { personaRotationLayer.personaForChannel(PersonaChannel.DEVICE) } returns p
        every { deviceDeriver.mobileFor(p) } returns dev

        val result = newModule().onAction(CategoryPool.GAMING)

        // The whole device is bound (UA + fixed navigator values), not just a UA string.
        verify(exactly = 1) { webViewPool.setDevice(dev) }
        verify(exactly = 0) { userAgentPool.randomChromiumAndroid() }
        assertEquals(ActionType.FINGERPRINT_ROTATE, result.actionType)
        assertEquals(CategoryPool.GAMING, result.category)
        assertTrue("detail must name the persona device; was: ${result.detail}", result.detail.contains("Persona device"))
    }

    @Test
    fun `onAction holds a single stable UA when there is no active persona (Layer 3 off)`() = runTest {
        every { personaRotationLayer.personaForChannel(PersonaChannel.DEVICE) } returns null
        every { userAgentPool.randomChromiumAndroid() } returns "UA-seed"

        val result = newModule().onAction(CategoryPool.GAMING)

        // Seed-if-unset (stable), never a per-action device/UA churn.
        verify(exactly = 1) { webViewPool.setUserAgentIfUnset("UA-seed") }
        verify(exactly = 0) { webViewPool.setDevice(any()) }
        verify(exactly = 0) { webViewPool.setUserAgent(any()) }
        assertEquals(ActionType.FINGERPRINT_ROTATE, result.actionType)
        assertTrue("detail must note the held state; was: ${result.detail}", result.detail.contains("held"))
    }
}
