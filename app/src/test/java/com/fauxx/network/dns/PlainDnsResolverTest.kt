package com.fauxx.network.dns

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class PlainDnsResolverTest {

    private val loopback = InetAddress.getByName("127.0.0.1")

    /** A UDP DNS responder on loopback that answers every question via [respond]. */
    /** [respond] returns (rcode, ipv4 or null), or null to stay silent for that question. */
    private inner class FakeDnsServer(
        private val respond: (qtype: Int) -> Pair<Int, ByteArray?>?,
        private val beforeReply: ((DatagramPacket) -> Unit)? = null,
    ) {
        val socket = DatagramSocket(0, loopback)
        val port: Int get() = socket.localPort
        init {
            thread(isDaemon = true) {
                val buf = ByteArray(512)
                while (!socket.isClosed) {
                    val packet = DatagramPacket(buf, buf.size)
                    runCatching { socket.receive(packet) }.getOrNull() ?: break
                    val query = packet.data.copyOf(packet.length)
                    var i = 12
                    while (query[i].toInt() != 0) i += (query[i].toInt() and 0xFF) + 1
                    val qtype = ((query[i + 1].toInt() and 0xFF) shl 8) or (query[i + 2].toInt() and 0xFF)
                    val question = query.copyOfRange(12, i + 5)
                    val (rcode, ipv4) = respond(qtype) ?: continue
                    beforeReply?.invoke(packet)
                    val answers = if (ipv4 != null) 1 else 0
                    val out = java.io.ByteArrayOutputStream()
                    out.write(query, 0, 2) // echo the id
                    out.write(byteArrayOf(0x81.toByte(), (0x80 or rcode).toByte(), 0, 1, 0, answers.toByte(), 0, 0, 0, 0))
                    out.write(question)
                    if (ipv4 != null) {
                        out.write(byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4))
                        out.write(ipv4)
                    }
                    val bytes = out.toByteArray()
                    socket.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
                }
            }
        }
        fun close() = socket.close()
    }

    private val servers = mutableListOf<FakeDnsServer>()

    @After
    fun tearDown() = servers.forEach { it.close() }

    private fun server(respond: (Int) -> Pair<Int, ByteArray?>?) = FakeDnsServer(respond).also { servers += it }

    private fun resolverFor(s: FakeDnsServer) =
        PlainDnsResolver(PlainDnsServer(loopback, s.port), attemptTimeoutMs = 300, deadlineMs = 1_000)

    @Test
    fun `an answer comes back as addresses`() {
        val s = server { qtype -> if (qtype == 1) 0 to byteArrayOf(93, -72, -40, 34) else 0 to null }
        assertEquals(Resolution.Addresses(listOf(InetAddress.getByName("93.184.216.34"))), resolverFor(s).resolve("example.com"))
    }

    @Test
    fun `NXDOMAIN is the server's answer`() {
        val s = server { 3 to null }
        assertEquals(Resolution.NoSuchHost, resolverFor(s).resolve("nope.example.com"))
    }

    @Test
    fun `SERVFAIL and REFUSED are failures`() {
        for (rcode in listOf(2, 5)) {
            val s = server { rcode to null }
            assertTrue("rcode $rcode", resolverFor(s).resolve("example.com") is Resolution.Failure)
        }
    }

    @Test
    fun `a server that never answers is a failure`() {
        val silent = DatagramSocket(0, loopback) // bound, never replies
        try {
            val r = PlainDnsResolver(PlainDnsServer(loopback, silent.localPort), attemptTimeoutMs = 200, deadlineMs = 600)
                .resolve("example.com")
            assertTrue("expected Failure, got $r", r is Resolution.Failure)
        } finally {
            silent.close()
        }
    }

    @Test
    fun `interception is detected when anything answers the probe address`() {
        val s = server { 0 to byteArrayOf(10, 0, 0, 1) }
        assertTrue(DnsInterceptionCheck.isIntercepted(PlainDnsServer(loopback, s.port), timeoutMs = 500))
    }

    @Test
    fun `no interception when nothing answers the probe address`() {
        val silent = DatagramSocket(0, loopback)
        try {
            assertFalse(DnsInterceptionCheck.isIntercepted(PlainDnsServer(loopback, silent.localPort), timeoutMs = 300))
        } finally {
            silent.close()
        }
    }

    @Test
    fun `server addresses parse strictly, by IP only`() {
        assertEquals(PlainDnsServer(InetAddress.getByName("9.9.9.9"), 53), PlainDnsServer.parse("9.9.9.9"))
        assertEquals(PlainDnsServer(InetAddress.getByName("9.9.9.9"), 5353), PlainDnsServer.parse(" 9.9.9.9:5353 "))
        assertEquals(PlainDnsServer(InetAddress.getByName("2606:4700::1111"), 53), PlainDnsServer.parse("2606:4700::1111"))
        assertEquals(PlainDnsServer(InetAddress.getByName("2606:4700::1111"), 853), PlainDnsServer.parse("[2606:4700::1111]:853"))
        assertEquals(PlainDnsServer(InetAddress.getByName("1.2.3.4"), 53), PlainDnsServer.parse("::ffff:1.2.3.4"))
        assertEquals("a Pi-hole on the LAN is a legitimate server", PlainDnsServer(InetAddress.getByName("192.168.1.2"), 53), PlainDnsServer.parse("192.168.1.2"))
        for (bad in listOf("", "dns.example", "dns.example:53", "999.1.1.1", "9.9.9.9:0", "9.9.9.9:99999", "[2606::1]x", "1.2.3",
            "0.0.0.0", "::", "224.0.0.251", "fe80::1")) {
            assertNull("'$bad' must be rejected", PlainDnsServer.parse(bad))
        }
    }

    @Test
    fun `an A answer is kept when the AAAA query is dropped, instead of failing over`() {
        val s = server { qtype -> if (qtype == 1) 0 to byteArrayOf(93, -72, -40, 34) else null }
        assertEquals(Resolution.Addresses(listOf(InetAddress.getByName("93.184.216.34"))), resolverFor(s).resolve("example.com"))
    }

    @Test
    fun `a stray datagram with the wrong ID does not spoil the lookup`() {
        lateinit var s: FakeDnsServer
        s = FakeDnsServer(
            respond = { qtype -> if (qtype == 1) 0 to byteArrayOf(93, -72, -40, 34) else 0 to null },
            beforeReply = { packet ->
                // Send garbage with a different ID first, from the same server socket.
                val bogus = byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
                s.socket.send(DatagramPacket(bogus, bogus.size, packet.address, packet.port))
            },
        ).also { servers += it }
        assertEquals(Resolution.Addresses(listOf(InetAddress.getByName("93.184.216.34"))), resolverFor(s).resolve("example.com"))
    }

    @Test
    fun `a name no server could answer is no-such-host, not a resolver failure`() {
        val s = server { 0 to null }
        assertEquals(Resolution.NoSuchHost, resolverFor(s).resolve("a".repeat(64) + ".example.com"))
    }

    @Test
    fun `a dead server fails within the deadline, under the breaker's slow threshold`() {
        val silent = DatagramSocket(0, loopback)
        try {
            val started = System.nanoTime()
            PlainDnsResolver(PlainDnsServer(loopback, silent.localPort)).resolve("example.com")
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue("took ${ms}ms", ms < 3_000)
        } finally {
            silent.close()
        }
    }
}
