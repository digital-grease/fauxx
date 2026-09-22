package com.fauxx.engine.modules

import com.fauxx.data.device.Brand
import com.fauxx.data.device.DeviceDeriver
import com.fauxx.data.device.DeviceProfile
import com.fauxx.data.device.FormFactor
import com.fauxx.data.model.ActionType
import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.webview.JarSwap
import com.fauxx.engine.webview.PersonaJarStore
import com.fauxx.engine.webview.PhantomWebViewPool
import com.fauxx.network.UserAgentPool
import com.fauxx.targeting.layer3.PersonaChannel
import com.fauxx.targeting.layer3.PersonaRotationLayer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
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
    private val jarStore: PersonaJarStore = mockk(relaxed = true)

    private fun newModule() = FingerprintModule(
        userAgentPool = userAgentPool,
        webViewPool = webViewPool,
        profileRepo = profileRepo,
        personaRotationLayer = personaRotationLayer,
        deviceDeriver = deviceDeriver,
        jarStore = jarStore,
    )

    /**
     * Never let a relaxed mock decide what [PhantomWebViewPool.setPersonaJar] returns. It returns
     * a [JarSwap] now, and the module branches on it: a relaxed default would silently pick a
     * value and make assertions about device binding trivially true or trivially unreachable.
     * Tests that care about a specific outcome re-stub this.
     */
    @Before
    fun stubJarSwapDefault() {
        coEvery { webViewPool.setPersonaJar(any()) } returns JarSwap.SWAPPED
    }

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

    @Test
    fun `onAction binds the persona's cookie jar keyed on the same persona as its device`() = runTest {
        // Issue #242: the jar and the User-Agent must key on the SAME persona. A jar that
        // outlived its handset model is a contradiction a tracker reads in one pass, because a
        // cookie cannot follow someone from one device to another.
        val p = persona()
        every { personaRotationLayer.personaForChannel(PersonaChannel.DEVICE) } returns p
        every { deviceDeriver.mobileFor(p) } returns device("UA")
        coEvery { webViewPool.setPersonaJar(any()) } returns JarSwap.UNCHANGED

        newModule().onAction(CategoryPool.GAMING)

        coVerify(exactly = 1) { webViewPool.setPersonaJar(p.id) }
    }

    @Test
    fun `no persona means no jar is bound at all`() = runTest {
        every { personaRotationLayer.personaForChannel(PersonaChannel.DEVICE) } returns null
        every { userAgentPool.randomChromiumAndroid() } returns "UA-seed"

        newModule().onAction(CategoryPool.GAMING)

        coVerify(exactly = 0) { webViewPool.setPersonaJar(any()) }
    }

    @Test
    fun `retired jars are swept only when the jar actually rotated`() = runTest {
        val p = persona()
        every { personaRotationLayer.personaForChannel(PersonaChannel.DEVICE) } returns p
        every { deviceDeriver.mobileFor(p) } returns device("UA")

        // Unchanged jar: enumerating and deleting profiles is not free, and nothing can have
        // been retired if the jar did not turn over.
        coEvery { webViewPool.setPersonaJar(any()) } returns JarSwap.UNCHANGED
        newModule().onAction(CategoryPool.GAMING)
        coVerify(exactly = 0) { jarStore.deleteJarsExcept(any()) }

        // Rotated: sweep, and keep exactly the jars of the personas still in play.
        coEvery { webViewPool.setPersonaJar(any()) } returns JarSwap.SWAPPED
        every { personaRotationLayer.livePersonaIds() } returns setOf("cur", "prev")
        every { jarStore.jarKeyFor("cur") } returns "jar-cur"
        every { jarStore.jarKeyFor("prev") } returns "jar-prev"

        newModule().onAction(CategoryPool.GAMING)

        coVerify(exactly = 1) { jarStore.deleteJarsExcept(setOf("jar-cur", "jar-prev")) }
    }

    @Test
    fun `the previous persona's jar survives the sweep while its channels still serve it`() = runTest {
        // The outgoing persona keeps being served on any channel still inside its adoption lag,
        // which at a 90-day lifetime is up to ~18 days after rotation. Deleting its jar on the
        // rotation instant would pull storage out from under a live identity.
        val p = persona()
        every { personaRotationLayer.personaForChannel(PersonaChannel.DEVICE) } returns p
        every { deviceDeriver.mobileFor(p) } returns device("UA")
        coEvery { webViewPool.setPersonaJar(any()) } returns JarSwap.SWAPPED
        every { personaRotationLayer.livePersonaIds() } returns setOf("cur", "prev")
        every { jarStore.jarKeyFor("cur") } returns "jar-cur"
        every { jarStore.jarKeyFor("prev") } returns "jar-prev"

        newModule().onAction(CategoryPool.GAMING)

        val keep = slot<Set<String>>()
        coVerify { jarStore.deleteJarsExcept(capture(keep)) }
        assertTrue("outgoing persona's jar must be kept", "jar-prev" in keep.captured)
    }

    @Test
    fun `a deferred jar swap holds the previous device instead of pairing it with the old jar`() = runTest {
        // The pool is still serving the PREVIOUS persona's jar. Presenting this persona's UA over
        // it would replay a cookie set under one handset model under another, which is the exact
        // contradiction per-persona jars exist to remove. Hold, and retry next action.
        val p = persona()
        every { personaRotationLayer.personaForChannel(PersonaChannel.DEVICE) } returns p
        every { deviceDeriver.mobileFor(p) } returns device("UA")
        coEvery { webViewPool.setPersonaJar(any()) } returns JarSwap.DEFERRED

        newModule().onAction(CategoryPool.GAMING)

        verify(exactly = 0) { webViewPool.setDevice(any()) }
        coVerify(exactly = 0) { jarStore.deleteJarsExcept(any()) }
    }

    @Test
    fun `a failed jar rebuild also holds the previous device`() = runTest {
        val p = persona()
        every { personaRotationLayer.personaForChannel(PersonaChannel.DEVICE) } returns p
        every { deviceDeriver.mobileFor(p) } returns device("UA")
        coEvery { webViewPool.setPersonaJar(any()) } returns JarSwap.FAILED

        newModule().onAction(CategoryPool.GAMING)

        verify(exactly = 0) { webViewPool.setDevice(any()) }
    }

    @Test
    fun `an unsupported device still gets its persona device identity`() = runTest {
        // UNSUPPORTED is not a mismatch. Treating it as one would freeze the User-Agent forever
        // on every device whose WebView predates MULTI_PROFILE, which is a whole population, not
        // an error case.
        val p = persona()
        val dev = device("UA")
        every { personaRotationLayer.personaForChannel(PersonaChannel.DEVICE) } returns p
        every { deviceDeriver.mobileFor(p) } returns dev
        coEvery { webViewPool.setPersonaJar(any()) } returns JarSwap.UNSUPPORTED

        newModule().onAction(CategoryPool.GAMING)

        verify(exactly = 1) { webViewPool.setDevice(dev) }
        // No jar exists to sweep on such a device.
        coVerify(exactly = 0) { jarStore.deleteJarsExcept(any()) }
    }

    @Test
    fun `a failing jar sweep does not fail the action`() = runTest {
        val p = persona()
        every { personaRotationLayer.personaForChannel(PersonaChannel.DEVICE) } returns p
        every { deviceDeriver.mobileFor(p) } returns device("UA")
        coEvery { webViewPool.setPersonaJar(any()) } returns JarSwap.SWAPPED
        every { personaRotationLayer.livePersonaIds() } returns setOf("cur")
        every { jarStore.jarKeyFor(any()) } returns "jar-cur"
        coEvery { jarStore.deleteJarsExcept(any()) } throws IllegalStateException("profile in use")

        // Storage housekeeping must never take down a crawl action.
        val result = newModule().onAction(CategoryPool.GAMING)

        assertEquals(ActionType.FINGERPRINT_ROTATE, result.actionType)
    }
}
