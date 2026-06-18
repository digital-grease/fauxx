package com.fauxx.sync.crypto

/**
 * A sealed persona payload: the per-message random nonce plus the ciphertext. `crypto_box_easy`
 * PREPENDS the 16-byte Poly1305 MAC, so the ciphertext is `MAC(16) || encrypted_bytes` (contract
 * section 5). Mirrors the desktop `SealedEnvelope`.
 *
 * This is the unit framed on the wire. It carries zero plaintext persona fields; only the
 * nonce (not secret) and the opaque ciphertext travel.
 */
class SealedEnvelope(
    /** The fresh per-message nonce (24 bytes). Public, never reused. */
    val nonce: ByteArray,
    /** The crypto_box ciphertext: the leading 16-byte MAC followed by the encrypted bytes. */
    val ciphertext: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SealedEnvelope) return false
        return nonce.contentEquals(other.nonce) && ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int = 31 * nonce.contentHashCode() + ciphertext.contentHashCode()
}
