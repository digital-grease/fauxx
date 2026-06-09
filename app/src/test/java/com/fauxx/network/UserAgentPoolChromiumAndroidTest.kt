package com.fauxx.network

import android.content.Context
import com.fauxx.data.model.PoisonProfile
import com.fauxx.engine.PoisonProfileRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.random.Random

/**
 * Guards the prerequisite that actually closes #168: [UserAgentPool.randomChromiumAndroid] must
 * only ever return Android-Chromium strings, so the WebView path's UA is coherent with the System
 * WebView's always-Android-Chromium TLS handshake. A future asset edit that reintroduces
 * non-Chromium UAs into the search path would fail these tests.
 */
class UserAgentPoolChromiumAndroidTest {

    private val mixed = listOf(
        // Android-Chromium family (the only acceptable outputs)
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/23.0 Chrome/115.0.0.0 Mobile Safari/537.36",
        // Foreign engines / platforms that must never be emitted on the WebView path
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Android 14; Mobile; rv:122.0) Gecko/122.0 Firefox/122.0",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/123.0 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
    )
    private val acceptable = setOf(mixed[0], mixed[1])

    private fun pool(customUa: String? = null): UserAgentPool {
        val json = mixed.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }.toByteArray()
        val context: Context = mockk {
            every { assets } returns mockk {
                every { open("user_agents.json") } answers { ByteArrayInputStream(json) }
            }
        }
        val profileRepo: PoisonProfileRepository = mockk {
            every { getProfile() } returns PoisonProfile(customUserAgent = customUa)
        }
        return UserAgentPool(context, profileRepo, Random(42))
    }

    @Test
    fun `randomChromiumAndroid only returns Android-Chromium strings`() {
        val p = pool()
        repeat(300) {
            val ua = p.randomChromiumAndroid()
            assertTrue("must be Android-Chromium, got: $ua", ua in acceptable)
            assertFalse(ua.contains("Firefox"))
            assertFalse(ua.contains("CriOS"))
            assertFalse(ua.contains("Windows NT"))
        }
    }

    @Test
    fun `a non-Chrome custom UA is ignored on the WebView path`() {
        val p = pool(customUa = "Mozilla/5.0 (X11; Linux x86_64; rv:122.0) Gecko/20100101 Firefox/122.0")
        repeat(50) { assertTrue(p.randomChromiumAndroid() in acceptable) }
    }

    @Test
    fun `a Chromium-Android custom UA is honored`() {
        val custom = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
        val p = pool(customUa = custom)
        assertEquals(custom, p.randomChromiumAndroid())
    }
}
