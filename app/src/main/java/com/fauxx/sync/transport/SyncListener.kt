package com.fauxx.sync.transport

import com.fauxx.data.model.SyntheticPersona
import com.fauxx.sync.SealedChannel
import com.fauxx.sync.data.PairedPeer
import com.fauxx.sync.data.PairedPeerRepository
import com.fauxx.sync.data.SyncPersonaStore
import com.fauxx.sync.wire.SyncBody
import com.fauxx.sync.wire.SyncConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The inbound sealed-frame listener (E13 #178). Binds a [ServerSocket] on the sync port, accepts
 * one length-prefixed frame per connection, trial-attributes the sender against the current paired
 * set ([SealedChannel.attributeAndOpen]), and upserts a `PersonaUpsert` into the encrypted store.
 *
 * Fail-closed per frame, never per listener: any frame that does not authenticate, exceeds the cap,
 * or carries an unknown kind/version is dropped (debug-logged) and the loop continues. Lifecycle is
 * bound to the user-initiated sync session: [start] when sync is enabled, [stop] (which closes the
 * socket to unblock `accept`) on disable.
 */
@Singleton
class SyncListener @Inject constructor(
    private val sealedChannel: SealedChannel,
    private val pairedPeerRepository: PairedPeerRepository,
    private val syncPersonaStore: SyncPersonaStore
) {
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var job: Job? = null

    /** The port the listener actually bound, or null when not running. */
    @Volatile var boundPort: Int? = null
        private set

    /**
     * Bind [port] (all interfaces) and run the accept loop on [scope]. Idempotent: a second call
     * while already running is a no-op. [onPersona] is invoked after a synced persona is persisted
     * (a transparency hook for the UI; persistence is the gate, not this callback).
     */
    @Synchronized
    fun start(
        scope: CoroutineScope,
        port: Int = SyncConstants.DEFAULT_SYNC_PORT,
        onPersona: (SyntheticPersona, PairedPeer) -> Unit = { _, _ -> }
    ) {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            val socket = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
            } catch (e: Exception) {
                Timber.w(e, "LAN sync: failed to bind inbound listener on port %d", port)
                return@launch
            }
            serverSocket = socket
            boundPort = socket.localPort
            Timber.i("LAN sync: inbound listener bound on %d", socket.localPort)
            serveInbound(socket, onPersona)
        }
    }

    /**
     * Drive the accept loop over an already-bound [serverSocket] until this coroutine is cancelled
     * or the socket is closed. Split out so an instrumented test can bind an ephemeral loopback port
     * and exercise the real socket path (mirror the desktop `serve_inbound`).
     *
     * The loop guard observes THIS coroutine's own Job (via [coroutineScope]'s `isActive`), not a
     * parent scope, so [stop]'s `job.cancel()` deterministically ends the loop once the socket is
     * closed (rather than busy-spinning on a closed socket if only a sibling Job were cancelled).
     * Per-connection handlers are children of this coroutine, so they are cancelled on stop too.
     */
    suspend fun serveInbound(
        serverSocket: ServerSocket,
        onPersona: (SyntheticPersona, PairedPeer) -> Unit = { _, _ -> }
    ) = coroutineScope {
        while (isActive) {
            val client = try {
                serverSocket.accept()
            } catch (e: Exception) {
                if (isActive) {
                    Timber.w(e, "LAN sync: accept failed")
                    continue
                } else {
                    break
                }
            }
            // Handle each connection on its own child coroutine so a slow/malformed peer cannot
            // stall others, and so stop() tears them down with the accept loop.
            launch(Dispatchers.IO) { handleConnection(client, onPersona) }
        }
    }

    private suspend fun handleConnection(
        client: Socket,
        onPersona: (SyntheticPersona, PairedPeer) -> Unit
    ) {
        client.use { socket ->
            socket.soTimeout = IO_TIMEOUT_MS
            val frame = try {
                FrameCodec.readFrame(socket.getInputStream())
            } catch (e: Exception) {
                Timber.d("LAN sync: dropped unreadable inbound frame: %s", e.message)
                return
            }
            val peers = pairedPeerRepository.getAll()
            val opened = sealedChannel.attributeAndOpen(frame, peers)
            if (opened == null) {
                Timber.d("LAN sync: rejected inbound frame (no paired peer authenticates it)")
                return
            }
            val (peer, message) = opened
            when (val body = message.body) {
                is SyncBody.PersonaUpsert -> {
                    syncPersonaStore.upsert(body.persona)
                    Timber.i("LAN sync: applied synced persona %s from %s", body.persona.id, peer.fingerprint)
                    onPersona(body.persona, peer)
                }
            }
        }
    }

    /** Stop the listener: close the socket (to unblock `accept`) and cancel the accept loop. */
    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        boundPort = null
        job?.cancel()
        job = null
    }

    private companion object {
        const val IO_TIMEOUT_MS = 10_000
    }
}
