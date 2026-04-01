package com.fauxx.di

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.integration.android.AndroidKeystore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages a Tink [Aead] keyset backed by Android KeyStore for encrypting
 * small secrets (e.g. the SQLCipher database passphrase).
 *
 * The keyset is generated with AES256_GCM, encrypted by an Android KeyStore
 * key-encryption-key (KEK), and stored in SharedPreferences as hex.
 */
@Singleton
class TinkKeyManager @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val KEK_ALIAS = "fauxx_tink_master_key"
        private const val KEYSET_PREF_NAME = "fauxx_tink_keyset_prefs"
        private const val KEYSET_PREF_KEY = "fauxx_aead_keyset"
        private val KEYSET_ASSOCIATED_DATA = "fauxx_aead_keyset".toByteArray()
        private const val PASSPHRASE_FILE = "fauxx_db_passphrase.enc"
        private const val PASSPHRASE_LENGTH = 32
    }

    private val aead: Aead by lazy {
        AeadConfig.register()
        val handle = getOrCreateKeysetHandle()
        handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private fun getOrCreateKeysetHandle(): KeysetHandle {
        val prefs = context.getSharedPreferences(KEYSET_PREF_NAME, Context.MODE_PRIVATE)
        val keysetHex = prefs.getString(KEYSET_PREF_KEY, null)
        val kekExists = AndroidKeystore.hasKey(KEK_ALIAS)

        // Handle all 4 state combinations of KEK and keyset existence:
        // 1. Neither exists → first run, generate both
        // 2. KEK exists but keyset missing → corrupted state, regenerate keyset
        // 3. Keyset exists but KEK missing → KEK was wiped, must regenerate both
        // 4. Both exist → normal path, decrypt stored keyset
        if (!kekExists || keysetHex == null) {
            // Cases 1, 2, 3: regenerate. If KEK is missing, create it. If keyset is
            // missing or stale (KEK was rotated), generate a fresh keyset.
            if (!kekExists) {
                AndroidKeystore.generateNewAes256GcmKey(KEK_ALIAS)
            }
            val handle = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
            val encrypted = TinkProtoKeysetFormat.serializeEncryptedKeyset(
                handle,
                AndroidKeystore.getAead(KEK_ALIAS),
                KEYSET_ASSOCIATED_DATA
            )
            prefs.edit()
                .putString(KEYSET_PREF_KEY, android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP))
                .commit()
            return handle
        }

        // Case 4: both exist — decrypt the stored keyset
        return try {
            val encrypted = android.util.Base64.decode(keysetHex, android.util.Base64.NO_WRAP)
            TinkProtoKeysetFormat.parseEncryptedKeyset(
                encrypted,
                AndroidKeystore.getAead(KEK_ALIAS),
                KEYSET_ASSOCIATED_DATA
            )
        } catch (e: java.security.GeneralSecurityException) {
            // Keyset is corrupted or KEK was rotated — regenerate
            val handle = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM)
            val freshEncrypted = TinkProtoKeysetFormat.serializeEncryptedKeyset(
                handle,
                AndroidKeystore.getAead(KEK_ALIAS),
                KEYSET_ASSOCIATED_DATA
            )
            prefs.edit()
                .putString(KEYSET_PREF_KEY, android.util.Base64.encodeToString(freshEncrypted, android.util.Base64.NO_WRAP))
                .commit()
            handle
        }
    }

    /**
     * Encrypts [plaintext] using the AndroidKeyStore-backed Aead keyset.
     * [associatedData] is bound to the ciphertext (authenticated but not encrypted).
     */
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray =
        aead.encrypt(plaintext, associatedData)

    /**
     * Decrypts [ciphertext] previously produced by [encrypt] with the same [associatedData].
     */
    fun decrypt(ciphertext: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray =
        aead.decrypt(ciphertext, associatedData)

    /**
     * Returns the SQLCipher database passphrase, creating and persisting one if it
     * doesn't yet exist. The passphrase is stored encrypted in [PASSPHRASE_FILE].
     */
    fun getOrCreateDatabasePassphrase(): String {
        val file = java.io.File(context.filesDir, PASSPHRASE_FILE)
        return if (file.exists()) {
            val ciphertext = file.readBytes()
            String(decrypt(ciphertext, PASSPHRASE_FILE.toByteArray()), Charsets.UTF_8)
        } else {
            val passphrase = generatePassphrase()
            val ciphertext = encrypt(passphrase.toByteArray(Charsets.UTF_8), PASSPHRASE_FILE.toByteArray())
            file.writeBytes(ciphertext)
            passphrase
        }
    }

    /**
     * Stores an externally-provided passphrase (used during migration from
     * EncryptedSharedPreferences). Overwrites any existing passphrase file.
     */
    fun storeDatabasePassphrase(passphrase: String) {
        val file = java.io.File(context.filesDir, PASSPHRASE_FILE)
        val ciphertext = encrypt(passphrase.toByteArray(Charsets.UTF_8), PASSPHRASE_FILE.toByteArray())
        file.writeBytes(ciphertext)
    }

    /**
     * Returns `true` if the Tink-encrypted passphrase file already exists.
     */
    fun hasStoredPassphrase(): Boolean =
        java.io.File(context.filesDir, PASSPHRASE_FILE).exists()

    private fun generatePassphrase(): String {
        val bytes = ByteArray(PASSPHRASE_LENGTH)
        java.security.SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }
}
