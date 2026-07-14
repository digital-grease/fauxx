package com.fauxx.sync.transport

import com.fauxx.sync.wire.SyncConstants
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The TCP stream framing around a sealed frame (contract section 6.1): a u32 BIG-endian length
 * prefix followed by the opaque frame bytes. Mirrors the desktop `tcp.rs::write_frame` /
 * `read_frame`.
 *
 * Note the endianness split: this length prefix is BIG-endian, while the in-frame protocol version
 * (see [com.fauxx.sync.wire.SealedFrame]) is LITTLE-endian; they are independent.
 */
object FrameCodec {

    /** Write one length-prefixed frame and flush. Rejects an over-cap frame before writing. */
    fun writeFrame(out: OutputStream, frame: ByteArray) {
        require(frame.isNotEmpty()) { "refusing to write an empty frame" }
        require(frame.size <= SyncConstants.MAX_FRAME_LEN) {
            "sealed frame ${frame.size} exceeds MAX_FRAME_LEN ${SyncConstants.MAX_FRAME_LEN}"
        }
        val len = frame.size
        // u32 BIG-endian length prefix.
        out.write((len ushr 24) and 0xFF)
        out.write((len ushr 16) and 0xFF)
        out.write((len ushr 8) and 0xFF)
        out.write(len and 0xFF)
        out.write(frame)
        out.flush()
    }

    /**
     * Read one length-prefixed frame, rejecting an out-of-bounds length BEFORE allocating the
     * buffer. Mirrors the desktop reader: reject `0` or `> MAX_FRAME_LEN`, then read exactly that
     * many bytes (looping until full, failing closed on premature EOF).
     */
    fun readFrame(input: InputStream): ByteArray {
        val lenBuf = readExactly(input, 4)
        val len = ((lenBuf[0].toInt() and 0xFF) shl 24) or
            ((lenBuf[1].toInt() and 0xFF) shl 16) or
            ((lenBuf[2].toInt() and 0xFF) shl 8) or
            (lenBuf[3].toInt() and 0xFF)
        // `len` is read as a signed Int: a u32 above 2^31 wraps negative, caught by `len <= 0`.
        if (len <= 0 || len > SyncConstants.MAX_FRAME_LEN) {
            throw IOException("inbound frame length $len out of bounds (1..=${SyncConstants.MAX_FRAME_LEN})")
        }
        return readExactly(input, len)
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val read = input.read(buf, off, n - off)
            if (read < 0) throw IOException("premature EOF: read $off of $n frame bytes")
            off += read
        }
        return buf
    }
}
