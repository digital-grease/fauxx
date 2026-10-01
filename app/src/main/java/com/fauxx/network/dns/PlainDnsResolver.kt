package com.fauxx.network.dns

import org.minidns.dnsmessage.DnsMessage
import org.minidns.dnsmessage.Question
import org.minidns.dnsname.InvalidDnsNameException
import org.minidns.record.InternetAddressRR
import org.minidns.record.Record
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A plain DNS server the user names by address (#227, PR 2): an IP and a port, typically 53.
 *
 * By IP only, never by name: resolving the server's own name would go through the very system
 * resolver the user is routing around. Loopback and private addresses are allowed on purpose
 * (a Pi-hole or a local forwarder is a legitimate choice); addresses that cannot be a server are
 * not.
 */
data class PlainDnsServer(val address: InetAddress, val port: Int) {
    companion object {
        const val DEFAULT_PORT = 53

        /**
         * Parse `1.1.1.1`, `9.9.9.9:5353`, `2606:4700::1111`, `[2606:4700::1111]:53`, or an
         * IPv4-mapped `::ffff:1.2.3.4`, or return null. Numeric addresses only, validated without
         * any lookup; the unspecified address, multicast, and unscoped link-local are refused.
         */
        fun parse(text: String): PlainDnsServer? {
            val t = text.trim()
            if (t.isEmpty()) return null
            val (host, portText) = when {
                t.startsWith("[") -> {
                    val end = t.indexOf(']')
                    if (end < 0) return null
                    val rest = t.substring(end + 1)
                    if (rest.isNotEmpty() && !rest.startsWith(":")) return null
                    t.substring(1, end) to rest.removePrefix(":").ifEmpty { null }
                }
                t.count { it == ':' } == 1 -> t.substringBefore(':') to t.substringAfter(':')
                else -> t to null // a bare IPv4, or a bare IPv6 with no port
            }
            val address = numericAddress(host) ?: return null
            if (address.isAnyLocalAddress || address.isMulticastAddress || address.isLinkLocalAddress) return null
            val port = when (portText) {
                null -> DEFAULT_PORT
                else -> portText.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toIntOrNull()
                    ?.takeIf { it in 1..65535 } ?: return null
            }
            return PlainDnsServer(address, port)
        }

        /** [LoopbackProxy.parseIpLiteral], plus IPv4-mapped IPv6, which Java hands back as IPv4. */
        private fun numericAddress(host: String): InetAddress? {
            LoopbackProxy.parseIpLiteral(host)?.let { return it }
            if (!host.startsWith("::ffff:", ignoreCase = true)) return null
            val v4 = host.substring("::ffff:".length)
            return LoopbackProxy.parseIpLiteral(v4) as? Inet4Address
        }
    }
}

/**
 * Plain DNS to a server the user chose (#227, PR 2). MiniDNS builds and parses the messages (a
 * mature parser for untrusted packets); the network I/O is done here, because MiniDNS's own
 * transport turned out to be a poor fit (both reviewed against its 1.1.1 bytecode):
 * - it falls back to TCP on ANY UDP failure, a lost packet included, so one dropped datagram cost
 *   the full timeout and then a TCP attempt: a healthy server read as "slow" and tripped the
 *   fail-open breaker on lossy mobile networks;
 * - it reads UDP replies on an unconnected socket and aborts the query on the first packet with the
 *   wrong ID, so any stray datagram turned a good lookup into a failure.
 *
 * Here: a UDP socket CONNECTED to the server (the kernel drops other sources), one retransmit,
 * replies that do not match (ID, QR bit, question) discarded rather than fatal, TCP only when the
 * reply is truncated, and one overall [deadlineMs] for the whole lookup, kept under the breaker's
 * 3 s slow threshold. A and AAAA are asked in parallel.
 *
 * Contract, as for DoH: an ANSWER is final (addresses; or NXDOMAIN / no records, which is
 * [Resolution.NoSuchHost]); only a lookup where no type got an answer is a [Resolution.Failure].
 * A partial answer counts: an A answer is kept when the AAAA query is dropped or refused, which
 * some middleboxes do, instead of sending the name to the system resolver.
 */
class PlainDnsResolver(
    private val server: PlainDnsServer,
    private val attemptTimeoutMs: Int = 1_000,
    private val deadlineMs: Long = 2_500L,
) : HostResolver {

    private sealed interface Outcome {
        data class Answer(val addresses: List<InetAddress>) : Outcome
        data object NoName : Outcome
        data class Fail(val cause: Throwable) : Outcome
    }

    override fun resolve(host: String): Resolution {
        val questions = try {
            listOf(Question(host, Record.TYPE.A), Question(host, Record.TYPE.AAAA))
        } catch (e: InvalidDnsNameException) {
            return Resolution.NoSuchHost // not a name any server could answer; never a resolver fault
        } catch (e: IllegalArgumentException) {
            // MiniDNS's IDN conversion rejects over-long labels this way, before its own checks.
            return Resolution.NoSuchHost
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMs)
        val futures = questions.map { q -> EXECUTOR.submit(Callable { outcomeOf(q, deadline) }) }
        val outcomes = futures.map { f ->
            try {
                f.get(deadlineMs + GRACE_MS, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                f.cancel(true)
                Outcome.Fail(e)
            }
        }
        val addresses = outcomes.filterIsInstance<Outcome.Answer>().flatMap { it.addresses }
        return when {
            addresses.isNotEmpty() -> Resolution.Addresses(addresses)
            outcomes.any { it is Outcome.NoName || it is Outcome.Answer } -> Resolution.NoSuchHost
            else -> Resolution.Failure((outcomes.first() as Outcome.Fail).cause)
        }
    }

    private fun outcomeOf(question: Question, deadline: Long): Outcome = try {
        val response = exchange(question, deadline)
        when (response.responseCode) {
            DnsMessage.RESPONSE_CODE.NO_ERROR -> {
                val found = response.answerSection.filter { it.type == question.type }
                    .mapNotNull { (it.payload as? InternetAddressRR<*>)?.inetAddress }
                if (found.isEmpty()) Outcome.NoName else Outcome.Answer(found)
            }
            DnsMessage.RESPONSE_CODE.NX_DOMAIN -> Outcome.NoName
            else -> Outcome.Fail(IOException("DNS server answered ${response.responseCode}"))
        }
    } catch (e: IOException) {
        Outcome.Fail(e)
    } catch (e: RuntimeException) {
        Outcome.Fail(e)
    }

    private fun exchange(question: Question, deadline: Long): DnsMessage {
        val query = DnsMessage.builder()
            .setQuestion(question)
            .setRecursionDesired(true)
            .setId(RANDOM.nextInt(0x10000))
            .build()
        val udp = udpExchange(query, deadline)
        return if (udp.truncated) tcpExchange(query, deadline) else udp
    }

    private fun udpExchange(query: DnsMessage, deadline: Long): DnsMessage {
        val bytes = query.toArray()
        DatagramSocket().use { socket ->
            socket.connect(server.address, server.port)
            val buffer = ByteArray(UDP_BUFFER_BYTES)
            repeat(UDP_ATTEMPTS) {
                socket.send(DatagramPacket(bytes, bytes.size))
                val attemptEnd = minOf(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(attemptTimeoutMs.toLong()))
                while (true) {
                    val remaining = TimeUnit.NANOSECONDS.toMillis(attemptEnd - System.nanoTime())
                    if (remaining <= 0) break
                    socket.soTimeout = remaining.toInt().coerceAtLeast(1)
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        break
                    }
                    val reply = runCatching { DnsMessage(buffer.copyOf(packet.length)) }.getOrNull() ?: continue
                    if (matches(query, reply)) return reply
                    // A stray or forged datagram: ignore it and keep waiting for the real reply.
                }
                if (System.nanoTime() >= deadline) throw SocketTimeoutException("no DNS reply before the deadline")
            }
        }
        throw SocketTimeoutException("no DNS reply after $UDP_ATTEMPTS attempts")
    }

    private fun tcpExchange(query: DnsMessage, deadline: Long): DnsMessage {
        val remainingMs = { TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).toInt() }
        if (remainingMs() <= 0) throw SocketTimeoutException("no time left for TCP")
        Socket().use { socket ->
            socket.connect(InetSocketAddress(server.address, server.port), remainingMs().coerceAtLeast(1))
            socket.soTimeout = remainingMs().coerceAtLeast(1)
            val bytes = query.toArray()
            socket.getOutputStream().apply {
                write(byteArrayOf((bytes.size shr 8).toByte(), bytes.size.toByte()))
                write(bytes)
                flush()
            }
            val input = DataInputStream(socket.getInputStream())
            val length = input.readUnsignedShort()
            val payload = ByteArray(length).also(input::readFully)
            val reply = DnsMessage(payload)
            if (!matches(query, reply)) throw IOException("TCP reply did not match the query")
            return reply
        }
    }

    /** A real reply to THIS query: same ID, the response bit set, and the same question. */
    private fun matches(query: DnsMessage, reply: DnsMessage): Boolean =
        reply.id == query.id && reply.qr && reply.questions == query.questions

    private companion object {
        const val UDP_ATTEMPTS = 2
        const val UDP_BUFFER_BYTES = 4_096
        const val GRACE_MS = 500L
        val RANDOM = SecureRandom()

        /** Daemon threads for the parallel A/AAAA queries; bounded by the proxy's own concurrency. */
        val EXECUTOR: ExecutorService = Executors.newCachedThreadPool { r ->
            Thread(r, "fauxx-plain-dns").apply { isDaemon = true }
        }
    }
}

/**
 * Whether something on the path MAY intercept plain DNS (#227, PR 2), the classic hijack test: ask
 * a DNS question of an address where no DNS server can exist ([probeTarget], from the TEST-NET-1
 * documentation range), over UDP only. On a clean network nothing answers. VPN and firewall apps
 * that capture all port-53 traffic (Rethink's DNS-leak protection, for one) answer it themselves,
 * and then the plain server the user picked is not the one answering.
 *
 * Heuristic, not proof: a router that forces all port 53 to the user's own Pi-hole also answers
 * (harmless if that Pi-hole is the chosen server), and an interceptor that only grabs well-known
 * public resolvers does not. Hence "may".
 */
object DnsInterceptionCheck {
    val DEFAULT_PROBE: PlainDnsServer = PlainDnsServer(InetAddress.getByName("192.0.2.53"), 53)

    fun isIntercepted(probeTarget: PlainDnsServer = DEFAULT_PROBE, timeoutMs: Int = 1_500): Boolean {
        val query = DnsMessage.builder()
            .setQuestion(Question("example.com", Record.TYPE.A))
            .setRecursionDesired(true)
            .setId(SecureRandom().nextInt(0x10000))
            .build()
        val bytes = query.toArray()
        return try {
            DatagramSocket().use { socket ->
                socket.connect(probeTarget.address, probeTarget.port)
                socket.soTimeout = timeoutMs
                socket.send(DatagramPacket(bytes, bytes.size))
                val buffer = ByteArray(512)
                socket.receive(DatagramPacket(buffer, buffer.size))
                true // anything answered an address that cannot be a DNS server
            }
        } catch (e: IOException) {
            false // timeout or unreachable: nobody is listening, as on a clean network
        } catch (e: RuntimeException) {
            false
        }
    }
}
