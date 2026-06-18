package com.fauxx.sync.wire

import java.util.Base64

/**
 * The canonical text form of a public key: base64url without padding (RFC 4648 section 5,
 * `-`/`_`, no `=`). Used in the QR `pk`, the mDNS TXT `pk`, and persistence. Matches the desktop
 * `encode_public_key` / `decode_public_key`.
 *
 * Uses [java.util.Base64] (available since API 26 = this app's minSdk, and on the host JVM so the
 * codec unit-tests run without Robolectric). Its URL encoder/decoder uses the exact RFC 4648
 * section 5 alphabet, identical to the Rust `URL_SAFE_NO_PAD` engine.
 */
object PublicKeyCodec {

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    /** Encode public-key bytes as base64url (no padding). */
    fun encode(publicKey: ByteArray): String {
        require(publicKey.size == SyncConstants.PUBLIC_KEY_LEN) {
            "public key is ${publicKey.size} bytes, expected ${SyncConstants.PUBLIC_KEY_LEN}"
        }
        return encoder.encodeToString(publicKey)
    }

    /** Decode a base64url public key into fixed-size bytes, failing closed on bad encoding or length. */
    fun decode(encoded: String): ByteArray {
        val bytes = try {
            decoder.decode(encoded.trim())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("public key not valid base64url", e)
        }
        require(bytes.size == SyncConstants.PUBLIC_KEY_LEN) {
            "public key is ${bytes.size} bytes, expected ${SyncConstants.PUBLIC_KEY_LEN}"
        }
        return bytes
    }
}
