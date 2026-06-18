package com.fauxx.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.sync.crypto.DeviceIdentity
import com.fauxx.sync.data.PairedPeer
import com.fauxx.sync.data.PairedPeerRepository
import com.fauxx.sync.data.SyncPersonaStore
import com.fauxx.sync.data.SyncedPersonaDao
import com.fauxx.sync.transport.FrameCodec
import com.fauxx.sync.transport.SyncListener
import com.fauxx.sync.wire.SealedFrame
import com.fauxx.sync.wire.SyncMessage
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Inject

/**
 * Instrumented end-to-end LAN sync over a REAL loopback TCP socket (E13 #178), the on-device
 * analogue of the desktop `lan_sync_tcp.rs`. A stranger identity ("alice") seals a `PersonaUpsert`
 * for this device's [SealedChannel] identity and writes it as `[u32 BE len][FXS1 frame]`; the
 * [SyncListener] trial-attributes the sender against the paired set and persists the persona. Covers
 * AC-3 (unpaired rejected), AC-4 (lossless round trip), AC-5 (real framing), AC-6 (encrypted store).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SyncEndToEndTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var sealedChannel: SealedChannel
    @Inject lateinit var pairedPeerRepository: PairedPeerRepository
    @Inject lateinit var syncPersonaStore: SyncPersonaStore
    @Inject lateinit var syncedPersonaDao: SyncedPersonaDao
    @Inject lateinit var syncListener: SyncListener

    private lateinit var serverSocket: ServerSocket
    private lateinit var scope: CoroutineScope
    private var port: Int = 0

    @Before
    fun setUp() {
        hiltRule.inject()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serverSocket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress("127.0.0.1", 0))
        }
        port = serverSocket.localPort
        scope.launch { syncListener.serveInbound(serverSocket) }
    }

    @After
    fun tearDown() {
        runCatching { serverSocket.close() }
        scope.cancel()
    }

    private fun persona(id: String) = SyntheticPersona(
        id = id,
        name = "Round Trip",
        ageRange = "AGE_35_44",
        profession = "TEACHER",
        region = "CANADA",
        interests = setOf(CategoryPool.ACADEMIC, CategoryPool.HISTORY),
        createdAt = 1_700_000_000_000,
        activeUntil = 1_800_000_000_000
    )

    private fun sealForDevice(sender: DeviceIdentity, persona: SyntheticPersona): ByteArray =
        SealedFrame(sender.seal(sealedChannel.publicKey, SyncMessage.personaUpsert(persona).toPlaintext())).toBytes()

    private fun writeFrame(frame: ByteArray) {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 3_000)
            FrameCodec.writeFrame(s.getOutputStream(), frame)
        }
    }

    private suspend fun awaitPersona(id: String): SyntheticPersona? =
        withTimeoutOrNull(3_000) {
            var p = syncPersonaStore.get(id)
            while (p == null) {
                delay(25)
                p = syncPersonaStore.get(id)
            }
            p
        }

    @Test
    fun paired_sender_persona_round_trips_over_real_tcp() = runBlocking {
        val alice = DeviceIdentity.generate()
        pairedPeerRepository.upsert(
            PairedPeer.create("Alice", alice.publicKey, "127.0.0.1", port, 1_700_000_000_000)
        )
        val expected = persona("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        writeFrame(sealForDevice(alice, expected))

        val received = awaitPersona(expected.id)
        assertNotNull("listener must persist the synced persona", received)
        assertEquals(expected, received)
        assertEquals(1_800_000_000_000, received!!.activeUntil)
    }

    @Test
    fun re_delivering_the_same_frame_does_not_duplicate() = runBlocking {
        val alice = DeviceIdentity.generate()
        pairedPeerRepository.upsert(
            PairedPeer.create("Alice", alice.publicKey, "127.0.0.1", port, 1_700_000_000_000)
        )
        val p = persona("cccccccc-cccc-4ccc-8ccc-cccccccccccc")
        val frame = sealForDevice(alice, p)
        writeFrame(frame)
        assertNotNull(awaitPersona(p.id))
        // Re-deliver the identical frame; idempotent upsert keyed on id must converge, not pile up.
        writeFrame(frame)
        delay(300)
        assertEquals(1, syncedPersonaDao.getAll().count { it.id == p.id })
    }

    @Test
    fun stop_terminates_the_listener_without_spinning() = runBlocking {
        // Exercise the PRODUCTION lifecycle path (start binds + launches, stop closes + cancels the
        // accept-loop Job) and assert it actually terminates rather than busy-spinning on the closed
        // socket. Uses an independent scope and an ephemeral port so it does not touch setUp's listener.
        val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            syncListener.start(lifecycleScope, port = 0)
            withTimeoutOrNull(2_000) { while (syncListener.boundPort == null) delay(20) }
            assertNotNull("listener should bind", syncListener.boundPort)
            syncListener.stop()
            delay(200)
            // stop() clears boundPort and the Job; the accept loop must not be spinning.
            assertNull("stop() must tear the listener down", syncListener.boundPort)
        } finally {
            syncListener.stop()
            lifecycleScope.cancel()
        }
    }

    @Test
    fun unpaired_sender_frame_is_rejected_and_not_persisted() = runBlocking {
        // No peers paired at all: nothing can authenticate the inbound frame.
        val stranger = DeviceIdentity.generate()
        val p = persona("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        writeFrame(sealForDevice(stranger, p))
        delay(500)
        assertNull("an unpaired sender's persona must not be persisted", syncPersonaStore.get(p.id))
    }
}
