package com.fauxx.engine.webview

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fauxx.data.crawllist.DomainBlocklist
import com.fauxx.data.model.DnsMode
import com.fauxx.data.model.PoisonProfile
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.dns.CustomDnsRouter
import com.fauxx.network.dns.DnsHealth
import com.fauxx.network.dns.DohPresets
import com.fauxx.util.SystemClockImpl
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * V2 for #227, the real-world check: with the DEVICE's DNS broken, Fauxx still browses, because the
 * real [CustomDnsRouter] resolves over real DNS-over-HTTPS (Quad9, reached by its bootstrap IP).
 *
 * Manual: boot an emulator whose DNS goes nowhere, for example
 * `emulator -avd <avd> -dns-server 192.0.2.1`. The test is skipped unless system DNS is broken
 * while direct IP connectivity works, so it is a no-op in CI and on normal devices.
 */
@RunWith(AndroidJUnit4::class)
class CustomDnsBrokenSystemDnsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun browsingSurvivesBrokenSystemDns() {
        // Assumptions outside runBlocking: thrown from inside a coroutine they are reported as a
        // failure instead of a skip.
        assumeTrue("system DNS must be broken for this check", runCatching { InetAddress.getByName("example.com") }.isFailure)
        assumeTrue("direct IP connectivity is required", canReach("9.9.9.9", 443))
        runBlocking { check() }
    }

    private suspend fun check() {
        val profile = MutableStateFlow(PoisonProfile(dnsMode = DnsMode.DOH, dohProvider = DohPresets.QUAD9.id))
        val repo = mockk<PoisonProfileRepository>(relaxed = true) {
            every { getProfile() } answers { profile.value }
            every { profiles } returns profile
        }
        val router = CustomDnsRouter(repo, SystemClockImpl(), WebViewProxyOverride.SYSTEM)
        val pool = PhantomWebViewPool(
            context, mockk<DomainBlocklist>(relaxed = true), PersonaJarStore(),
            PhantomIdentityProvider.NONE, PhantomBrowsingPrefs.NONE, router,
        )
        try {
            router.start()
            pool.initialize()
            val webView = pool.acquire()
            try {
                instrumentation.runOnMainSync { webView.loadUrl("https://example.com/") }
                assertEquals("Example Domain", awaitTitle(webView, "Example Domain"))
                assertEquals(DnsHealth.Healthy, router.health.value)
            } finally {
                pool.release(webView)
            }
        } finally {
            router.stop()
            pool.destroy()
        }
    }

    private fun canReach(ip: String, port: Int): Boolean =
        runCatching { Socket().use { it.connect(InetSocketAddress(ip, port), 5_000) } }.isSuccess

    private fun awaitTitle(webView: android.webkit.WebView, expected: String): String? {
        var title: String? = null
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync { title = webView.title }
            if (title == expected) return title
            Thread.sleep(250)
        }
        return title
    }
}
