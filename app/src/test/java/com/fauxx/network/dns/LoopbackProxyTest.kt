package com.fauxx.network.dns

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val head = LoopbackProxy.readHead(s, 5_000) ?: ""
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
        assertNull("an unbracketed colon is ambiguous and refused", LoopbackProxy.parseAuthority("a:b:443"))
        assertNull(LoopbackProxy.parseAuthority("[not-v6]:443"))
        assertNull(LoopbackProxy.parseAuthority("example.com:+443"))
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

    @Test
    fun `a client that trickles its request head is cut off at the total deadline`() {
        val slow = LoopbackProxy(resolver = { resolution }, credentials = credentials, headDeadlineMs = 300)
        slow.start()
        try {
            Socket("127.0.0.1", slow.port).use { s ->
                s.soTimeout = 5_000
                val started = System.nanoTime()
                // One byte, well inside any per-read timeout, then nothing more.
                s.getOutputStream().apply { write('C'.code); flush() }
                assertEquals("the proxy must close the connection", -1, runCatching { s.getInputStream().read() }.getOrDefault(-1))
                val ms = (System.nanoTime() - started) / 1_000_000
                assertTrue("cut off after ${ms}ms", ms < 3_000)
            }
        } finally {
            slow.stop()
        }
    }

    /** Wait up to 10 s for [condition]. */
    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `idle unauthenticated connections cannot exhaust the tunnel slots`() {
        val small = LoopbackProxy(
            resolver = { resolution }, credentials = credentials, connector = toEcho,
            maxPending = 2, maxTunnels = 4, headDeadlineMs = 5_000,
        )
        small.start()
        val idle = List(2) { Socket("127.0.0.1", small.port) }
        try {
            // Admission runs on pool threads in no particular order, so a probe sent too early can
            // take a pending slot ahead of an idle socket (that flaked on CI). Wait until both idle
            // sockets hold the slots, then probe.
            awaitTrue("both idle sockets to be pending") { small.pendingCount() == 2 }
            // Pending slots are full; a third unauthenticated connection is turned away...
            Socket("127.0.0.1", small.port).use { s ->
                s.soTimeout = 5_000
                val head = LoopbackProxy.readHead(s, 5_000) ?: ""
                assertTrue(head, head.startsWith("HTTP/1.1 503"))
            }
            // ...but once the idle ones close and are released, real tunnels get through.
            idle.forEach { it.close() }
            awaitTrue("the idle sockets to be released") { small.pendingCount() == 0 }
            Socket("127.0.0.1", small.port).use { s ->
                s.soTimeout = 5_000
                s.getOutputStream().write(("CONNECT example.com:443 HTTP/1.1\r\n" + auth() + "\r\n\r\n").toByteArray())
                val head = LoopbackProxy.readHead(s, 5_000) ?: ""
                assertTrue(head, head.startsWith("HTTP/1.1 200"))
            }
        } finally {
            idle.forEach { runCatching { it.close() } }
            small.stop()
        }
    }

    @Test
    fun `LAN, CGNAT and sinkhole targets are refused whether literal or resolved`() {
        for (literal in listOf("192.168.1.1", "10.0.0.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "[fd00::1]", "[fe80::1]")) {
            val (s, head) = request("CONNECT $literal:443 HTTP/1.1", auth())
            s.close()
            assertTrue("$literal -> $head", head.startsWith("HTTP/1.1 502"))
        }
        resolution = Resolution.Addresses(listOf(InetAddress.getByName("192.168.1.1")))
        val (s, head) = request("CONNECT rebind.example:443 HTTP/1.1", auth())
        s.close()
        assertTrue("a name rebound to the LAN -> $head", head.startsWith("HTTP/1.1 502"))
    }

    @Test
    fun `IP literal detection is strict`() {
        assertEquals(InetAddress.getByName("203.0.113.7"), LoopbackProxy.parseIpLiteral("203.0.113.7"))
        assertEquals(InetAddress.getByName("2001:db8::1"), LoopbackProxy.parseIpLiteral("2001:db8::1"))
        for (name in listOf("999.1.1.1", "01.2.3.4", "1.2.3", "example.com", "a:b", "dead:beef")) {
            assertNull("$name must be treated as a name", LoopbackProxy.parseIpLiteral(name))
        }
    }

    @Test
    fun `credential matching rejects near misses`() {
        assertTrue(credentials.matches("Basic " + Base64.getEncoder().encodeToString("fauxx:s3cret".toByteArray())))
        assertFalse(credentials.matches("Basic " + Base64.getEncoder().encodeToString("fauxx:s3creT".toByteArray())))
        assertFalse(credentials.matches(null))
        assertFalse(credentials.matches(""))
    }

    @Test
    fun `stopping the proxy while a connection is racing addresses crashes nothing`() {
        val escaped = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> escaped += e }
        val v6 = InetAddress.getByName("2001:db8::7")
        val hanging = LoopbackProxy(
            resolver = { Resolution.Addresses(listOf(v6, remote)) },
            credentials = credentials,
            connector = { _, _, _ -> Thread.sleep(10_000); throw IOException("never") },
        )
        hanging.start()
        try {
            val s = Socket("127.0.0.1", hanging.port)
            s.getOutputStream().write(("CONNECT example.com:443 HTTP/1.1\r\n" + auth() + "\r\n\r\n").toByteArray())
            Thread.sleep(300) // the worker is now inside the address race
            hanging.stop()
            Thread.sleep(300)
            s.close()
            assertTrue("nothing may escape a proxy worker: $escaped", escaped.isEmpty())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
