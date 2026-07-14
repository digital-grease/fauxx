package com.fauxx.sync.transport

import com.fauxx.sync.wire.SyncConstants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Pure-JVM TCP-framing test (mirror the desktop `frame_round_trips_over_loopback` /
 * `oversize_length_prefix_is_rejected_before_allocation`). The length prefix is u32 BIG-endian.
 */
class FrameCodecTest {

    @Test
    fun `round trips a frame through the length prefix`() {
        val frame = ByteArray(200) { it.toByte() }
        val out = ByteArrayOutputStream()
        FrameCodec.writeFrame(out, frame)
        val read = FrameCodec.readFrame(ByteArrayInputStream(out.toByteArray()))
        assertArrayEquals(frame, read)
    }

    @Test
    fun `a one-byte frame is prefixed big-endian as 00 00 00 01`() {
        val out = ByteArrayOutputStream()
        FrameCodec.writeFrame(out, byteArrayOf(0x7F))
        val bytes = out.toByteArray()
        assertEquals(5, bytes.size)
        assertEquals(0x00.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
        assertEquals(0x00.toByte(), bytes[2])
        assertEquals(0x01.toByte(), bytes[3])
        assertEquals(0x7F.toByte(), bytes[4])
    }

    @Test
    fun `rejects a zero length prefix`() {
        val zeroLen = byteArrayOf(0, 0, 0, 0)
        assertThrows(IOException::class.java) { FrameCodec.readFrame(ByteArrayInputStream(zeroLen)) }
    }

    @Test
    fun `rejects an oversize length prefix before allocating`() {
        // (1 << 20) + 1, big-endian. The reader must reject it without reading a body.
        val bogus = SyncConstants.MAX_FRAME_LEN + 1
        val prefix = byteArrayOf(
            ((bogus ushr 24) and 0xFF).toByte(),
            ((bogus ushr 16) and 0xFF).toByte(),
            ((bogus ushr 8) and 0xFF).toByte(),
            (bogus and 0xFF).toByte()
        )
        assertThrows(IOException::class.java) { FrameCodec.readFrame(ByteArrayInputStream(prefix)) }
    }

    @Test
    fun `fails closed on premature EOF in the body`() {
        // Announce 100 bytes but provide only 10.
        val prefix = byteArrayOf(0, 0, 0, 100)
        val truncated = prefix + ByteArray(10)
        assertThrows(IOException::class.java) { FrameCodec.readFrame(ByteArrayInputStream(truncated)) }
    }

    @Test
    fun `refuses to write an oversize frame`() {
        val out = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            FrameCodec.writeFrame(out, ByteArray(SyncConstants.MAX_FRAME_LEN + 1))
        }
    }
}
