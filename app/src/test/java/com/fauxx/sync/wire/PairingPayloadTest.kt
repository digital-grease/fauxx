package com.fauxx.sync.wire

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Pure-JVM pairing-payload codec test (mirror the desktop `pairing_payload_*`). The load-bearing
 * interop check is decode-side acceptance of the FROZEN golden QR vector plus field equality, since
 * Gson may order keys differently than serde on encode.
 */
class PairingPayloadTest {

    /** The frozen golden vector from the contract section 4.1 (pk = 32 x 0x07). */
    private val frozenQr =
        "eyJ2IjoxLCJuYW1lIjoiRGVza3RvcC1TdHVkeSIsInBrIjoiQndjSEJ3Y0hCd2NIQndjSEJ3Y0hCd2NIQndjSEJ3Y0hCd2NIQndjSEJ3YyIsImhvc3QiOiJkZXNrdG9wLmxvY2FsLiIsInBvcnQiOjQ1OTk5fQ"

    @Test
    fun `decodes the frozen golden QR vector with exact fields`() {
        val payload = PairingPayload.decode(frozenQr)
        assertEquals(1, payload.v)
        assertEquals("Desktop-Study", payload.name)
        assertEquals("desktop.local.", payload.host)
        assertEquals(45999, payload.port)
        assertArrayEquals(ByteArray(32) { 7 }, payload.publicKeyBytes())
    }

    @Test
    fun `structurally round trips`() {
        val payload = PairingPayload.of("Phone", ByteArray(32) { 11 }, "phone.local.", 45999)
        val back = PairingPayload.decode(payload.encode())
        assertEquals(payload, back)
        assertArrayEquals(payload.publicKeyBytes(), back.publicKeyBytes())
    }

    @Test
    fun `omits host from the JSON when null`() {
        val payload = PairingPayload.of("NoHost", ByteArray(32) { 5 }, null, 45999)
        // Inspect the inner JSON the base64url wraps.
        val json = String(java.util.Base64.getUrlDecoder().decode(payload.encode()), Charsets.UTF_8)
        assertFalse("host must be omitted, not null", json.contains("host"))
        // And it decodes back with a null host.
        assertNull(PairingPayload.decode(payload.encode()).host)
    }

    @Test
    fun `rejects a future version`() {
        val future = PairingPayload(v = 2, name = "D", pk = PublicKeyCodec.encode(ByteArray(32) { 1 }), host = null, port = 1)
        assertThrows(IllegalArgumentException::class.java) { PairingPayload.decode(future.encode()) }
    }

    @Test
    fun `rejects garbage`() {
        assertThrows(IllegalArgumentException::class.java) { PairingPayload.decode("!!!not base64!!!") }
    }

    @Test
    fun `rejects a pk that is not 32 bytes`() {
        // base64url("hello") is valid base64url but decodes to 5 bytes.
        val badPk = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("hello".toByteArray())
        val json = """{"v":1,"name":"X","pk":"$badPk","port":45999}"""
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        assertThrows(IllegalArgumentException::class.java) { PairingPayload.decode(encoded) }
    }
}
