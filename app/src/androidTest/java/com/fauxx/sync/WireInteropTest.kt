package com.fauxx.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.sync.crypto.DeviceIdentity
import com.fauxx.sync.wire.SealedFrame
import com.fauxx.sync.wire.SyncBody
import com.fauxx.sync.wire.SyncMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented wire interop (E13 #178): seal a real `PersonaUpsert` through the FXS1 frame with the
 * native crypto, recover it, prove no plaintext persona field leaks onto the wire, and prove an
 * unpaired sender's frame does not authenticate. Mirrors the desktop `no_plaintext_persona_fields_
 * on_the_wire` and `frame_from_unpaired_sender_is_rejected_on_receive`.
 */
@RunWith(AndroidJUnit4::class)
class WireInteropTest {

    private fun persona() = SyntheticPersona(
        id = "66666666-6666-4666-8666-666666666666",
        name = "Sync Persona",
        ageRange = "AGE_35_44",
        profession = "ENGINEER",
        region = "US_WEST",
        interests = setOf(CategoryPool.TECHNOLOGY, CategoryPool.SCIENCE, CategoryPool.GAMING),
        createdAt = 1_700_000_000_321,
        activeUntil = 1_700_600_000_654
    )

    @Test
    fun persona_round_trips_through_the_sealed_frame() {
        val alice = DeviceIdentity.generate()
        val bob = DeviceIdentity.generate()
        val p = persona()

        val frameBytes = SealedFrame(
            alice.seal(bob.publicKey, SyncMessage.personaUpsert(p).toPlaintext())
        ).toBytes()

        val frame = SealedFrame.fromBytes(frameBytes)
        assertNotNull(frame)
        val plaintext = bob.open(alice.publicKey, frame!!.envelope)
        assertNotNull(plaintext)
        val message = SyncMessage.fromPlaintext(plaintext!!)
        val recovered = (message.body as SyncBody.PersonaUpsert).persona
        assertEquals(p, recovered)
        assertEquals(p.activeUntil, recovered.activeUntil)
    }

    @Test
    fun no_plaintext_persona_fields_on_the_wire() {
        val alice = DeviceIdentity.generate()
        val bob = DeviceIdentity.generate()
        val p = persona()
        val frame = SealedFrame(
            alice.seal(bob.publicKey, SyncMessage.personaUpsert(p).toPlaintext())
        ).toBytes()

        for (needle in listOf(
            p.name.toByteArray(),
            p.region.toByteArray(),
            p.id.toByteArray(),
            p.profession.toByteArray(),
            "PersonaUpsert".toByteArray()
        )) {
            assertFalse(
                "plaintext leak in sealed frame: ${String(needle)}",
                containsSubsequence(frame, needle)
            )
        }
    }

    @Test
    fun frame_from_an_unpaired_sender_is_rejected() {
        val bob = DeviceIdentity.generate()
        val stranger = DeviceIdentity.generate()
        val paired = DeviceIdentity.generate()

        val frame = SealedFrame(
            stranger.seal(bob.publicKey, SyncMessage.personaUpsert(persona()).toPlaintext())
        ).toBytes()
        val parsed = SealedFrame.fromBytes(frame)
        assertNotNull(parsed)
        // Even attributing the stranger's frame to a paired sender fails the MAC.
        assertNull(bob.open(paired.publicKey, parsed!!.envelope))
    }

    private fun containsSubsequence(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
