package com.fauxx.engine.dns

import com.fauxx.data.model.DnsMode
import com.fauxx.data.model.PoisonProfile
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.webview.WebViewProxyOverride
import com.fauxx.network.dns.DnsHealth
import com.fauxx.network.dns.DohPresets
import com.fauxx.support.FakeClock
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

class CustomDnsRouterTest {

    private class FakeOverride(var supported: Boolean = true, var accept: Boolean = true) : WebViewProxyOverride {
        val calls = mutableListOf<String>()
        @Volatile var port: Int? = null
        override fun isSupported() = supported
        override suspend fun set(port: Int): Boolean {
            calls += "set"; if (accept) this.port = port; return accept
        }
        override suspend fun clear(): Boolean { calls += "clear"; port = null; return true }
    }

    private val profile = MutableStateFlow(PoisonProfile())
    private val repo: PoisonProfileRepository = mockk(relaxed = true) {
        every { getProfile() } answers { profile.value }
        every { profiles } returns profile
    }
    private val override = FakeOverride()
    private val router = CustomDnsRouter(repo, FakeClock(0L), override)

    @After
    fun tearDown() = runBlocking { router.stop() }

    private fun doh(provider: String = DohPresets.DEFAULT_ID, url: String = "") =
        PoisonProfile(dnsMode = DnsMode.DOH, dohProvider = provider, dohCustomUrl = url)

    /** The realm the running proxy demands, read back off the wire. */
    private fun realmOf(port: Int): String {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5_000
            s.getOutputStream().write("CONNECT example.com:443 HTTP/1.1\r\n\r\n".toByteArray())
            val head = s.getInputStream().bufferedReader().readText()
            return Regex("realm=\"([^\"]+)\"").find(head)!!.groupValues[1]
        }
    }

    @Test
    fun `system mode never touches the WebView`() = runBlocking {
        router.start()
        assertTrue(override.calls.isEmpty())
        assertEquals(DnsHealth.Off, router.health.value)
    }

    @Test
    fun `DoH starts a proxy, points the WebView at it, and answers only its own challenge`() = runBlocking {
        profile.value = doh()
        router.start()

        val port = assertNotNull(override.port).let { override.port!! }
        assertEquals(DnsHealth.Healthy, router.health.value)
        val realm = realmOf(port)
        assertNotNull(router.credentialsFor("127.0.0.1", realm))
        assertNull("a site's own challenge must never get the secret", router.credentialsFor("example.com", realm))
        assertNull("a different realm on loopback must not get it either", router.credentialsFor("127.0.0.1", "other"))
    }

    @Test
    fun `stop clears the WebView override before anything else and forgets the credentials`() = runBlocking {
        profile.value = doh()
        router.start()
        val realm = realmOf(override.port!!)
        router.stop()

        assertEquals(listOf("set", "clear"), override.calls)
        assertNull(router.credentialsFor("127.0.0.1", realm))
        assertEquals(DnsHealth.Off, router.health.value)
    }

    @Test
    fun `a WebView without proxy support degrades instead of routing`() = runBlocking {
        override.supported = false
        profile.value = doh()
        router.start()
        assertTrue(override.calls.isEmpty())
        assertTrue(router.health.value is DnsHealth.Degraded)
    }

    @Test
    fun `a WebView that refuses the override is left on direct connections`() = runBlocking {
        override.accept = false
        profile.value = doh()
        router.start()
        assertEquals(listOf("set", "clear"), override.calls)
        assertTrue(router.health.value is DnsHealth.Degraded)
    }

    @Test
    fun `an invalid custom URL degrades instead of routing`() = runBlocking {
        profile.value = doh(provider = DohPresets.CUSTOM_ID, url = "http://not-https.example/dns-query")
        router.start()
        assertTrue(override.calls.isEmpty())
        assertTrue(router.health.value is DnsHealth.Degraded)
    }

    @Test
    fun `a settings change while running swaps in a fresh proxy with fresh credentials`() = runBlocking {
        profile.value = doh()
        router.start()
        val firstPort = override.port!!
        val firstRealm = realmOf(firstPort)

        profile.value = doh(provider = DohPresets.CLOUDFLARE.id)
        withTimeout(5_000) { while (override.port == null || override.port == firstPort) delay(20) }

        assertNotEquals(firstRealm, realmOf(override.port!!))
        assertNull(router.credentialsFor("127.0.0.1", firstRealm))
    }

    @Test
    fun `switching back to system DNS clears the override`() = runBlocking {
        profile.value = doh()
        router.start()
        profile.value = PoisonProfile()
        withTimeout(5_000) { while (override.port != null) delay(20) }
        assertEquals(DnsHealth.Off, router.health.value)
    }
}
