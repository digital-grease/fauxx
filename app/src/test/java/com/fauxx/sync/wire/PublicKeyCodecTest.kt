package com.fauxx.sync.wire

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Pure-JVM codec test (mirror the desktop `public_key_codec_round_trip`). */
class PublicKeyCodecTest {

    @Test
    fun `round trips public key bytes through base64url`() {
        val pk = ByteArray(32) { 42 }
        val text = PublicKeyCodec.encode(pk)
        assertArrayEquals(pk, PublicKeyCodec.decode(text))
    }

    @Test
    fun `encodes the frozen pk7 vector exactly`() {
        // The QR golden vector's pk is 32 bytes of 0x07; base64url no-pad is 43 chars.
        val pk7 = ByteArray(32) { 7 }
        assertEquals("BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc", PublicKeyCodec.encode(pk7))
        assertEquals(43, PublicKeyCodec.encode(pk7).length)
    }

    @Test
    fun `rejects a key that does not decode to 32 bytes`() {
        assertThrows(IllegalArgumentException::class.java) { PublicKeyCodec.decode("short") }
    }

    @Test
    fun `rejects non base64url input`() {
        assertThrows(IllegalArgumentException::class.java) { PublicKeyCodec.decode("!!!not base64!!!") }
    }

    @Test
    fun `rejects encoding a wrong-length key`() {
        assertThrows(IllegalArgumentException::class.java) { PublicKeyCodec.encode(ByteArray(10)) }
    }
}
