package com.fauxx.sync.transport

import com.fauxx.sync.SealedChannel
import com.fauxx.sync.data.PairedPeer
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetSocketAddress

/**
 * #213: route resolution and the manual-IP fallback. The sealed channel authenticates by paired
 * key, so a manual address is a safe last resort when mDNS is blocked (VPN/proxy/AP isolation).
 */
class TcpClientTest {

    private val client = TcpClient(mockk<SealedChannel>(relaxed = true))

    private fun peer(host: String?, port: Int = 48173, pk: String = "PEER_PK") =
        PairedPeer(name = "dev", publicKey = pk, fingerprint = "fp", host = host, port = port, pairedAt = 0L)

    // --- parseHostPort ---

    @Test
    fun `parseHostPort accepts a bare IPv4 with the default port`() {
        val addr = TcpClient.parseHostPort("192.168.1.42", 48173)!!
        assertEquals("192.168.1.42", addr.hostString)
        assertEquals(48173, addr.port)
    }

    @Test
    fun `parseHostPort accepts an explicit IPv4 port`() {
        assertEquals(5000, TcpClient.parseHostPort("192.168.1.42:5000", 48173)!!.port)
    }

    @Test
    fun `parseHostPort accepts a bracketed IPv6 with port`() {
        val addr = TcpClient.parseHostPort("[::1]:9000", 48173)
        assertEquals(9000, addr!!.port)
    }

    @Test
    fun `parseHostPort rejects blank, bad port, and out-of-range port`() {
        assertNull(TcpClient.parseHostPort("   ", 48173))
        assertNull(TcpClient.parseHostPort("192.168.1.42:notaport", 48173))
        assertNull(TcpClient.parseHostPort("192.168.1.42:70000", 48173))
    }

    // --- resolveAddr precedence ---

    @Test
    fun `resolveAddr prefers an mDNS-fed route`() {
        val routed = InetSocketAddress("10.0.0.9", 1234)
        client.setRoute("PEER_PK", routed)
        client.setManualFallback(InetSocketAddress("10.0.0.99", 5555))
        assertEquals(routed, client.resolveAddr(peer(host = "10.0.0.5")))
    }

    @Test
    fun `resolveAddr falls back to a numeric host hint when no route`() {
        val addr = client.resolveAddr(peer(host = "10.0.0.5", port = 7000))
        assertEquals("10.0.0.5", addr!!.hostString)
        assertEquals(7000, addr.port)
    }

    @Test
    fun `resolveAddr ignores a dot-local host and uses the manual fallback`() {
        val manual = InetSocketAddress("10.0.0.42", 48173)
        client.setManualFallback(manual)
        assertEquals(manual, client.resolveAddr(peer(host = "mydevice.local.")))
    }

    @Test
    fun `resolveAddr returns null when nothing resolves`() {
        assertNull(client.resolveAddr(peer(host = "mydevice.local.")))
        assertNull(client.resolveAddr(peer(host = null)))
    }

    @Test
    fun `clearRoutes drops mDNS routes but keeps the manual fallback`() {
        val manual = InetSocketAddress("10.0.0.42", 48173)
        client.setRoute("PEER_PK", InetSocketAddress("10.0.0.9", 1234))
        client.setManualFallback(manual)
        client.clearRoutes()
        // route gone -> .local host ignored -> manual fallback remains
        assertEquals(manual, client.resolveAddr(peer(host = "mydevice.local.")))
    }
}
