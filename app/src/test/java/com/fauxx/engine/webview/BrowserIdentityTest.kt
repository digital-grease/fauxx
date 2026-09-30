package com.fauxx.engine.webview

import com.fauxx.data.device.Brand
import com.fauxx.data.device.DeviceProfile
import com.fauxx.data.device.FormFactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserIdentityTest {

    /** The brand list WebView 151 reported for itself, as measured by FingerprintProbeInstrumentedTest. */
    private val webView151 = listOf(
        BrandEntry("Not=A?Brand", "99", "99.0.0.0"),
        BrandEntry("Android WebView", "151", "151.0.7922.199"),
        BrandEntry("Chromium", "151", "151.0.7922.199"),
    )

    private val pixel7 = DeviceProfile(
        formFactor = FormFactor.MOBILE,
        userAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/142.0.0.0 Mobile Safari/537.36",
        platform = "Android", platformVersion = "13.0.0", model = "Pixel 7", isMobile = true,
        brands = listOf(Brand("Chromium", "142"), Brand("Google Chrome", "142"), Brand("Not?A_Brand", "24")),
        screenWidth = 412, screenHeight = 915, devicePixelRatio = 2.625f,
        hardwareConcurrency = 8, deviceMemory = 8,
    )

    @Test
    fun `renames the WebView brand to Chrome and keeps the GREASE entry and order`() {
        val brands = BrowserIdentity.chromeBrands(webView151)!!
        assertEquals(listOf("Not=A?Brand", "Google Chrome", "Chromium"), brands.map { it.brand })
        assertEquals(webView151.map { it.majorVersion }, brands.map { it.majorVersion })
        assertEquals(webView151.map { it.fullVersion }, brands.map { it.fullVersion })
    }

    @Test
    fun `refuses to guess when the WebView reports no WebView brand`() {
        assertNull(BrowserIdentity.chromeBrands(listOf(BrandEntry("Chromium", "151", "151.0.0.0"))))
        assertNull(BrowserIdentity.forDevice(pixel7, emptyList(), "151.0.7922.199"))
    }

    @Test
    fun `presents the reduced Chrome UA at the installed WebView's major, not the persona's calendar major`() {
        val identity = BrowserIdentity.forDevice(pixel7, webView151, "151.0.7922.199")!!
        assertEquals(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/151.0.0.0 Mobile Safari/537.36",
            identity.userAgent,
        )
        assertFalse("the handset model must not be in the reduced UA", identity.userAgent.contains("Pixel"))
        assertFalse("the reduced UA carries no WebView token", identity.userAgent.contains("; wv"))
        assertEquals("151.0.7922.199", identity.fullVersion)
    }

    @Test
    fun `carries the persona handset only in the high-entropy fields`() {
        val identity = BrowserIdentity.forDevice(pixel7, webView151, "151.0.7922.199")!!
        assertEquals("Pixel 7", identity.model)
        assertEquals("13.0.0", identity.platformVersion)
    }

    @Test
    fun `falls back to the default catalog handset with no persona`() {
        val identity = BrowserIdentity.forDevice(null, webView151, "151.0.7922.199")!!
        assertEquals(BrowserIdentity.DEFAULT_MODEL, identity.model)
        assertEquals(BrowserIdentity.DEFAULT_PLATFORM_VERSION, identity.platformVersion)
        assertTrue(identity.brands.any { it.brand == BrowserIdentity.CHROME_BRAND })
    }

    @Test
    fun `an unparseable WebView version presents nothing rather than a bad major`() {
        assertNull(BrowserIdentity.forDevice(pixel7, webView151, "garbage"))
    }
}
