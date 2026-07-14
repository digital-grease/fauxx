package com.fauxx.sync.data

import com.fauxx.sync.wire.Fingerprint
import com.fauxx.sync.wire.PublicKeyCodec

/**
 * Domain model for a paired peer (decoupled from the Room entity). The base64url [publicKey] is the
 * trust anchor. Mirrors the desktop `PairedPeer`.
 */
data class PairedPeer(
    val name: String,
    val publicKey: String,
    val fingerprint: String,
    val host: String?,
    val port: Int,
    val pairedAt: Long
) {
    /** Decode this peer's public key into fixed-size bytes. */
    fun publicKeyBytes(): ByteArray = PublicKeyCodec.decode(publicKey)

    companion object {
        /**
         * Build a paired-peer record from raw public-key bytes and metadata, stamping the
         * base64url public key and the fingerprint (mirror the desktop `PairedPeer::new`).
         */
        fun create(
            name: String,
            publicKey: ByteArray,
            host: String?,
            port: Int,
            pairedAt: Long
        ): PairedPeer = PairedPeer(
            name = name,
            publicKey = PublicKeyCodec.encode(publicKey),
            fingerprint = Fingerprint.of(publicKey),
            host = host,
            port = port,
            pairedAt = pairedAt
        )
    }
}

internal fun PairedPeer.toEntity(): PairedPeerEntity =
    PairedPeerEntity(publicKey = publicKey, name = name, fingerprint = fingerprint, host = host, port = port, pairedAt = pairedAt)

internal fun PairedPeerEntity.toDomain(): PairedPeer =
    PairedPeer(name = name, publicKey = publicKey, fingerprint = fingerprint, host = host, port = port, pairedAt = pairedAt)
