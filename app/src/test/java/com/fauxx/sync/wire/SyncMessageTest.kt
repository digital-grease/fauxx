package com.fauxx.sync.wire

import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM envelope test (mirror the desktop `sync_message_*`). Proves the `{protocolVersion, kind,
 * body}` shape, lossless persona round-trip, and the fail-closed posture on unknown kind / future
 * version. Includes a desktop-shaped golden JSON to prove cross-implementation decode.
 */
class SyncMessageTest {

    private fun persona() = SyntheticPersona(
        id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        name = "Round Trip",
        ageRange = "AGE_35_44",
        profession = "TEACHER",
        region = "CANADA",
        interests = setOf(CategoryPool.ACADEMIC, CategoryPool.HISTORY),
        createdAt = 1_700_000_000_000,
        activeUntil = 1_800_000_000_000
    )

    @Test
    fun `persona upsert round trips every field`() {
        val msg = SyncMessage.personaUpsert(persona())
        val back = SyncMessage.fromPlaintext(msg.toPlaintext())
        assertEquals(SyncConstants.PROTOCOL_VERSION, back.protocolVersion)
        val body = back.body as SyncBody.PersonaUpsert
        assertEquals(persona(), body.persona)
        assertEquals(1_800_000_000_000, body.persona.activeUntil)
    }

    @Test
    fun `json carries the literal protocol and camelCase keys`() {
        val json = String(SyncMessage.personaUpsert(persona()).toPlaintext(), Charsets.UTF_8)
        assertTrue(json.contains("\"protocolVersion\""))
        assertTrue(json.contains("\"kind\""))
        assertTrue(json.contains("PersonaUpsert"))
        assertTrue(json.contains("\"ageRange\""))
        assertTrue(json.contains("\"createdAt\""))
        assertTrue(json.contains("\"activeUntil\""))
        assertTrue(json.contains("\"interests\""))
    }

    @Test
    fun `decodes a desktop-shaped envelope JSON (cross-impl golden)`() {
        // Exactly the shape the desktop serde emits, including the additive `schemaVersion` key the
        // phone's lenient reader must ignore.
        val desktopJson = """
            {"protocolVersion":1,"kind":"PersonaUpsert","body":{
            "id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","name":"Round Trip",
            "ageRange":"AGE_35_44","profession":"TEACHER","region":"CANADA",
            "interests":["ACADEMIC","HISTORY"],"createdAt":1700000000000,
            "activeUntil":1800000000000,"schemaVersion":1}}
        """.trimIndent()
        val msg = SyncMessage.fromPlaintext(desktopJson.toByteArray(Charsets.UTF_8))
        val p = (msg.body as SyncBody.PersonaUpsert).persona
        assertEquals("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", p.id)
        assertEquals("AGE_35_44", p.ageRange)
        assertEquals("CANADA", p.region)
        assertEquals(setOf(CategoryPool.ACADEMIC, CategoryPool.HISTORY), p.interests)
        assertEquals(1_700_000_000_000, p.createdAt)
        assertEquals(1_800_000_000_000, p.activeUntil)
    }

    @Test
    fun `rejects an unknown kind at parse (fail closed)`() {
        val unknown = """{"protocolVersion":1,"kind":"TotallyUnknownKind","body":{}}"""
        assertThrows(SyncParseException::class.java) {
            SyncMessage.fromPlaintext(unknown.toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun `rejects the protocol-defined-but-unconsumed kinds`() {
        for (kind in listOf("PublicIpReport", "CoordinationState", "SignedArtifact", "PersonaPack")) {
            val json = """{"protocolVersion":1,"kind":"$kind","body":{}}"""
            assertThrows(
                "kind $kind must be rejected",
                SyncParseException::class.java
            ) { SyncMessage.fromPlaintext(json.toByteArray(Charsets.UTF_8)) }
        }
    }

    @Test
    fun `rejects a future protocol version`() {
        val future = """{"protocolVersion":2,"kind":"PersonaUpsert","body":{}}"""
        assertThrows(SyncParseException::class.java) {
            SyncMessage.fromPlaintext(future.toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun `rejects a persona body missing a required field`() {
        // Missing activeUntil.
        val missing = """{"protocolVersion":1,"kind":"PersonaUpsert","body":{
            "id":"x","name":"y","ageRange":"AGE_35_44","profession":"TEACHER","region":"CANADA",
            "interests":["HISTORY"],"createdAt":1}}"""
        assertThrows(SyncParseException::class.java) {
            SyncMessage.fromPlaintext(missing.toByteArray(Charsets.UTF_8))
        }
    }
}
