package com.fauxx.sync.transport

import androidx.annotation.VisibleForTesting
import com.fauxx.data.model.SyntheticPersona
import com.fauxx.sync.SealedChannel
import com.fauxx.sync.data.PairedPeer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Raised when there is no resolved route to a recipient (mirror `send_without_a_route_fails_closed`). */
class NoRouteException(message: String) : Exception(message)

/** Outcome of a fan-out push: how many peers received the frame, and the names that failed. */
data class PushResult(val sent: Int, val failedPeerNames: List<String>)

/**
 * The sealed-frame TCP client (E13 #178). Opens a fresh [Socket] per frame, writes one
 * length-prefixed sealed frame (contract section 6.1), and closes. A routing map (peer base64url
 * public key -> resolved address) mirrors the desktop `RoutingTable`; it is fed by mDNS discovery
 * (Phase 7) and may fall back to the QR/stored host hint when that hint is a numeric address.
 */
@Singleton
class TcpClient @Inject constructor(
    private val sealedChannel: SealedChannel
) {
    private val routes = ConcurrentHashMap<String, InetSocketAddress>()

    /**
     * User-supplied manual peer address (#213). Used as a last-resort route for ANY paired peer
     * that mDNS could not resolve — the path to recovery when discovery is blocked by a VPN/proxy
     * app or Wi-Fi AP client-isolation. Safe because the sealed channel authenticates by paired
     * public key regardless of the IP: a frame sent to the wrong host simply fails to authenticate.
     */
    @Volatile
    private var manualFallback: InetSocketAddress? = null

    /** Insert or update the resolved address for a recipient (keyed on base64url public key). */
    fun setRoute(peerPublicKey: String, addr: InetSocketAddress) {
        routes[peerPublicKey] = addr
    }

    /** The current route for a peer, if any. */
    fun routeFor(peerPublicKey: String): InetSocketAddress? = routes[peerPublicKey]

    /** Set (or clear, with null) the manual last-resort address for mDNS-blocked networks (#213). */
    fun setManualFallback(addr: InetSocketAddress?) {
        manualFallback = addr
    }

    /** The current manual fallback address, if the user set one (#213). */
    fun manualFallback(): InetSocketAddress? = manualFallback

    /**
     * Drop all mDNS-resolved routes (e.g. on a network change; identity is the key, the IP is a
     * re-resolvable hint). The user's [manualFallback] is intentionally NOT cleared here — it is a
     * deliberate override the user re-sets when their address changes.
     */
    fun clearRoutes() = routes.clear()

    /** Open a connection, write one frame, close. Runs on [Dispatchers.IO] with timeouts. */
    suspend fun send(addr: InetSocketAddress, frame: ByteArray) = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            socket.connect(addr, CONNECT_TIMEOUT_MS)
            socket.soTimeout = IO_TIMEOUT_MS
            FrameCodec.writeFrame(socket.getOutputStream(), frame)
            // One frame per connection; `use {}` closes (and thus signals end-of-frame) on exit.
        }
    }

    /**
     * Seal the persona for [peer] and push it. Throws [NoRouteException] if the peer's address
     * cannot be resolved from the routing table or a numeric host hint (fail closed).
     */
    suspend fun pushPersonaTo(peer: PairedPeer, persona: SyntheticPersona) {
        val addr = resolveAddr(peer)
            ?: throw NoRouteException("no LAN route to ${peer.name} (peer not discovered or not advertising)")
        val frame = sealedChannel.sealPersonaFor(peer, persona)
        send(addr, frame)
    }

    /** Push to every paired peer, sealing per recipient. Aggregates successes and per-peer failures. */
    suspend fun pushPersonaToAll(persona: SyntheticPersona, peers: List<PairedPeer>): PushResult {
        var sent = 0
        val failed = mutableListOf<String>()
        for (peer in peers) {
            try {
                pushPersonaTo(peer, persona)
                sent++
            } catch (e: Exception) {
                Timber.w(e, "LAN sync: push to %s failed", peer.fingerprint)
                failed += peer.name
            }
        }
        return PushResult(sent, failed)
    }

    @VisibleForTesting
    internal fun resolveAddr(peer: PairedPeer): InetSocketAddress? {
        routes[peer.publicKey]?.let { return it }
        // Fall back to the QR/stored host hint only when it is a numeric address: mDNS `.local.`
        // names do not resolve through InetAddress, so those rely on the NSD-fed route instead.
        val host = peer.host
        if (host != null && host.isNotBlank() &&
            !host.endsWith(".local.") && !host.endsWith(".local")
        ) {
            runCatching { InetSocketAddress(host, peer.port).takeUnless { it.isUnresolved } }
                .getOrNull()
                ?.let { return it }
        }
        // #213: last resort — the user-supplied manual address (mDNS blocked by VPN/proxy/isolation).
        return manualFallback
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val IO_TIMEOUT_MS = 10_000

        /**
         * Parse a user-entered "host" or "host:port" (or "[ipv6]:port") into an [InetSocketAddress]
         * for the manual fallback (#213), defaulting the port to [defaultPort]. Returns null on
         * blank input, a bad port, or an address that does not resolve to a literal. Intended for
         * literal IPs; call off the main thread since a hostname would trigger DNS resolution.
         */
        fun parseHostPort(input: String, defaultPort: Int): InetSocketAddress? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null
            val (host, port) = when {
                trimmed.startsWith("[") -> { // [IPv6] or [IPv6]:port
                    val close = trimmed.indexOf(']')
                    if (close < 1) return null
                    val p = trimmed.substring(close + 1).removePrefix(":")
                        .let { if (it.isEmpty()) defaultPort else it.toIntOrNull() ?: return null }
                    trimmed.substring(1, close) to p
                }
                trimmed.count { it == ':' } == 1 -> { // host:port (IPv4 or hostname)
                    val idx = trimmed.lastIndexOf(':')
                    val p = trimmed.substring(idx + 1).toIntOrNull() ?: return null
                    trimmed.substring(0, idx) to p
                }
                else -> trimmed to defaultPort // bare IPv4 / hostname / bare IPv6
            }
            if (host.isBlank() || port !in 1..65535) return null
            return runCatching { InetSocketAddress(host, port).takeUnless { it.isUnresolved } }
                .getOrNull()
        }
    }
}
