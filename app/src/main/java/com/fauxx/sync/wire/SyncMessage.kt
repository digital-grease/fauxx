package com.fauxx.sync.wire

import com.fauxx.data.model.SyntheticPersona
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Thrown when an opened plaintext is not a [SyncMessage] this build accepts. Always fail closed. */
class SyncParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The operation carried inside a [SyncMessage]. E13 emits and consumes only [PersonaUpsert]. */
sealed class SyncBody {
    /** The stable `kind` discriminator string this body serializes as. */
    abstract val kind: String

    /** Insert or replace a persona on the receiver, keyed on the persona `id`. */
    data class PersonaUpsert(val persona: SyntheticPersona) : SyncBody() {
        override val kind: String get() = KIND_PERSONA_UPSERT
    }

    companion object {
        const val KIND_PERSONA_UPSERT = "PersonaUpsert"
    }
}

/**
 * The versioned envelope whose UTF-8 JSON is what the sealed channel encrypts. Matches the desktop
 * `wire::SyncMessage`: `{"protocolVersion":1,"kind":"PersonaUpsert","body":{...persona...}}`
 * (serde flattens `protocolVersion` over an adjacently-tagged body; Gson has no adjacent tagging,
 * so the JSON is assembled by hand into the same shape).
 *
 * On parse, a `protocolVersion` above this build's, or any `kind` other than `PersonaUpsert`
 * (including the protocol-defined-but-unconsumed `PublicIpReport` / `CoordinationState` /
 * `SignedArtifact` / `PersonaPack`), is REJECTED, never silently ignored.
 */
class SyncMessage private constructor(
    val protocolVersion: Int,
    val body: SyncBody
) {

    /** Serialize to the canonical UTF-8 JSON plaintext the channel seals. */
    fun toPlaintext(): ByteArray {
        val obj = JsonObject()
        obj.addProperty("protocolVersion", protocolVersion)
        when (val b = body) {
            is SyncBody.PersonaUpsert -> {
                obj.addProperty("kind", b.kind)
                obj.add("body", GSON.toJsonTree(b.persona))
            }
        }
        return GSON.toJson(obj).toByteArray(Charsets.UTF_8)
    }

    companion object {
        private val GSON = Gson()

        /** The eight required Android persona keys; their presence is enforced like serde would. */
        private val REQUIRED_PERSONA_KEYS = listOf(
            "id", "name", "ageRange", "profession", "region", "interests", "createdAt", "activeUntil"
        )

        /** Wrap a persona upsert at the current protocol version. */
        fun personaUpsert(persona: SyntheticPersona): SyncMessage =
            SyncMessage(SyncConstants.PROTOCOL_VERSION, SyncBody.PersonaUpsert(persona))

        /**
         * Parse a [SyncMessage] from opened (decrypted) plaintext. Throws [SyncParseException] on
         * anything malformed, an unsupported `protocolVersion`, or an unknown/unconsumed `kind`.
         */
        fun fromPlaintext(bytes: ByteArray): SyncMessage {
            val root = try {
                JsonParser.parseString(String(bytes, Charsets.UTF_8))
            } catch (e: Exception) {
                throw SyncParseException("sync message is not valid JSON", e)
            }
            if (!root.isJsonObject) throw SyncParseException("sync message is not a JSON object")
            val obj = root.asJsonObject

            val protocolVersion = readInt(obj, "protocolVersion")
            if (protocolVersion > SyncConstants.PROTOCOL_VERSION) {
                throw SyncParseException(
                    "unsupported sync protocol version $protocolVersion " +
                        "(this build speaks ${SyncConstants.PROTOCOL_VERSION})"
                )
            }

            val kindEl = obj.get("kind")
            if (kindEl == null || kindEl.isJsonNull || !kindEl.isJsonPrimitive) {
                throw SyncParseException("sync message missing kind")
            }
            val kind = kindEl.asString

            return when (kind) {
                SyncBody.KIND_PERSONA_UPSERT -> {
                    val bodyEl = obj.get("body")
                    if (bodyEl == null || !bodyEl.isJsonObject) {
                        throw SyncParseException("PersonaUpsert body is missing or not an object")
                    }
                    val bodyObj = bodyEl.asJsonObject
                    for (key in REQUIRED_PERSONA_KEYS) {
                        if (!bodyObj.has(key) || bodyObj.get(key).isJsonNull) {
                            throw SyncParseException("PersonaUpsert body missing $key")
                        }
                    }
                    val persona = try {
                        GSON.fromJson(bodyObj, SyntheticPersona::class.java)
                    } catch (e: Exception) {
                        throw SyncParseException("invalid persona body", e)
                    } ?: throw SyncParseException("invalid persona body")
                    SyncMessage(protocolVersion, SyncBody.PersonaUpsert(persona))
                }
                // Defined by the protocol but not consumed by this build: reject, never ignore.
                else -> throw SyncParseException("unknown or unconsumed sync kind '$kind'")
            }
        }

        private fun readInt(obj: JsonObject, key: String): Int {
            val el = obj.get(key)
            if (el == null || el.isJsonNull || !el.isJsonPrimitive) {
                throw SyncParseException("sync message missing $key")
            }
            return try {
                el.asInt
            } catch (e: NumberFormatException) {
                throw SyncParseException("sync message $key is not an integer", e)
            }
        }
    }
}
