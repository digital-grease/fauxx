package com.fauxx.network.dns

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import kotlin.concurrent.thread

class LoopbackProxyTest {

    private val credentials = ProxyCredentials(user = "fauxx", secret = "s3cret", realm = "fauxx-test")

    /** A public-looking (TEST-NET-3) address the resolver hands out; the connector maps it home. */
    private val remote = InetAddress.getByName("203.0.113.7")

    private val echo = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")).also { server ->
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching { s.getInputStream().copyTo(s.getOutputStream()) }
                    runCatching { s.close() }
                }
            }
        }
    }

    private val toEcho = HappyEyeballs.Connector { address, _, timeout ->
        if (address != remote) throw IOException("unexpected address $address")
        Socket().apply { connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), echo.localPort), timeout) }
    }

    private var resolution: Resolution = Resolution.Addresses(listOf(remote))
    private val resolved = mutableListOf<String>()
    private val proxy = LoopbackProxy(
        resolver = { host -> resolved += host; resolution },
        credentials = credentials,
        connector = toEcho,
    ).also { it.start() }

    @After
    fun tearDown() {
        proxy.stop()
        echo.close()
    }

    private fun auth(c: ProxyCredentials = credentials) =
        "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString("${c.user}:${c.secret}".toByteArray())

    /** Send a request head and return the response's status line, leaving the socket open. */
    private fun request(vararg lines: String): Pair<Socket, String> {
        val s = Socket("127.0.0.1", proxy.port).apply { soTimeout = 5_000 }
        s.getOutputStream().write((lines.joinToString("\r\n") + "\r\n\r\n").toByteArray())
        val head = LoopbackProxy.readHead(s.getInputStream()) ?: ""
        return s to head
    }

    @Test
    fun `no credentials gets a 407 naming the realm`() {
        val (s, head) = request("CONNECT example.com:443 HTTP/1.1", "Host: example.com:443")
        s.close()
        assertTrue(head, head.startsWith("HTTP/1.1 407"))
        assertTrue(head, head.contains("Proxy-Authenticate: Basic realm=\"fauxx-test\""))
        assertTrue("an unauthenticated request must never trigger a lookup", resolved.isEmpty())
    }

    @Test
    fun `wrong credentials get a 407`() {
        val wrong = ProxyCredentials("fauxx", "guess", "fauxx-test")
        val (s, head) = request("CONNECT example.com:443 HTTP/1.1", auth(wrong))
        s.close()
        assertTrue(head, head.startsWith("HTTP/1.1 407"))
    }

    @Test
    fun `anything but CONNECT is refused`() {
        val (s, head) = request("GET http://example.com/ HTTP/1.1", "Host: example.com", auth())
        s.close()
        assertTrue(head, head.startsWith("HTTP/1.1 405"))
    }

    @Test
    fun `an authenticated CONNECT resolves through the custom resolver and pipes bytes both ways`() {
        val (s, head) = request("CONNECT example.com:443 HTTP/1.1", auth())
        assertTrue(head, head.startsWith("HTTP/1.1 200"))
        assertEquals(listOf("example.com"), resolved)

        s.getOutputStream().apply { write("ping".toByteArray()); flush() }
        val back = ByteArray(4)
        var read = 0
        while (read < 4) read += s.getInputStream().read(back, read, 4 - read)
        assertEquals("ping", String(back))
        s.close()
    }

    @Test
    fun `NXDOMAIN, a failed lookup and a sinkhole answer all fail fast with 502`() {
        for (r in listOf(
            Resolution.NoSuchHost,
            Resolution.Failure(IOException("down")),
            Resolution.Addresses(listOf(InetAddress.getByName("0.0.0.0"))),
        )) {
            resolution = r
            val (s, head) = request("CONNECT blocked.example:443 HTTP/1.1", auth())
            s.close()
            assertTrue("$r -> $head", head.startsWith("HTTP/1.1 502"))
        }
    }

    @Test
    fun `loopback targets are refused without a lookup`() {
        val (s, head) = request("CONNECT 127.0.0.1:${proxy.port} HTTP/1.1", auth())
        s.close()
        assertTrue(head, head.startsWith("HTTP/1.1 502"))
        assertTrue("an IP literal must not go to the resolver", resolved.isEmpty())
    }

    @Test
    fun `stop closes open tunnels`() {
        val (s, head) = request("CONNECT example.com:443 HTTP/1.1", auth())
        assertTrue(head.startsWith("HTTP/1.1 200"))
        proxy.stop()
        assertEquals("the tunnel must be closed by stop()", -1, runCatching { s.getInputStream().read() }.getOrDefault(-1))
        s.close()
    }

    @Test
    fun `authority parsing`() {
        assertEquals("example.com" to 443, LoopbackProxy.parseAuthority("example.com:443"))
        assertEquals("2001:db8::1" to 8443, LoopbackProxy.parseAuthority("[2001:db8::1]:8443"))
        assertNull(LoopbackProxy.parseAuthority("example.com"))
        assertNull(LoopbackProxy.parseAuthority("example.com:0"))
        assertNull(LoopbackProxy.parseAuthority("example.com:99999"))
        assertNull(LoopbackProxy.parseAuthority("[2001:db8::1]443"))
        assertNull(LoopbackProxy.parseAuthority(":443"))
    }

    @Test
    fun `generated credentials are unique per run`() {
        val a = ProxyCredentials.random()
        val b = ProxyCredentials.random()
        assertTrue(a.secret != b.secret && a.realm != b.realm)
        assertTrue(a.realm.startsWith("fauxx-"))
    }
}
