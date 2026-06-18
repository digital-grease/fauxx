package com.fauxx.sync.wire

import com.fauxx.sync.crypto.SodiumProvider
import com.goterl.lazysodium.interfaces.GenericHash

/**
 * A short, human-comparable fingerprint of a public key, shown in the discovery list and printed
 * under the QR so a user can eyeball that the scanned device matches the discovered one. Display
 * and integrity convenience only, NOT a security boundary; the sealed channel enforces auth.
 *
 * Definition (matches the desktop `wire::fingerprint`): BLAKE2b-256 (unkeyed, default 32-byte
 * output, i.e. libsodium `crypto_generichash` defaults) of the 32 raw public-key bytes; take the
 * first 8 bytes; lowercase hex; group into four colon-separated 2-byte pairs (19 chars, 3 colons).
 */
object Fingerprint {

    fun of(publicKey: ByteArray): String {
        val out = ByteArray(GenericHash.BYTES) // 32-byte BLAKE2b digest
        val ok = SodiumProvider.genericHash.cryptoGenericHash(
            out, out.size, publicKey, publicKey.size.toLong(), null, 0
        )
        check(ok) { "fingerprint hash failed" }
        val sb = StringBuilder(19)
        for (i in 0 until 8) {
            if (i > 0 && i % 2 == 0) sb.append(':')
            sb.append("%02x".format(out[i].toInt() and 0xFF))
        }
        return sb.toString()
    }
}
