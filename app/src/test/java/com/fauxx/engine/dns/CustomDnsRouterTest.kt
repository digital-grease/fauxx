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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    private class FakeOverride(
        var supported: Boolean = true,
        var accept: Boolean = true,
        var clears: Boolean = true,
        var clearDelayMs: Long = 0L,
    ) : WebViewProxyOverride {
        val calls = mutableListOf<String>()
        @Volatile var port: Int? = null
        override fun isSupported() = supported
        override suspend fun set(port: Int): Boolean {
            calls += "set"; if (accept) this.port = port; return accept
        }
        override suspend fun clear(): Boolean {
            calls += "clear"
            if (clearDelayMs > 0) delay(clearDelayMs)
            if (clears) port = null
            return clears
        }
    }

    private val profile = MutableStateFlow(PoisonProfile())
    private val repo: PoisonProfileRepository = mockk(relaxed = true) {
        every { getProfile() } answers { profile.value }
        every { profiles } returns profile
        // Writes land in the same flow the router watches, as DataStore's would.
        io.mockk.coEvery { updateProfile(any()) } coAnswers {
            val transform = firstArg<(PoisonProfile) -> PoisonProfile>()
            synchronized(profile) { profile.value = transform(profile.value) }
        }
    }
    private val override = FakeOverride()
    @Volatile private var intercepted = false
    private val networkChanged = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private val router = CustomDnsRouter(
        repo, FakeClock(0L), override, DnsInterceptionProbe { intercepted },
        object : NetworkChanges { override val changes = networkChanged },
    )

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
    fun `a WebView without proxy support never touches the override`() = runBlocking {
        override.supported = false
        profile.value = doh()
        router.start()
        assertTrue(override.calls.isEmpty())
        assertEquals(DnsHealth.Off, router.health.value)
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
    fun `an invalid server IP for a custom URL degrades instead of routing`() = runBlocking {
        profile.value = doh(provider = DohPresets.CUSTOM_ID, url = "https://dns.lan/dns-query")
            .copy(dohCustomServerIp = "dns.lan")
        router.start()
        assertTrue(override.calls.isEmpty())
        assertTrue(router.health.value is DnsHealth.Degraded)
    }

    @Test
    fun `a custom URL with a server IP routes through the proxy`() = runBlocking {
        profile.value = doh(provider = DohPresets.CUSTOM_ID, url = "https://dns.lan/dns-query")
            .copy(dohCustomServerIp = "192.168.6.7")
        router.start()
        assertTrue("the WebView must be pointed at the proxy", override.port != null)
        assertEquals(DnsHealth.Healthy, router.health.value)
    }

    @Test
    fun `turning the certificate check off while running swaps in a fresh proxy`() = runBlocking {
        // The setting must reach the resolver, which only happens when the router rebuilds it.
        profile.value = doh(provider = DohPresets.CUSTOM_ID, url = "https://dns.lan/dns-query")
        router.start()
        val firstPort = override.port!!

        profile.value = profile.value.copy(dohSkipCertificateCheck = true)
        withTimeout(5_000) { while (override.port == null || override.port == firstPort) delay(20) }
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
        // The old proxy is gone; its credentials stay answerable only so challenges already in
        // flight during the swap do not fail a page load, and they open nothing.
        assertTrue("the old proxy must be stopped", runCatching { Socket("127.0.0.1", firstPort).close() }.isFailure)
    }

    @Test
    fun `switching back to system DNS clears the override`() = runBlocking {
        profile.value = doh()
        router.start()
        profile.value = PoisonProfile()
        withTimeout(5_000) { while (override.port != null) delay(20) }
        assertEquals(DnsHealth.Off, router.health.value)
    }

    @Test
    fun `a clear that fails keeps the proxy running instead of black-holing the WebView`() = runBlocking {
        profile.value = doh()
        router.start()
        val port = override.port!!
        val realm = realmOf(port)

        override.clears = false
        profile.value = PoisonProfile()
        withTimeout(5_000) { while (router.health.value !is DnsHealth.Degraded) delay(20) }

        // The WebView may still point at the old port, so it must still answer and authenticate.
        assertEquals("the stranded proxy must still be serving", realm, realmOf(port))
        assertNotNull(router.credentialsFor("127.0.0.1", realm))

        // A later successful clear finally retires it.
        override.clears = true
        profile.value = doh(provider = DohPresets.CLOUDFLARE.id)
        withTimeout(5_000) { while (override.port == null || override.port == port) delay(20) }
        val refused = runCatching { Socket("127.0.0.1", port).close() }.isFailure
        assertTrue("the stranded proxy must be stopped once a clear succeeds", refused)
    }

    @Test
    fun `an unknown preset id falls back to the default instead of degrading`() = runBlocking {
        profile.value = doh(provider = "a-preset-from-a-future-version")
        router.start()
        assertNotNull(override.port)
        assertEquals(DnsHealth.Healthy, router.health.value)
    }

    @Test
    fun `stop completes its teardown even when the caller is cancelled`() = runBlocking {
        profile.value = doh()
        router.start()
        val port = override.port!!
        override.clearDelayMs = 300L
        // Start inside stop() and get stuck in the slow clear, THEN cancel the caller.
        val job = launch(start = CoroutineStart.UNDISPATCHED) { router.stop() }
        job.cancel()
        job.join()
        withTimeout(5_000) { while (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) delay(20) }
        assertNull(override.port)
    }

    private fun plain(server: String = "9.9.9.9", routeNoise: Boolean = false) =
        PoisonProfile(dnsMode = DnsMode.PLAIN, plainDnsServer = server, routeDnsNoise = routeNoise)

    @Test
    fun `plain DNS routes through the proxy like DoH`() = runBlocking {
        profile.value = plain()
        router.start()
        assertNotNull(override.port)
        assertEquals(DnsHealth.Healthy, router.health.value)
    }

    @Test
    fun `an invalid plain server degrades instead of routing`() = runBlocking {
        profile.value = plain(server = "dns.example")
        router.start()
        assertTrue(override.calls.isEmpty())
        assertTrue(router.health.value is DnsHealth.Degraded)
    }

    @Test
    fun `intercepted plain DNS is reported even though answers keep arriving`() = runBlocking {
        intercepted = true
        profile.value = plain()
        router.start()
        withTimeout(5_000) { while (router.health.value !is DnsHealth.Degraded) delay(20) }
        assertTrue((router.health.value as DnsHealth.Degraded).intercepted)
        assertNotNull("browsing keeps going: fail-open", override.port)
    }

    @Test
    fun `a network change re-probes, and a clean result clears the interception warning`() = runBlocking {
        intercepted = true
        profile.value = plain()
        router.start()
        withTimeout(5_000) { while (router.health.value !is DnsHealth.Degraded) delay(20) }

        // The fake has no replay: an emit before the router subscribes is dropped, and on a slow
        // runner the first probe can finish before that subscription exists. Wait for it.
        withTimeout(5_000) { while (networkChanged.subscriptionCount.value == 0) delay(20) }

        intercepted = false // the user exempted Fauxx in their VPN app, say
        networkChanged.emit(Unit)
        withTimeout(5_000) { while (router.health.value != DnsHealth.Healthy) delay(20) }

        intercepted = true // and later joins a network that hijacks DNS
        networkChanged.emit(Unit)
        withTimeout(5_000) { while (router.health.value !is DnsHealth.Degraded) delay(20) }
    }

    @Test
    fun `a WebView without proxy support stays quietly off, with no dashboard line`() = runBlocking {
        override.supported = false
        profile.value = plain()
        router.start()
        assertEquals(DnsHealth.Off, router.health.value)
    }

    @Test
    fun `interception is only checked for plain DNS`() = runBlocking {
        intercepted = true
        profile.value = doh()
        router.start()
        delay(300)
        assertEquals(DnsHealth.Healthy, router.health.value)
    }

    @Test
    fun `DNS noise uses the custom resolver only when the user turned it on`() = runBlocking {
        profile.value = plain(routeNoise = false)
        router.start()
        assertNull(router.noiseResolver())

        profile.value = plain(routeNoise = true)
        // The toggle is read live; flipping it must not restart the proxy.
        val port = override.port
        withTimeout(5_000) { while (router.noiseResolver() == null) delay(20) }
        assertEquals(port, override.port)

        router.stop()
        assertNull("nothing to route through once stopped", router.noiseResolver())
    }

    // --- Trust on first use for a self-signed custom DoH server (release review) ---

    private val tlsServers = mutableListOf<okhttp3.mockwebserver.MockWebServer>()

    @After
    fun closeTlsServers() = tlsServers.forEach { runCatching { it.shutdown() } }

    /** An HTTPS server with a self-signed certificate. The handshake is all these tests need. */
    private fun selfSignedServer(): Pair<okhttp3.mockwebserver.MockWebServer, okhttp3.tls.HeldCertificate> {
        val certificate = okhttp3.tls.HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val server = okhttp3.mockwebserver.MockWebServer().also { tlsServers += it }
        server.useHttps(okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                okhttp3.mockwebserver.MockResponse().setResponseCode(503)
        }
        server.start()
        return server to certificate
    }

    private fun selfSigned(server: okhttp3.mockwebserver.MockWebServer, pinnedKey: String = "") = PoisonProfile(
        dnsMode = DnsMode.DOH, dohProvider = DohPresets.CUSTOM_ID,
        dohCustomUrl = "https://localhost:${server.port}/dns-query",
        dohSkipCertificateCheck = true, dohPinnedKey = pinnedKey, routeDnsNoise = true,
    )

    @Test
    fun `the first key a self-signed server presents is saved, and the resolver rebuilt around it`() = runBlocking {
        val (server, certificate) = selfSignedServer()
        profile.value = selfSigned(server)
        router.start()
        val firstPort = override.port!!

        router.noiseResolver()!!.resolve("example.com") // first contact
        withTimeout(5_000) { while (profile.value.dohPinnedKey.isEmpty()) delay(20) }

        assertEquals(com.fauxx.network.dns.CertificatePin.fingerprint(certificate.certificate), profile.value.dohPinnedKey)
        withTimeout(5_000) { while (override.port == firstPort) delay(20) }
    }

    @Test
    fun `a server whose key is not the pinned one is reported as a changed certificate`() = runBlocking {
        val (server, _) = selfSignedServer()
        val someoneElse = okhttp3.tls.HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        profile.value = selfSigned(server, pinnedKey = com.fauxx.network.dns.CertificatePin.fingerprint(someoneElse.certificate))
        router.start()

        router.noiseResolver()!!.resolve("example.com")

        val health = withTimeout(5_000) {
            var h = router.health.value
            while (!(h is DnsHealth.Degraded && h.certificateChanged)) { delay(20); h = router.health.value }
            h
        }
        assertTrue(health is DnsHealth.Degraded && health.certificateChanged)
        assertEquals("a refused key is never saved over the pin", com.fauxx.network.dns.CertificatePin.fingerprint(someoneElse.certificate), profile.value.dohPinnedKey)
    }

    @Test
    fun `a pin is ignored while the certificate check is on`() = runBlocking {
        // A stray stored pin must neither rebuild nor change verification when the switch is off.
        profile.value = doh(provider = DohPresets.CUSTOM_ID, url = "https://dns.lan/dns-query")
        router.start()
        val port = override.port!!
        profile.value = profile.value.copy(dohPinnedKey = "stray")
        delay(300)
        assertEquals(port, override.port)
    }
}
