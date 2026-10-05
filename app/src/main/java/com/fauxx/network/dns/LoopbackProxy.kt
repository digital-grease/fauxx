package com.fauxx.network.dns

import androidx.annotation.VisibleForTesting
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Per-run proxy credentials (#227). The [secret] is what keeps other apps out; it is 192 random
 * bits, fresh for every proxy, never persisted or logged. The [realm] is NOT secret (every 407
 * announces it); the WebView client checks it only so it answers the current proxy's challenge.
 */
class ProxyCredentials(val user: String, val secret: String, val realm: String) {
    internal val expectedHeader: ByteArray =
        ("Basic " + Base64.getEncoder().encodeToString("$user:$secret".toByteArray(Charsets.UTF_8)))
            .toByteArray(Charsets.ISO_8859_1)

    /** Constant-time, so response timing reveals nothing about how much of a guess was right. */
    internal fun matches(header: String?): Boolean =
        header != null && MessageDigest.isEqual(header.toByteArray(Charsets.ISO_8859_1), expectedHeader)

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
 * - **Authenticated.** Loopback traffic never enters a VpnService tunnel, so without a password any
 *   app the user has firewalled could find this port and reach the internet as Fauxx.
 * - **Bounded before authentication.** Any local app can connect, so unauthenticated connections
 *   get a short total deadline to send their request head and their own small cap, separate from
 *   the tunnel cap. Otherwise a few dozen idle sockets would starve the WebView into 503s with
 *   health still reading healthy: an accidental fail-CLOSED.
 * - **Public targets only.** Fauxx's traffic never needs the device or the LAN, and behind a proxy
 *   Chromium no longer sees target addresses, so its own local-network protections are blind. A
 *   page that rebinds its name to 192.168.x.x through DNS gets a 502 here. A filtering resolver's
 *   0.0.0.0 fails fast for the same reason.
 * - **Its own bounded thread pool**, never `Dispatchers.IO`: a WebView opens many parallel
 *   connections, and blocking pumps there would starve Room and the rest of the app's IO.
 */
class LoopbackProxy(
    private val resolver: HostResolver,
    private val credentials: ProxyCredentials,
    private val maxTunnels: Int = 48,
    private val maxPending: Int = 16,
    private val connectTimeoutMs: Int = 10_000,
    private val idleTimeoutMs: Long = 300_000L,
    private val headDeadlineMs: Long = 3_000L,
    private val connector: HappyEyeballs.Connector = HappyEyeballs.PLAIN_SOCKETS,
    /** Called once if the accept loop dies on its own (not via [stop]). */
    private val onDied: (Throwable) -> Unit = {},
) {
    private val server = ServerSocket()
    private val open: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    private val pending = AtomicInteger(0)

    /**
     * Connections admitted but not yet authenticated. Admission happens on pool threads, not in
     * accept order, so tests wait on this rather than on timing.
     */
    @VisibleForTesting
    internal fun pendingCount(): Int = pending.get()
    private val tunnels = AtomicInteger(0)
    @Volatile private var stopped = false

    private val threadCount = AtomicInteger(0)
    private val executor = ThreadPoolExecutor(
        0, (maxTunnels * 2) + maxPending, 30L, TimeUnit.SECONDS, SynchronousQueue(),
    ) { r -> Thread(r, "fauxx-proxy-${threadCount.incrementAndGet()}").apply { isDaemon = true } }

    val port: Int get() = server.localPort

    /** Bind to 127.0.0.1 on an ephemeral port and start accepting. Returns the port. */
    fun start(): Int {
        server.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), 0), BACKLOG)
        thread(isDaemon = true, name = "fauxx-proxy-accept") { acceptLoop() }
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

    private fun acceptLoop() {
        var consecutiveErrors = 0
        while (!stopped) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                if (stopped) return
                // A transient error (EMFILE under load, say) is not death: back off and retry.
                // Only a persistently failing socket counts, and then the router fails open.
                if (++consecutiveErrors > MAX_ACCEPT_ERRORS || server.isClosed) {
                    Timber.w(e, "Loopback proxy accept loop died")
                    onDied(e)
                    return
                }
                Thread.sleep(ACCEPT_BACKOFF_MS)
                continue
            }
            consecutiveErrors = 0
            open += client
            try {
                executor.execute { serve(client) }
            } catch (e: RejectedExecutionException) {
                close(client)
            }
        }
    }

    private fun serve(client: Socket) {
        if (pending.incrementAndGet() > maxPending) {
            pending.decrementAndGet()
            respond(client, "503 Service Unavailable")
            return
        }
        var authenticated = false
        try {
            val target = readRequest(client) ?: return close(client)
            pending.decrementAndGet()
            authenticated = true
            tunnel(client, target)
        } catch (e: Exception) {
            // Everything, InterruptedException included: an uncaught throwable on a pool worker
            // reaches the app's default handler and kills the process.
            close(client)
        } finally {
            if (!authenticated) pending.decrementAndGet()
        }
    }

    /**
     * Read and authenticate the request head within [headDeadlineMs] in total. Responds and returns
     * null on anything but a well-formed, authenticated CONNECT.
     */
    private fun readRequest(client: Socket): Pair<String, Int>? {
        val head = readHead(client, headDeadlineMs) ?: return null
        val lines = head.split("\r\n")
        val requestLine = lines.first().split(" ")
        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }.toMap()

        if (!credentials.matches(headers["proxy-authorization"])) {
            respond(client, "407 Proxy Authentication Required", "Proxy-Authenticate: Basic realm=\"${credentials.realm}\"")
            return null
        }
        if (requestLine.size < 2 || requestLine[0] != "CONNECT") {
            respond(client, "405 Method Not Allowed")
            return null
        }
        return parseAuthority(requestLine[1]) ?: run {
            respond(client, "400 Bad Request")
            null
        }
    }

    private fun tunnel(client: Socket, target: Pair<String, Int>) {
        if (tunnels.incrementAndGet() > maxTunnels) {
            tunnels.decrementAndGet()
            return respond(client, "503 Service Unavailable")
        }
        try {
            val (host, port) = target
            val addresses = addressesFor(host)?.filter(::isPublicTarget)?.takeIf { it.isNotEmpty() }
                ?: return respond(client, "502 Bad Gateway")

            val upstream = try {
                HappyEyeballs.connect(addresses, port, connectTimeoutMs, connector = connector)
            } catch (e: IOException) {
                return respond(client, "504 Gateway Timeout")
            }
            open += upstream
            try {
                client.getOutputStream().apply { write(ESTABLISHED); flush() }
                val lastActivity = AtomicLong(System.nanoTime())
                client.soTimeout = IDLE_POLL_MS
                upstream.soTimeout = IDLE_POLL_MS
                try {
                    executor.execute { pipe(client.getInputStream(), upstream.getOutputStream(), client, upstream, lastActivity) }
                } catch (e: RejectedExecutionException) {
                    close(client); close(upstream); return
                }
                pipe(upstream.getInputStream(), client.getOutputStream(), client, upstream, lastActivity)
            } catch (e: Exception) {
                close(upstream)
                throw e
            }
        } finally {
            tunnels.decrementAndGet()
        }
    }

    private fun addressesFor(host: String): List<InetAddress>? {
        parseIpLiteral(host)?.let { return listOf(it) }
        return when (val r = resolver.resolve(host)) {
            is Resolution.Addresses -> r.addresses
            Resolution.NoSuchHost, is Resolution.Failure -> null
        }
    }

    /**
     * Pump one direction until EOF or until BOTH directions have been idle for [idleTimeoutMs].
     * The idle clock is shared, so a long one-way stream (a download, server-sent events) keeps the
     * quiet direction alive instead of having its read timeout tear the tunnel down mid-transfer.
     */
    private fun pipe(from: InputStream, to: OutputStream, a: Socket, b: Socket, lastActivity: AtomicLong) {
        val buffer = ByteArray(BUFFER_BYTES)
        val idleNanos = TimeUnit.MILLISECONDS.toNanos(idleTimeoutMs)
        try {
            while (true) {
                val n = try {
                    from.read(buffer)
                } catch (e: SocketTimeoutException) {
                    if (System.nanoTime() - lastActivity.get() < idleNanos) continue else break
                }
                if (n < 0) break
                lastActivity.set(System.nanoTime())
                to.write(buffer, 0, n)
                to.flush()
            }
        } catch (_: IOException) {
            // Closed from either side; both ends are torn down below.
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
        private const val MAX_HEAD_BYTES = 16 * 1024
        private const val BUFFER_BYTES = 16 * 1024
        private const val IDLE_POLL_MS = 30_000
        private const val MAX_ACCEPT_ERRORS = 20
        private const val ACCEPT_BACKOFF_MS = 200L
        private val ESTABLISHED = "HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        private val TERMINATOR = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        private val IPV4 = Regex("""(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}""")

        /**
         * Read up to the blank line ending the request head, byte by byte so nothing past it is
         * consumed, within [deadlineMs] IN TOTAL. A per-read timeout alone would let a client
         * trickle one byte at a time and hold the connection for hours.
         */
        fun readHead(socket: Socket, deadlineMs: Long): String? {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMs)
            val input = socket.getInputStream()
            val out = ByteArrayOutputStream()
            var matched = 0
            while (out.size() < MAX_HEAD_BYTES) {
                val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (remainingMs <= 0) return null
                socket.soTimeout = remainingMs.toInt().coerceAtLeast(1)
                val b = try { input.read() } catch (_: IOException) { -1 }
                if (b < 0) return null
                out.write(b)
                matched = when {
                    b.toByte() == TERMINATOR[matched] -> matched + 1
                    b.toByte() == TERMINATOR[0] -> 1
                    else -> 0
                }
                if (matched == TERMINATOR.size) {
                    return out.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n")
                }
            }
            return null
        }

        /**
         * `host:port` or `[v6]:port` into its parts, or null if malformed. A bare host containing a
         * colon is rejected: IPv6 must be bracketed, so nothing ambiguous reaches a resolver.
         */
        fun parseAuthority(authority: String): Pair<String, Int>? {
            val (host, portText) = if (authority.startsWith("[")) {
                val end = authority.indexOf(']')
                if (end < 0 || authority.getOrNull(end + 1) != ':') return null
                val inner = authority.substring(1, end)
                if (parseIpLiteral(inner) !is Inet6Address) return null
                inner to authority.substring(end + 2)
            } else {
                val colon = authority.lastIndexOf(':')
                if (colon <= 0) return null
                val host = authority.substring(0, colon)
                if (host.contains(':')) return null
                host to authority.substring(colon + 1)
            }
            val port = portText.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
                ?.takeIf { it in 1..65535 } ?: return null
            if (host.isBlank()) return null
            return host to port
        }

        /**
         * The address [host] spells out literally, or null if it is a name. Strict: dotted-quad
         * IPv4 with no leading zeros, or IPv6 (validated by the parser). Anything else is a name
         * and goes to the custom resolver, never to the system one by accident: a lenient check
         * would hand `999.1.1.1` or `01.2.3.4` to getaddrinfo.
         */
        fun parseIpLiteral(host: String): InetAddress? = when {
            IPV4.matches(host) -> InetAddress.getByName(host)
            // IPv6 syntax only: hex digits, colons and dots (embedded IPv4), at least two colons.
            // On Android, getByName hands anything it cannot parse numerically to the system
            // resolver, so only strings that are already IPv6-shaped may reach it.
            host.count { it == ':' } >= 2 && host.all { it == ':' || it == '.' || it.isDigit() || it.lowercaseChar() in 'a'..'f' } ->
                runCatching { InetAddress.getByName(host) }.getOrNull()?.takeIf { it is Inet6Address }
            else -> null
        }

        /** Whether [address] is somewhere a public web page could legitimately be. */
        fun isPublicTarget(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress
            ) return false
            val b = address.address
            return when (address) {
                is Inet4Address -> {
                    val first = b[0].toInt() and 0xFF
                    val second = b[1].toInt() and 0xFF
                    !(first == 0 || // 0.0.0.0/8, sinkhole answers included
                        (first == 100 && second in 64..127) || // CGNAT 100.64.0.0/10
                        first >= 240) // reserved and broadcast
                }
                is Inet6Address -> (b[0].toInt() and 0xFE) != 0xFC // ULA fc00::/7
                else -> false
            }
        }
    }
}
