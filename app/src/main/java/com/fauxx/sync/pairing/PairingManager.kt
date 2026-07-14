package com.fauxx.sync.pairing

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.fauxx.sync.SealedChannel
import com.fauxx.sync.data.PairedPeer
import com.fauxx.sync.data.PairedPeerRepository
import com.fauxx.sync.wire.PairingPayload
import com.fauxx.sync.wire.SyncConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Two-sided QR pairing (E13 #178): builds this device's pairing payload, and completes pairing from
 * a scanned (or pasted) peer payload. Mirrors the desktop `complete_pairing`: refuses to pair this
 * device's own key, then persists the peer (stamping fingerprint + `pairedAt`) in the encrypted store.
 */
@Singleton
class PairingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sealedChannel: SealedChannel,
    private val pairedPeerRepository: PairedPeerRepository
) {

    /** This device's human-readable name (the mDNS instance name), best-effort. */
    fun deviceName(): String {
        val configured = runCatching {
            Settings.Global.getString(context.contentResolver, "device_name")
        }.getOrNull()
        return configured?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Fauxx-Device"
    }

    /** This device's public-key fingerprint, for display under the QR. */
    suspend fun myFingerprint(): String = withContext(Dispatchers.IO) { sealedChannel.fingerprint() }

    /** Build the pairing payload this device shows to a peer. */
    suspend fun myPairingPayload(port: Int = SyncConstants.DEFAULT_SYNC_PORT): PairingPayload =
        withContext(Dispatchers.IO) {
            val name = deviceName()
            PairingPayload.of(
                name = name,
                publicKey = sealedChannel.publicKey,
                host = "${sanitizeHost(name)}.local.",
                port = port
            )
        }

    /**
     * Complete pairing from a scanned/pasted base64url payload string. Decodes and validates the
     * payload (fail closed on bad base64url, non-JSON, `v` above supported, or a `pk` that is not
     * 32 bytes), refuses to pair this device's own key, and persists exactly one paired-peer row.
     */
    suspend fun completePairing(scannedPayload: String): PairedPeer = withContext(Dispatchers.IO) {
        val payload = PairingPayload.decode(scannedPayload)
        val peerPublicKey = payload.publicKeyBytes()
        require(!peerPublicKey.contentEquals(sealedChannel.publicKey)) {
            "refusing to pair a device with itself"
        }
        val peer = PairedPeer.create(
            name = payload.name,
            publicKey = peerPublicKey,
            host = payload.host,
            port = payload.port,
            pairedAt = System.currentTimeMillis()
        )
        pairedPeerRepository.upsert(peer)
        peer
    }

    private companion object {
        /**
         * Sanitize a device name into a host-label fragment (ASCII alphanumerics and hyphens only),
         * matching the desktop `sanitize_host`, so the advertised host hint is a plausible mDNS label.
         */
        fun sanitizeHost(name: String): String {
            val cleaned = buildString {
                for (c in name) append(if (c.isLetterOrDigit() && c.code < 128 || c == '-') c else '-')
            }
            return cleaned.ifEmpty { "fauxx" }
        }
    }
}
