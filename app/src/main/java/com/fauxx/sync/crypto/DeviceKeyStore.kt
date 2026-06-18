package com.fauxx.sync.crypto

import android.content.Context
import com.fauxx.di.TinkKeyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads (creating on first run) this device's long-lived X25519 identity, persisting the secret
 * ONLY Tink-AEAD-wrapped, never in plaintext or any log. Mirrors the desktop's "OS keystore wrap"
 * with the Android equivalent: a Tink AEAD keyset backed by AndroidKeyStore (via [TinkKeyManager]).
 *
 * The serialized blob is `public(32) || secret(32)` exactly like the desktop, so the same 64-byte
 * layout round-trips. If the keyset or storage is unavailable, this throws rather than degrading to
 * an in-memory or unencrypted identity (fail closed, contract section 9 / AC-6).
 */
@Singleton
class DeviceKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tinkKeyManager: TinkKeyManager
) {

    /**
     * Return this device's identity, generating and persisting one on first run and reloading the
     * same keypair (stable public key / fingerprint) on every subsequent call.
     */
    fun loadOrCreate(): DeviceIdentity {
        val file = File(context.filesDir, KEYPAIR_FILE)
        if (file.exists()) {
            val blob = tinkKeyManager.decrypt(file.readBytes(), ASSOCIATED_DATA)
            try {
                require(blob.size == KEYPAIR_LEN) {
                    "device keypair blob is ${blob.size} bytes, expected $KEYPAIR_LEN"
                }
                return DeviceIdentity.fromBytes(
                    blob.copyOfRange(0, PUBLIC_KEY_LEN),
                    blob.copyOfRange(PUBLIC_KEY_LEN, KEYPAIR_LEN)
                )
            } finally {
                blob.fill(0)
            }
        }

        val identity = DeviceIdentity.generate()
        val blob = ByteArray(KEYPAIR_LEN)
        val secret = identity.secretKeyCopyForKeystore()
        try {
            System.arraycopy(identity.publicKey, 0, blob, 0, PUBLIC_KEY_LEN)
            System.arraycopy(secret, 0, blob, PUBLIC_KEY_LEN, SECRET_KEY_LEN)
            val ciphertext = tinkKeyManager.encrypt(blob, ASSOCIATED_DATA)
            File(context.filesDir, KEYPAIR_FILE).writeBytes(ciphertext)
        } finally {
            secret.fill(0)
            blob.fill(0)
        }
        return identity
    }

    private companion object {
        const val KEYPAIR_FILE = "fauxx_device_keypair.enc"
        val ASSOCIATED_DATA = "fauxx_device_keypair".toByteArray(Charsets.UTF_8)
        const val PUBLIC_KEY_LEN = 32
        const val SECRET_KEY_LEN = 32
        const val KEYPAIR_LEN = PUBLIC_KEY_LEN + SECRET_KEY_LEN
    }
}
