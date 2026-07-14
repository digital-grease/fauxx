package com.fauxx.sync

import com.fauxx.data.model.SyntheticPersona
import com.fauxx.sync.crypto.DeviceIdentity
import com.fauxx.sync.crypto.DeviceKeyStore
import com.fauxx.sync.data.PairedPeer
import com.fauxx.sync.wire.Fingerprint
import com.fauxx.sync.wire.SealedFrame
import com.fauxx.sync.wire.SyncMessage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The authenticated sealed channel (E13 #178): seal a `PersonaUpsert` for a paired peer into frame
 * bytes, and open + attribute an inbound frame back into a [SyncMessage]. Byte-compatible with the
 * desktop sealed channel; no networking here, so it is fully unit/golden-testable.
 *
 * The device identity is loaded lazily from the Tink-wrapped keystore on first use (off the main
 * thread by callers). If the keystore is unavailable, access throws (fail closed; never an
 * in-memory identity).
 */
@Singleton
class SealedChannel @Inject constructor(
    private val deviceKeyStore: DeviceKeyStore
) {
    private val identity: DeviceIdentity by lazy { deviceKeyStore.loadOrCreate() }

    /** This device's public key (safe to share). */
    val publicKey: ByteArray get() = identity.publicKey

    /** This device's public-key fingerprint (for display under the QR / in discovery). */
    fun fingerprint(): String = Fingerprint.of(identity.publicKey)

    /**
     * Seal a `PersonaUpsert` for one paired peer into the on-wire sealed frame bytes.
     * The caller is responsible for passing a genuinely paired peer.
     */
    fun sealPersonaFor(peer: PairedPeer, persona: SyntheticPersona): ByteArray {
        val recipient = peer.publicKeyBytes()
        val plaintext = SyncMessage.personaUpsert(persona).toPlaintext()
        val envelope = identity.seal(recipient, plaintext)
        return SealedFrame(envelope).toBytes()
    }

    /**
     * Open a frame attributed to the given sender public key. Returns the [SyncMessage] on success,
     * or `null` on a bad frame, a MAC failure, or an unknown/unsupported message (all fail closed).
     */
    fun openFrame(senderPublicKey: ByteArray, frameBytes: ByteArray): SyncMessage? {
        val frame = SealedFrame.fromBytes(frameBytes) ?: return null
        val plaintext = identity.open(senderPublicKey, frame.envelope) ?: return null
        return try {
            SyncMessage.fromPlaintext(plaintext)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The contract section 6.1 trial-open attribution: the frame carries no clear-text sender, so
     * try each paired peer's key in turn. The crypto_box MAC verifies for exactly the real sender
     * and fails for every other key (and for an unpaired/forged frame, for all of them). Returns
     * the (sender, opened message) pair on the first key that authenticates, or `null` if none does.
     *
     * Rejects before any crypto when no peers are paired (mirror the desktop listener: nothing can
     * authenticate an inbound frame against an empty paired set).
     */
    fun attributeAndOpen(frameBytes: ByteArray, pairedPeers: List<PairedPeer>): Pair<PairedPeer, SyncMessage>? {
        if (pairedPeers.isEmpty()) return null
        for (peer in pairedPeers) {
            val senderPk = try {
                peer.publicKeyBytes()
            } catch (e: Exception) {
                continue // a corrupt stored key cannot attribute anything; skip it
            }
            val message = openFrame(senderPk, frameBytes) ?: continue
            return peer to message
        }
        return null
    }
}
