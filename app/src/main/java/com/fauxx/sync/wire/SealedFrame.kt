package com.fauxx.sync.wire

import com.fauxx.sync.crypto.SealedEnvelope

/**
 * The sealed wire frame: a tiny versioned header, the nonce, and the ciphertext. This is exactly
 * what the transport carries. The persona JSON is wholly inside `ciphertext`, never in the clear.
 *
 * Binary layout (matches the desktop `SealedFrame`):
 * `magic(4)="FXS1" || version(u16 LITTLE-endian) || nonce(24) || ciphertext(MAC(16) || encrypted)`
 *
 * Note the endianness: the in-frame version is LITTLE-endian here, independent of the BIG-endian
 * u32 TCP length prefix that the transport adds around this frame.
 */
class SealedFrame(val envelope: SealedEnvelope) {

    /** Serialize the frame to the bytes the transport ships. */
    fun toBytes(): ByteArray {
        val magic = MAGIC
        val out = ByteArray(magic.size + 2 + envelope.nonce.size + envelope.ciphertext.size)
        var off = 0
        System.arraycopy(magic, 0, out, off, magic.size); off += magic.size
        // version, u16 LITTLE-endian
        out[off++] = (SyncConstants.PROTOCOL_VERSION and 0xFF).toByte()
        out[off++] = ((SyncConstants.PROTOCOL_VERSION ushr 8) and 0xFF).toByte()
        System.arraycopy(envelope.nonce, 0, out, off, envelope.nonce.size); off += envelope.nonce.size
        System.arraycopy(envelope.ciphertext, 0, out, off, envelope.ciphertext.size)
        return out
    }

    companion object {
        private val MAGIC: ByteArray = SyncConstants.FRAME_MAGIC.toByteArray(Charsets.US_ASCII)

        /**
         * Parse a frame from transport bytes, failing closed (returns `null`) on a buffer shorter
         * than [SyncConstants.MIN_FRAME_LEN], a wrong magic, or an unsupported version.
         */
        fun fromBytes(bytes: ByteArray): SealedFrame? {
            if (bytes.size < SyncConstants.MIN_FRAME_LEN) return null
            for (i in MAGIC.indices) {
                if (bytes[i] != MAGIC[i]) return null
            }
            // version, u16 LITTLE-endian: low byte first.
            val version = (bytes[4].toInt() and 0xFF) or ((bytes[5].toInt() and 0xFF) shl 8)
            if (version > SyncConstants.PROTOCOL_VERSION) return null
            val nonce = bytes.copyOfRange(6, 6 + SyncConstants.NONCE_LEN)
            val ciphertext = bytes.copyOfRange(SyncConstants.FRAME_HEADER_LEN, bytes.size)
            return SealedFrame(SealedEnvelope(nonce, ciphertext))
        }
    }
}
