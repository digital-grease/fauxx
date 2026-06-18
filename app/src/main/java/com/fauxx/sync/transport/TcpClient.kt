package com.fauxx.sync.transport

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

    /** Insert or update the resolved address for a recipient (keyed on base64url public key). */
    fun setRoute(peerPublicKey: String, addr: InetSocketAddress) {
        routes[peerPublicKey] = addr
    }

    /** The current route for a peer, if any. */
    fun routeFor(peerPublicKey: String): InetSocketAddress? = routes[peerPublicKey]

    /** Drop all routes (e.g. on a network change; identity is the key, the IP is a re-resolvable hint). */
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

    private fun resolveAddr(peer: PairedPeer): InetSocketAddress? {
        routes[peer.publicKey]?.let { return it }
        // Fall back to the QR/stored host hint only when it is a numeric address: mDNS `.local.`
        // names do not resolve through InetAddress, so those rely on the NSD-fed route instead.
        val host = peer.host ?: return null
        if (host.isBlank() || host.endsWith(".local.") || host.endsWith(".local")) return null
        return try {
            InetSocketAddress(host, peer.port).takeUnless { it.isUnresolved }
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val IO_TIMEOUT_MS = 10_000
    }
}
