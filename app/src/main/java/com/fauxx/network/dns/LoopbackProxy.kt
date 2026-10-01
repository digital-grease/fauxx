package com.fauxx.network.dns

import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Per-run proxy credentials (#227). The WebView answers the proxy's 407 with these, and only when
 * the challenge comes from 127.0.0.1 with this exact [realm], so a site's own 401 never sees them.
 */
class ProxyCredentials(val user: String, val secret: String, val realm: String) {
    internal val expectedHeader: String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$secret".toByteArray(Charsets.UTF_8))

    companion object {
        fun random(random: SecureRandom = SecureRandom()): ProxyCredentials {
            fun token(bytes: Int) = ByteArray(bytes).also(random::nextBytes)
                .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
            return ProxyCredentials(user = "fauxx", secret = token(24), realm = "fauxx-" + token(6))
        }
    }
}

/**
 * The loopback HTTP CONNECT proxy the WebView is pointed at when the user picks a custom resolver
 * (#227). Chromium hands every hostname to an HTTP proxy unresolved (verified in spike S1), so this
 * is where Fauxx's traffic gets its DNS: [resolver] answers, the proxy connects, and the bytes are
 * piped both ways untouched. TLS stays end to end between Chromium and the site, so the
 * #168/#169 fingerprint is unaffected.
 *
 * - **CONNECT only.** The app forbids cleartext, so plain http never reaches a proxy (S1); anything
 *   else gets 405.
 * - **Authenticated.** Loopback traffic never enters a VPN tunnel, so without a password any app
 *   the user has firewalled could find this port and reach the internet as Fauxx. Every request
 *   must carry [credentials], or it gets a 407.
 * - **No loopback or sinkhole targets.** A filtering resolver's 0.0.0.0 fails fast instead of
 *   timing out, and no page can use the proxy to reach services on the device itself.
 * - **Its own bounded thread pool**, never `Dispatchers.IO`: a WebView opens many parallel
 *   connections, and blocking pumps there would starve Room and the rest of the app's IO.
 */
class LoopbackProxy(
    private val resolver: HostResolver,
    private val credentials: ProxyCredentials,
    private val maxConnections: Int = 48,
    private val connectTimeoutMs: Int = 10_000,
    private val idleTimeoutMs: Int = 300_000,
    private val connector: HappyEyeballs.Connector = HappyEyeballs.PLAIN_SOCKETS,
    /** Called once if the accept loop dies on its own (not via [stop]). */
    private val onDied: (Throwable) -> Unit = {},
) {
    private val server = ServerSocket()
    private val open: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    private val active = AtomicInteger(0)
    @Volatile private var stopped = false

    private val threadCount = AtomicInteger(0)
    private val executor = ThreadPoolExecutor(
        0, maxConnections * 2, 30L, TimeUnit.SECONDS, SynchronousQueue(),
    ) { r -> Thread(r, "fauxx-proxy-${threadCount.incrementAndGet()}").apply { isDaemon = true } }

    val port: Int get() = server.localPort

    /** Bind to 127.0.0.1 on an ephemeral port and start accepting. Returns the port. */
    fun start(): Int {
        server.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), 0), BACKLOG)
        thread(isDaemon = true, name = "fauxx-proxy-accept") {
            try {
                while (!stopped) {
                    val client = server.accept()
                    open += client
                    try {
                        executor.execute { serve(client) }
                    } catch (e: RejectedExecutionException) {
                        close(client)
                    }
                }
            } catch (e: Throwable) {
                if (!stopped) {
                    Timber.w(e, "Loopback proxy accept loop died")
                    onDied(e)
                }
            }
        }
        return port
    }

    fun stop() {
        stopped = true
        runCatching { server.close() }
        // toTypedArray, not toList: sockets leave the set concurrently, and Kotlin's toList sizes
        // the list first and then iterates, which throws when an element vanishes in between.
        open.toTypedArray().forEach(::close)
        executor.shutdownNow()
    }

    private fun serve(client: Socket) {
        if (active.incrementAndGet() > maxConnections) {
            respond(client, "503 Service Unavailable")
            active.decrementAndGet()
            return
        }
        try {
            handle(client)
        } catch (e: IOException) {
            close(client)
        } catch (e: RuntimeException) {
            Timber.w(e, "Loopback proxy connection failed")
            close(client)
        } finally {
            active.decrementAndGet()
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = HEAD_TIMEOUT_MS
        val head = readHead(client.getInputStream()) ?: return close(client)
        val lines = head.split("\r\n")
        val requestLine = lines.first().split(" ")
        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }.toMap()

        if (headers["proxy-authorization"] != credentials.expectedHeader) {
            return respond(client, "407 Proxy Authentication Required", "Proxy-Authenticate: Basic realm=\"${credentials.realm}\"")
        }
        if (requestLine.size < 2 || requestLine[0] != "CONNECT") return respond(client, "405 Method Not Allowed")

        val (host, port) = parseAuthority(requestLine[1]) ?: return respond(client, "400 Bad Request")
        val addresses = addressesFor(host)
            ?.filterNot { it.isLoopbackAddress || it.isAnyLocalAddress }
            ?.takeIf { it.isNotEmpty() }
            ?: return respond(client, "502 Bad Gateway")

        val upstream = try {
            HappyEyeballs.connect(addresses, port, connectTimeoutMs, connector = connector)
        } catch (e: IOException) {
            return respond(client, "504 Gateway Timeout")
        }
        open += upstream

        client.getOutputStream().apply { write(ESTABLISHED); flush() }
        client.soTimeout = idleTimeoutMs
        upstream.soTimeout = idleTimeoutMs
        try {
            executor.execute { pipe(client.getInputStream(), upstream.getOutputStream(), client, upstream) }
        } catch (e: RejectedExecutionException) {
            close(client); close(upstream); return
        }
        pipe(upstream.getInputStream(), client.getOutputStream(), client, upstream)
    }

    private fun addressesFor(host: String): List<InetAddress>? {
        if (isIpLiteral(host)) return listOf(InetAddress.getByName(host))
        return when (val r = resolver.resolve(host)) {
            is Resolution.Addresses -> r.addresses
            Resolution.NoSuchHost, is Resolution.Failure -> null
        }
    }

    private fun pipe(from: InputStream, to: OutputStream, a: Socket, b: Socket) {
        val buffer = ByteArray(BUFFER_BYTES)
        try {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                to.write(buffer, 0, n)
                to.flush()
            }
        } catch (_: IOException) {
            // Closed or idle-timed-out from either side; both ends are torn down below.
        } finally {
            close(a)
            close(b)
        }
    }

    private fun respond(client: Socket, status: String, vararg extra: String) {
        runCatching {
            val head = buildString {
                append("HTTP/1.1 ").append(status).append("\r\n")
                extra.forEach { append(it).append("\r\n") }
                append("Content-Length: 0\r\nConnection: close\r\n\r\n")
            }
            client.getOutputStream().apply { write(head.toByteArray(Charsets.ISO_8859_1)); flush() }
        }
        close(client)
    }

    private fun close(socket: Socket) {
        open -= socket
        runCatching { socket.close() }
    }

    internal companion object {
        const val LOOPBACK = "127.0.0.1"
        private const val BACKLOG = 50
        private const val HEAD_TIMEOUT_MS = 10_000
        private const val MAX_HEAD_BYTES = 16 * 1024
        private const val BUFFER_BYTES = 16 * 1024
        private val ESTABLISHED = "HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.ISO_8859_1)

        /** Read up to the blank line ending the request head, byte by byte so nothing past it is consumed. */
        fun readHead(input: InputStream): String? {
            val out = ByteArrayOutputStream()
            var matched = 0
            val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
            while (out.size() < MAX_HEAD_BYTES) {
                val b = try { input.read() } catch (_: SocketException) { -1 }
                if (b < 0) return null
                out.write(b)
                matched = if (b.toByte() == terminator[matched]) matched + 1 else if (b.toByte() == terminator[0]) 1 else 0
                if (matched == terminator.size) {
                    return out.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n")
                }
            }
            return null
        }

        /** `host:port` or `[v6]:port` into its parts, or null if malformed. */
        fun parseAuthority(authority: String): Pair<String, Int>? {
            val (host, portText) = if (authority.startsWith("[")) {
                val end = authority.indexOf(']')
                if (end < 0 || authority.getOrNull(end + 1) != ':') return null
                authority.substring(1, end) to authority.substring(end + 2)
            } else {
                val colon = authority.lastIndexOf(':')
                if (colon <= 0) return null
                authority.substring(0, colon) to authority.substring(colon + 1)
            }
            val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            if (host.isBlank()) return null
            return host to port
        }

        /** An IPv4 or IPv6 literal, which needs no resolution (and must not be sent to a resolver). */
        fun isIpLiteral(host: String): Boolean =
            host.contains(':') || host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
    }
}
