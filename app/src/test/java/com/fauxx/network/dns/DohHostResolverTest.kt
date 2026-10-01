package com.fauxx.network.dns

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
    }
}
