package com.fauxx.sync.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.GenericHash
import java.security.SecureRandom

/**
 * Process-wide owner of a single [LazySodiumAndroid] instance and the OS CSPRNG.
 *
 * lazysodium wraps libsodium, so [Box] (X25519 + XSalsa20-Poly1305) and [GenericHash]
 * (BLAKE2b) are byte-for-byte compatible with the desktop `dryoc` build. The JNA native
 * load is not free, so exactly one instance exists per process.
 */
object SodiumProvider {

    /** The shared lazysodium instance. Loads the native library on first access. */
    val lazySodium: LazySodiumAndroid by lazy { LazySodiumAndroid(SodiumAndroid()) }

    /** The authenticated public-key box interface (byte-array, fail-by-boolean form). */
    val box: Box.Native get() = lazySodium

    /** The generic (BLAKE2b) hash interface, used for the public-key fingerprint. */
    val genericHash: GenericHash.Native get() = lazySodium

    /** The OS CSPRNG used to draw fresh per-message nonces. Never `java.util.Random`. */
    val secureRandom: SecureRandom by lazy { SecureRandom() }
}
