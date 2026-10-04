package com.fauxx.network.dns

import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/**
 * Pins how `okhttp-dnsoverhttps` reports each outcome, against a MockWebServer that speaks real
 * DNS wire format (RFC 8484 POST). The fail-open policy depends on telling a resolver that ANSWERED
 * "no such host" apart from one that could not answer, and that distinction rests on how the
 * library shapes its exceptions, which nothing else in the build would notice changing.
 */
class DohHostResolverTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun resolverFor(server: MockWebServer) =
        DohHostResolver(server.url("/dns-query").toString(), bootstrap = emptyList())

    @Test
    fun `an answer comes back as addresses`() {
        server.dispatcher = dnsDispatcher { qtype -> if (qtype == TYPE_A) answerA(byteArrayOf(93, -72, -40, 34)) else noError() }
        server.start()

        val result = resolverFor(server).resolve("example.com")

        assertEquals(Resolution.Addresses(listOf(InetAddress.getByName("93.184.216.34"))), result)
    }

    @Test
    fun `NXDOMAIN is an answer, not a failure`() {
        server.dispatcher = dnsDispatcher { nxDomain() }
        server.start()

        assertEquals(Resolution.NoSuchHost, resolverFor(server).resolve("does-not-exist.example.com"))
    }

    @Test
    fun `a sinkhole answer is still an answer`() {
        server.dispatcher = dnsDispatcher { qtype -> if (qtype == TYPE_A) answerA(byteArrayOf(0, 0, 0, 0)) else noError() }
        server.start()

        assertEquals(
            Resolution.Addresses(listOf(InetAddress.getByName("0.0.0.0"))),
            resolverFor(server).resolve("tracker.example.com"),
        )
    }

    @Test
    fun `an unreachable resolver is a failure`() {
        server.start()
        val url = server.url("/dns-query").toString()
        server.shutdown()

        val result = DohHostResolver(url, bootstrap = emptyList()).resolve("example.com")

        assertTrue("expected Failure, got $result", result is Resolution.Failure)
    }

    @Test
    fun `a resolver returning server errors is a failure`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(503)
        }
        server.start()

        val result = resolverFor(server).resolve("example.com")

        assertTrue("expected Failure, got $result", result is Resolution.Failure)
    }

    @Test
    fun `SERVFAIL is a failure, so the breaker trips instead of every page failing`() {
        server.dispatcher = dnsDispatcher { header(FLAGS_SERVFAIL, answers = 0) }
        server.start()

        val result = resolverFor(server).resolve("example.com")

        assertTrue("expected Failure, got $result", result is Resolution.Failure)
    }

    @Test
    fun `a DoH endpoint whose own name cannot be resolved is a failure, not no-such-host`() {
        // The Pi-hole case: DoH-bypass blocklists answer NXDOMAIN for dns.nextdns.io and friends.
        server.start()
        val url = "http://doh.test:${server.port}/dns-query"

        val result = DohHostResolver(url, bootstrap = emptyList(), endpointResolver = { Resolution.NoSuchHost })
            .resolve("example.com")

        assertTrue("expected Failure, got $result", result is Resolution.Failure)
    }

    @Test
    fun `stale bootstrap addresses fall back to resolving the endpoint's name`() {
        server.dispatcher = dnsDispatcher { qtype -> if (qtype == TYPE_A) answerA(byteArrayOf(93, -72, -40, 34)) else noError() }
        server.start()
        val url = "http://doh.test:${server.port}/dns-query"
        // 127.0.0.2 refuses the connection at once, like a bootstrap IP the provider retired.
        val stale = listOf(InetAddress.getByName("127.0.0.2"))
        val endpoint = HostResolver { host ->
            if (host == "doh.test") Resolution.Addresses(listOf(InetAddress.getByName("127.0.0.1"))) else Resolution.NoSuchHost
        }

        val result = DohHostResolver(url, bootstrap = stale, endpointResolver = endpoint).resolve("example.com")

        assertEquals(Resolution.Addresses(listOf(InetAddress.getByName("93.184.216.34"))), result)
    }

    @Test
    fun `a server IP is used instead of looking up the URL's name`() {
        // The self-hosted case from #227: the URL names dns.lan, which nothing outside the LAN
        // can resolve. With the server's IP given, the name must never be looked up.
        server.dispatcher = dnsDispatcher { qtype -> if (qtype == TYPE_A) answerA(byteArrayOf(93, -72, -40, 34)) else noError() }
        server.start()
        val asked = mutableListOf<String>()
        val endpoint = HostResolver { host -> asked += host; Resolution.Failure(IllegalStateException("no lookup expected")) }

        val result = DohHostResolver(
            "http://dns.lan:${server.port}/dns-query",
            bootstrap = listOf(InetAddress.getByName("127.0.0.1")),
            endpointResolver = endpoint,
        ).resolve("example.com")

        assertEquals(Resolution.Addresses(listOf(InetAddress.getByName("93.184.216.34"))), result)
        assertTrue("the endpoint's name must not be looked up, asked: $asked", asked.isEmpty())
    }

    @Test
    fun `a self-signed DoH server is refused while the certificate check is on`() {
        useSelfSignedHttps(certificateFor = "localhost")

        val result = DohHostResolver(server.url("/dns-query").toString(), bootstrap = emptyList()).resolve("example.com")

        assertTrue("expected Failure, got $result", result is Resolution.Failure)
    }

    @Test
    fun `skipping the certificate check reaches a self-signed DoH server`() {
        useSelfSignedHttps(certificateFor = "localhost")

        val result = DohHostResolver(server.url("/dns-query").toString(), bootstrap = emptyList(), skipCertificateCheck = true)
            .resolve("example.com")

        assertEquals(Resolution.Addresses(listOf(InetAddress.getByName("93.184.216.34"))), result)
    }

    @Test
    fun `skipping the certificate check also accepts a certificate for another name`() {
        // The 192.168.6.7-with-a-self-signed-cert setup: the certificate rarely names what the URL
        // says, and "skip" has to mean the name is not checked either.
        useSelfSignedHttps(certificateFor = "some-other-name.test")

        val result = DohHostResolver(
            "https://dns.lan:${server.port}/dns-query",
            bootstrap = listOf(InetAddress.getByName("127.0.0.1")),
            skipCertificateCheck = true,
        ).resolve("example.com")

        assertEquals(Resolution.Addresses(listOf(InetAddress.getByName("93.184.216.34"))), result)
    }

    private fun useSelfSignedHttps(certificateFor: String) {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName(certificateFor).build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.dispatcher = dnsDispatcher { qtype -> if (qtype == TYPE_A) answerA(byteArrayOf(93, -72, -40, 34)) else noError() }
        server.start()
    }

    // --- Minimal DNS wire format ------------------------------------------------------------

    private fun dnsDispatcher(answer: (qtype: Int) -> Buffer) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val query = request.body.readByteArray()
            // Header (12 bytes), then QNAME labels up to the zero byte, then QTYPE.
            var i = 12
            while (query[i].toInt() != 0) i += (query[i].toInt() and 0xFF) + 1
            val qtype = ((query[i + 1].toInt() and 0xFF) shl 8) or (query[i + 2].toInt() and 0xFF)
            val question = query.copyOfRange(12, i + 5)
            val body = Buffer().write(answer(qtype).readByteArray()).let { built ->
                // Splice the echoed question in after the header the builder wrote.
                val bytes = built.readByteArray()
                Buffer().write(bytes, 0, 12).write(question).write(bytes, 12, bytes.size - 12)
            }
            return MockResponse().setHeader("Content-Type", "application/dns-message").setBody(body)
        }
    }

    private fun header(flags: Int, answers: Int): Buffer =
        Buffer().writeShort(0).writeShort(flags).writeShort(1).writeShort(answers).writeShort(0).writeShort(0)

    private fun noError(): Buffer = header(FLAGS_NOERROR, answers = 0)

    private fun nxDomain(): Buffer = header(FLAGS_NXDOMAIN, answers = 0)

    private fun answerA(ip: ByteArray): Buffer = header(FLAGS_NOERROR, answers = 1)
        .writeShort(0xC00C) // name: pointer to the question
        .writeShort(TYPE_A).writeShort(1).writeInt(60).writeShort(4).write(ip)

    private companion object {
        const val TYPE_A = 1
        const val FLAGS_NOERROR = 0x8180
        const val FLAGS_NXDOMAIN = 0x8183
        const val FLAGS_SERVFAIL = 0x8182
    }
}
