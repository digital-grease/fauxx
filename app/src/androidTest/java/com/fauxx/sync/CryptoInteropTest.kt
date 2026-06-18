package com.fauxx.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fauxx.sync.crypto.DeviceIdentity
import com.fauxx.sync.wire.Fingerprint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented crypto + fingerprint interop (E13 #178). Needs the native lazysodium library, so it
 * runs on a device/emulator. Mirrors the desktop `crypto.rs` and `wire.rs` fingerprint tests.
 *
 * The fingerprint golden vectors are the byte-for-byte proof that the lazysodium BLAKE2b path equals
 * the desktop `dryoc` / libsodium `crypto_generichash` defaults: they were computed independently
 * (Python `hashlib.blake2b(digest_size=32)` over the fixed keys).
 */
@RunWith(AndroidJUnit4::class)
class CryptoInteropTest {

    @Test
    fun seals_and_opens_a_round_trip() {
        val alice = DeviceIdentity.generate()
        val bob = DeviceIdentity.generate()
        val message = "persona payload bytes".toByteArray()
        val sealed = alice.seal(bob.publicKey, message)
        val opened = bob.open(alice.publicKey, sealed)
        assertArrayEquals(message, opened)
    }

    @Test
    fun tampered_ciphertext_is_rejected() {
        val alice = DeviceIdentity.generate()
        val bob = DeviceIdentity.generate()
        val sealed = alice.seal(bob.publicKey, "hello".toByteArray())
        sealed.ciphertext[0] = (sealed.ciphertext[0].toInt() xor 0x01).toByte()
        assertNull(bob.open(alice.publicKey, sealed))
    }

    @Test
    fun tampered_nonce_is_rejected() {
        val alice = DeviceIdentity.generate()
        val bob = DeviceIdentity.generate()
        val sealed = alice.seal(bob.publicKey, "hello".toByteArray())
        sealed.nonce[0] = (sealed.nonce[0].toInt() xor 0x01).toByte()
        assertNull(bob.open(alice.publicKey, sealed))
    }

    @Test
    fun a_wrong_recipient_cannot_open() {
        val alice = DeviceIdentity.generate()
        val bob = DeviceIdentity.generate()
        val eve = DeviceIdentity.generate()
        val sealed = alice.seal(bob.publicKey, "secret".toByteArray())
        // Eve was never the recipient; she lacks Bob's secret key.
        assertNull(eve.open(alice.publicKey, sealed))
    }

    @Test
    fun a_stranger_forgery_fails_when_attributed_to_a_paired_sender() {
        val bob = DeviceIdentity.generate()
        val eve = DeviceIdentity.generate()
        val alice = DeviceIdentity.generate()
        // Eve seals a forgery for Bob, but Bob attributes inbound to the PAIRED Alice; the MAC was
        // computed under Eve<->Bob, so opening against Alice's key fails.
        val forged = eve.seal(bob.publicKey, "forged".toByteArray())
        assertNull(bob.open(alice.publicKey, forged))
    }

    @Test
    fun from_bytes_rejects_wrong_length() {
        assertThrows(IllegalArgumentException::class.java) {
            DeviceIdentity.fromBytes(ByteArray(10), ByteArray(32))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceIdentity.fromBytes(ByteArray(32), ByteArray(10))
        }
    }

    @Test
    fun round_trip_through_bytes_preserves_keys() {
        val alice = DeviceIdentity.generate()
        val bob = DeviceIdentity.generate()
        val secret = alice.secretKeyCopyForKeystore()
        val restored = DeviceIdentity.fromBytes(alice.publicKey, secret)
        assertArrayEquals(alice.publicKey, restored.publicKey)
        // The restored identity decrypts what the original could.
        val sealed = bob.seal(alice.publicKey, "for the persisted identity".toByteArray())
        assertArrayEquals("for the persisted identity".toByteArray(), restored.open(bob.publicKey, sealed))
    }

    @Test
    fun toString_redacts_the_secret() {
        val rendered = DeviceIdentity.generate().toString()
        assertTrue(rendered.contains("redacted"))
    }

    @Test
    fun fingerprint_matches_frozen_blake2b_vectors() {
        // Independently computed (Python hashlib.blake2b, digest_size=32) over the fixed keys.
        assertEquals("25d8:0d45:a11b:372d", Fingerprint.of(ByteArray(32) { 9 }))
        assertEquals("17cd:c7bc:a3f2:a0bd", Fingerprint.of(ByteArray(32) { 7 }))
        assertEquals("2508:177e:3f55:3ec0", Fingerprint.of(ByteArray(32) { 11 }))
    }

    @Test
    fun fingerprint_is_grouped_and_key_dependent() {
        val fp = Fingerprint.of(ByteArray(32) { 9 })
        assertEquals(19, fp.length)
        assertEquals(3, fp.count { it == ':' })
        assertEquals(fp, Fingerprint.of(ByteArray(32) { 9 }))
        assertNotEquals(fp, Fingerprint.of(ByteArray(32) { 8 }))
    }
}
