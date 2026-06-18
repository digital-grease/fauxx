package com.fauxx.sync.wire

import com.fauxx.sync.crypto.SealedEnvelope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM frame layout test (mirror the desktop `sealed_frame_round_trips` /
 * `sealed_frame_rejects_bad_magic`). Exercises the exact byte layout with a synthetic envelope, so
 * no native crypto is needed.
 */
class SealedFrameTest {

    private fun envelope(): SealedEnvelope =
        SealedEnvelope(ByteArray(24) { it.toByte() }, ByteArray(16 + 5) { (100 + it).toByte() })

    @Test
    fun `to bytes has FXS1 magic and little-endian version`() {
        val bytes = SealedFrame(envelope()).toBytes()
        assertEquals('F'.code.toByte(), bytes[0])
        assertEquals('X'.code.toByte(), bytes[1])
        assertEquals('S'.code.toByte(), bytes[2])
        assertEquals('1'.code.toByte(), bytes[3])
        // version u16 LITTLE-endian == 1 -> 0x01 0x00
        assertEquals(0x01.toByte(), bytes[4])
        assertEquals(0x00.toByte(), bytes[5])
    }

    @Test
    fun `round trips the layout`() {
        val env = envelope()
        val bytes = SealedFrame(env).toBytes()
        val back = SealedFrame.fromBytes(bytes)
        assertNotNull(back)
        assertArrayEquals(env.nonce, back!!.envelope.nonce)
        assertArrayEquals(env.ciphertext, back.envelope.ciphertext)
    }

    @Test
    fun `rejects a frame shorter than 46 bytes`() {
        assertNull(SealedFrame.fromBytes(ByteArray(45)))
        assertNull(SealedFrame.fromBytes("short".toByteArray()))
    }

    @Test
    fun `rejects wrong magic`() {
        val bytes = SealedFrame(envelope()).toBytes()
        bytes[0] = 'Z'.code.toByte()
        assertNull(SealedFrame.fromBytes(bytes))
    }

    @Test
    fun `rejects an unsupported future version`() {
        val bytes = SealedFrame(envelope()).toBytes()
        // Bump the little-endian version to 2.
        bytes[4] = 0x02
        bytes[5] = 0x00
        assertNull(SealedFrame.fromBytes(bytes))
    }

    @Test
    fun `accepts exactly the minimum 46-byte frame`() {
        val env = SealedEnvelope(ByteArray(24), ByteArray(16))
        val bytes = SealedFrame(env).toBytes()
        assertEquals(46, bytes.size)
        assertNotNull(SealedFrame.fromBytes(bytes))
    }
}
