package com.fauxx.data.device

import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Locks the Device Identity derivation contract (issue #242, spike C2). The device set must be
 * deterministic and coherent: a persona always resolves to the same one-mobile-plus-one-desktop pair,
 * the mobile UA is Android-Chromium (the phone's WebView TLS, #168), the desktop UA is not, and the
 * Chrome major is pinned to the persona's creation time. The catalog checksum guards the shared asset
 * so the desktop companion (fauxx-desktop#47), which vendors the same file, cannot drift out of sync.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DeviceDeriverTest {

    private val deriver get() = DeviceDeriver(RuntimeEnvironment.getApplication())

    private fun persona(id: String, createdAt: Long = DeviceDeriver.BASELINE_EPOCH_MS) = SyntheticPersona(
        id = id,
        name = "n",
        ageRange = "AGE_35_44",
        profession = "ENGINEER",
        region = "US_MIDWEST",
        interests = setOf(CategoryPool.TECHNOLOGY),
        createdAt = createdAt,
        activeUntil = createdAt + 7L * 24 * 60 * 60 * 1000,
    )

    private fun isChromiumAndroid(ua: String) =
        ua.contains("Android") && ua.contains("Chrome/") && ua.contains("Mobile") &&
            !ua.contains("Firefox") && !ua.contains("CriOS") && !ua.contains("FxiOS") && !ua.contains("EdgA")

    // --- chromeMajor (pure) ---

    @Test
    fun `chromeMajor equals the baseline at the baseline epoch and floors below it`() {
        assertEquals(DeviceDeriver.BASELINE_MAJOR, DeviceDeriver.chromeMajor(DeviceDeriver.BASELINE_EPOCH_MS))
        assertEquals(DeviceDeriver.BASELINE_MAJOR, DeviceDeriver.chromeMajor(DeviceDeriver.BASELINE_EPOCH_MS - 5_000))
        assertEquals(DeviceDeriver.BASELINE_MAJOR, DeviceDeriver.chromeMajor(0L))
    }

    @Test
    fun `chromeMajor advances one major per release interval`() {
        val epoch = DeviceDeriver.BASELINE_EPOCH_MS
        val interval = DeviceDeriver.RELEASE_INTERVAL_MS
        assertEquals(DeviceDeriver.BASELINE_MAJOR + 1, DeviceDeriver.chromeMajor(epoch + interval))
        assertEquals(DeviceDeriver.BASELINE_MAJOR + 3, DeviceDeriver.chromeMajor(epoch + 3 * interval + 1))
    }

    @Test
    fun `chromeMajor is monotonic non-decreasing in createdAt`() {
        var prev = DeviceDeriver.chromeMajor(0L)
        var t = DeviceDeriver.BASELINE_EPOCH_MS
        repeat(40) {
            t += 9L * 24 * 60 * 60 * 1000 // 9-day steps
            val cur = DeviceDeriver.chromeMajor(t)
            assertTrue("major must never go backwards", cur >= prev)
            prev = cur
        }
    }

    // --- pick (pure) ---

    @Test
    fun `pick is deterministic and within range`() {
        repeat(20) { i ->
            val id = "persona-$i"
            val a = DeviceDeriver.pick(id, DeviceDeriver.DOMAIN_MOBILE, 0, 6)
            val b = DeviceDeriver.pick(id, DeviceDeriver.DOMAIN_MOBILE, 0, 6)
            assertEquals(a, b)
            assertTrue(a in 0 until 6)
        }
    }

    @Test
    fun `pick varies across persona ids`() {
        val distinct = (0 until 60).map { DeviceDeriver.pick("id-$it", DeviceDeriver.DOMAIN_MOBILE, 0, 6) }.toSet()
        assertTrue("selection must not be degenerate", distinct.size > 1)
    }

    // --- devicesFor (catalog + derivation) ---

    @Test
    fun `derives exactly one mobile and one desktop device`() {
        val devices = deriver.devicesFor(persona("p1"))
        assertEquals(2, devices.size)
        assertEquals(1, devices.count { it.formFactor == FormFactor.MOBILE })
        assertEquals(1, devices.count { it.formFactor == FormFactor.DESKTOP })
    }

    @Test
    fun `mobile device is android-chromium and desktop device is not mobile`() {
        val p = persona("p2")
        val mobile = deriver.mobileFor(p)
        val desktop = deriver.desktopFor(p)

        assertTrue("mobile UA must be Android-Chromium (#168): ${mobile.userAgent}", isChromiumAndroid(mobile.userAgent))
        assertTrue(mobile.isMobile)
        assertEquals("Android", mobile.platform)

        assertFalse("desktop must not be mobile", desktop.isMobile)
        assertFalse("desktop UA must not carry the Mobile token", desktop.userAgent.contains("Mobile"))
        assertFalse("desktop UA must not be Android", desktop.userAgent.contains("Android"))
        assertTrue(desktop.platform in setOf("Windows", "macOS", "Linux"))
    }

    @Test
    fun `derivation is stable across calls and across deriver instances`() {
        val p = persona("stable-id")
        val a = deriver.devicesFor(p)
        val b = deriver.devicesFor(p)
        val c = DeviceDeriver(RuntimeEnvironment.getApplication()).devicesFor(p) // fresh instance = "restart"
        assertEquals(a, b)
        assertEquals(a, c)
    }

    @Test
    fun `chrome major is substituted into the UA and brand versions with no token left`() {
        val p = persona("v-id", createdAt = DeviceDeriver.BASELINE_EPOCH_MS) // major == BASELINE_MAJOR
        val major = DeviceDeriver.BASELINE_MAJOR.toString()
        for (d in deriver.devicesFor(p)) {
            assertTrue("UA must carry the substituted major: ${d.userAgent}", d.userAgent.contains("Chrome/$major."))
            assertFalse("no unresolved token may remain", d.userAgent.contains(DeviceDeriver.MAJOR_TOKEN))
            val chromeBrand = d.brands.first { it.name == "Google Chrome" }
            assertEquals(major, chromeBrand.version)
        }
    }

    @Test
    fun `mobile model selection distributes across personas`() {
        val models = (0 until 60).map { deriver.mobileFor(persona("id-$it")).model }.toSet()
        assertTrue("expected more than one mobile model over many personas", models.size > 1)
    }

    // --- shared-asset checksum (vendor + checksum contract with fauxx-desktop#47) ---

    @Test
    fun `bundled device_templates_json matches the pinned checksum`() {
        val bytes = RuntimeEnvironment.getApplication().assets.open("device_templates.json").use { it.readBytes() }
        val hex = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(
            "device_templates.json changed — update this checksum AND the vendored copy + test in fauxx-desktop#47",
            DEVICE_TEMPLATES_SHA256,
            hex,
        )
    }

    @Test
    fun `golden vector locks the exact derived output (cross-language contract for fauxx-desktop#47)`() {
        // These frozen outputs ARE the cross-repo contract: the Rust deriver in fauxx-desktop#47 must
        // reproduce them byte-for-byte from the same persona.id + createdAt (same catalog, same pick,
        // same version formula). Only regenerate on an INTENTIONAL catalog/algorithm change — and
        // update the Rust golden test in lockstep. (Regen: temporarily `fail()` printing mobileFor/
        // desktopFor for GOLDEN_CASES, as in this test's git history.)
        for ((id, ts) in GOLDEN_CASES) {
            val p = persona(id, ts)
            val key = "$id@$ts"
            assertEquals("mobile golden mismatch for $key", GOLDEN_MOBILE.getValue(key), deriver.mobileFor(p).toString())
            assertEquals("desktop golden mismatch for $key", GOLDEN_DESKTOP.getValue(key), deriver.desktopFor(p).toString())
        }
    }

    private companion object {
        // Pinned SHA-256 of app/src/main/assets/device_templates.json. The desktop companion vendors
        // the same file and asserts the same value, so the two repos cannot silently diverge.
        const val DEVICE_TEMPLATES_SHA256 = "3059247b5e83ea09b3ec69d8ed68577c4ceff27d3ca09f0842dd6db0b1e7a3dd"

        // Fixed (id, createdAt) cases pinned as the cross-language golden vector: chosen to span
        // different templates and Chrome-version boundaries (142 / 145 / 149). fauxx-desktop#47 must
        // reproduce these.
        val GOLDEN_CASES = listOf(
            "11111111-1111-4111-8111-111111111111" to DeviceDeriver.BASELINE_EPOCH_MS,
            "22222222-2222-4222-8222-222222222222" to (DeviceDeriver.BASELINE_EPOCH_MS + 90L * 24 * 60 * 60 * 1000),
            "abcdef00-0000-4000-8000-000000000000" to (DeviceDeriver.BASELINE_EPOCH_MS + 200L * 24 * 60 * 60 * 1000),
        )

        val GOLDEN_MOBILE = mapOf(
            "11111111-1111-4111-8111-111111111111@1768262400000" to
                "DeviceProfile(formFactor=MOBILE, userAgent=Mozilla/5.0 (Linux; Android 13; SM-S901B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36, platform=Android, platformVersion=13.0.0, model=SM-S901B, isMobile=true, brands=[Brand(name=Chromium, version=142), Brand(name=Google Chrome, version=142), Brand(name=Not?A_Brand, version=24)], screenWidth=360, screenHeight=780, devicePixelRatio=3.0, hardwareConcurrency=8, deviceMemory=8)",
            "22222222-2222-4222-8222-222222222222@1776038400000" to
                "DeviceProfile(formFactor=MOBILE, userAgent=Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Mobile Safari/537.36, platform=Android, platformVersion=14.0.0, model=Pixel 8, isMobile=true, brands=[Brand(name=Chromium, version=145), Brand(name=Google Chrome, version=145), Brand(name=Not?A_Brand, version=24)], screenWidth=412, screenHeight=915, devicePixelRatio=2.625, hardwareConcurrency=8, deviceMemory=8)",
            "abcdef00-0000-4000-8000-000000000000@1785542400000" to
                "DeviceProfile(formFactor=MOBILE, userAgent=Mozilla/5.0 (Linux; Android 14; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36, platform=Android, platformVersion=14.0.0, model=SM-S911B, isMobile=true, brands=[Brand(name=Chromium, version=149), Brand(name=Google Chrome, version=149), Brand(name=Not?A_Brand, version=24)], screenWidth=360, screenHeight=780, devicePixelRatio=3.0, hardwareConcurrency=8, deviceMemory=12)",
        )

        val GOLDEN_DESKTOP = mapOf(
            "11111111-1111-4111-8111-111111111111@1768262400000" to
                "DeviceProfile(formFactor=DESKTOP, userAgent=Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36, platform=macOS, platformVersion=14.5.0, model=, isMobile=false, brands=[Brand(name=Chromium, version=142), Brand(name=Google Chrome, version=142), Brand(name=Not?A_Brand, version=24)], screenWidth=1512, screenHeight=982, devicePixelRatio=2.0, hardwareConcurrency=8, deviceMemory=16)",
            "22222222-2222-4222-8222-222222222222@1776038400000" to
                "DeviceProfile(formFactor=DESKTOP, userAgent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36, platform=Windows, platformVersion=15.0.0, model=, isMobile=false, brands=[Brand(name=Chromium, version=145), Brand(name=Google Chrome, version=145), Brand(name=Not?A_Brand, version=24)], screenWidth=1920, screenHeight=1080, devicePixelRatio=1.0, hardwareConcurrency=8, deviceMemory=16)",
            "abcdef00-0000-4000-8000-000000000000@1785542400000" to
                "DeviceProfile(formFactor=DESKTOP, userAgent=Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36, platform=macOS, platformVersion=14.5.0, model=, isMobile=false, brands=[Brand(name=Chromium, version=149), Brand(name=Google Chrome, version=149), Brand(name=Not?A_Brand, version=24)], screenWidth=1512, screenHeight=982, devicePixelRatio=2.0, hardwareConcurrency=8, deviceMemory=16)",
        )
    }
}
