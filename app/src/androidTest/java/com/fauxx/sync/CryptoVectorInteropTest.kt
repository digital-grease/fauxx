package com.fauxx.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fauxx.sync.crypto.DeviceIdentity
import com.fauxx.sync.crypto.SodiumProvider
import com.fauxx.sync.wire.SealedFrame
import com.fauxx.sync.wire.SyncBody
import com.fauxx.sync.wire.SyncMessage
import com.goterl.lazysodium.interfaces.Box
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The byte-for-byte cross-implementation crypto_box interop proof (E13 #178). The vector below was
 * MINTED BY THE DESKTOP (dryoc) with fixed keypairs and a fixed nonce, then validated for
 * self-consistency on both sides (`fauxx-desktop/e13_interop_vector.json`). It proves the Android
 * lazysodium X25519 + XSalsa20-Poly1305 path is identical to the desktop's, the one thing the
 * Android-internal round-trip and the algorithm's standardness cannot establish alone.
 *
 * Fixed inputs: sk_sender = 0x0b*32, sk_recipient = 0x16*32 (public keys derived via
 * crypto_scalarmult_base), nonce = 0x55*24, body = the canonical "Round Trip" persona.
 */
@RunWith(AndroidJUnit4::class)
class CryptoVectorInteropTest {

    private val skSender = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
    private val pkSender = hex("73b2d8b76aa9b53660032bc8f5d8bee3a3ae4e3b3a7fd49ade81f7347a34aa68")
    private val skRecipient = hex("1616161616161616161616161616161616161616161616161616161616161616")
    private val pkRecipient = hex("7f442fb4ecc9dd6cde4635881fbe2bb433b67b004935c4330d21e36f681a0e12")
    private val nonce = hex("555555555555555555555555555555555555555555555555")

    private val ciphertextHex =
        "4c4dd0992df13b4f3580bf28dd7d4c3ae8773e4d04b3aed5d7e6b1d95098e21abb0587cf05dcc1a3ea88d2de111b5f8eb1c70a4e9f98a5d0b179049ef86ec00d83d12d4ede21362dcb55b8f735aed9f4eb4fad206547993bee63e9fe482c8f2f7a90c3ad443b33feba8505126a6e4ec9c7c617030bcb92b21c9927cd3eea8d5881939b5662e8d812af28886d26d521e7ede59cdc1c4e5cad3bb7eb7a700fd1350926830e24f3922a251b27a12fbf7a70988515f6e0a40a106fa7f392cc372df9c52ad908bcdf7e4be57e04cd8896162b8aa061499ea401ad352eba58d146da3e33d75740cecd8baa3b7c0525c444f7628f5f6141d343931c2c098076b92aeb0a0f3d0e89260fdb68d0247e8ed7c49d74872d06703ae59ae4a626e23c65a13f955d0141fcd5391a74d177e402f51398f7"

    private val fxs1FrameHex =
        "4658533101005555555555555555555555555555555555555555555555554c4dd0992df13b4f3580bf28dd7d4c3ae8773e4d04b3aed5d7e6b1d95098e21abb0587cf05dcc1a3ea88d2de111b5f8eb1c70a4e9f98a5d0b179049ef86ec00d83d12d4ede21362dcb55b8f735aed9f4eb4fad206547993bee63e9fe482c8f2f7a90c3ad443b33feba8505126a6e4ec9c7c617030bcb92b21c9927cd3eea8d5881939b5662e8d812af28886d26d521e7ede59cdc1c4e5cad3bb7eb7a700fd1350926830e24f3922a251b27a12fbf7a70988515f6e0a40a106fa7f392cc372df9c52ad908bcdf7e4be57e04cd8896162b8aa061499ea401ad352eba58d146da3e33d75740cecd8baa3b7c0525c444f7628f5f6141d343931c2c098076b92aeb0a0f3d0e89260fdb68d0247e8ed7c49d74872d06703ae59ae4a626e23c65a13f955d0141fcd5391a74d177e402f51398f7"

    private val expectedJson =
        """{"protocolVersion":1,"kind":"PersonaUpsert","body":{"id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","name":"Round Trip","ageRange":"AGE_35_44","profession":"TEACHER","region":"CANADA","interests":["ACADEMIC","HISTORY"],"createdAt":1700000000000,"activeUntil":1800000000000,"schemaVersion":1}}"""

    @Test
    fun desktop_sealed_frame_opens_to_the_exact_persona() {
        // Open the desktop-minted frame with the recipient identity, attributing to the sender.
        val recipient = DeviceIdentity.fromBytes(pkRecipient, skRecipient)
        val frame = SealedFrame.fromBytes(hex(fxs1FrameHex))
        assertNotNull("desktop FXS1 frame must parse", frame)

        val plaintext = recipient.open(pkSender, frame!!.envelope)
        assertNotNull("desktop-sealed frame must open with the lazysodium path", plaintext)
        assertEquals("recovered plaintext must equal the desktop JSON byte-for-byte",
            expectedJson, String(plaintext!!, Charsets.UTF_8))

        // And it parses to the canonical persona.
        val msg = SyncMessage.fromPlaintext(plaintext)
        val persona = (msg.body as SyncBody.PersonaUpsert).persona
        assertEquals("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", persona.id)
        assertEquals("AGE_35_44", persona.ageRange)
        assertEquals("TEACHER", persona.profession)
        assertEquals("CANADA", persona.region)
        assertEquals(1_700_000_000_000, persona.createdAt)
        assertEquals(1_800_000_000_000, persona.activeUntil)
    }

    @Test
    fun android_seal_reproduces_the_desktop_ciphertext_byte_for_byte() {
        // Seal the exact plaintext with the sender secret + recipient public + the SAME fixed nonce,
        // and assert the ciphertext (and the assembled FXS1 frame) match the desktop bit-for-bit.
        val plaintext = expectedJson.toByteArray(Charsets.UTF_8)
        val ciphertext = ByteArray(plaintext.size + Box.MACBYTES)
        val ok = SodiumProvider.box.cryptoBoxEasy(
            ciphertext, plaintext, plaintext.size.toLong(), nonce, pkRecipient, skSender
        )
        assertTrue("cryptoBoxEasy must succeed", ok)
        assertEquals("ciphertext must match the desktop dryoc output", ciphertextHex, ciphertext.toHex())

        // The full on-wire FXS1 frame must also be identical.
        val frame = SealedFrame(com.fauxx.sync.crypto.SealedEnvelope(nonce, ciphertext)).toBytes()
        assertArrayEquals("assembled FXS1 frame must match the desktop frame", hex(fxs1FrameHex), frame)
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((Character.digit(s[it * 2], 16) shl 4) + Character.digit(s[it * 2 + 1], 16)).toByte() }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
