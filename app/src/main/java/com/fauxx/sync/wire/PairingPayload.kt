package com.fauxx.sync.wire

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import java.util.Base64

/**
 * The compact pairing blob carried in the QR (and the manual-paste fallback): `base64url(JSON)`
 * where the JSON is `{"v":..,"name":..,"pk":..,"host":..,"port":..}` with the literal short keys.
 * Matches the desktop `wire::PairingPayload`. `host` is OMITTED (not null) when absent.
 *
 * The load-bearing interop checks are decode-side acceptance plus field equality (Gson may order
 * keys differently than serde on encode, which is fine: both sides decode by field).
 */
data class PairingPayload(
    @SerializedName("v") val v: Int,
    @SerializedName("name") val name: String,
    @SerializedName("pk") val pk: String,
    @SerializedName("host") val host: String?,
    @SerializedName("port") val port: Int
) {

    /**
     * Encode to the compact base64url string carried in the QR. `host` is omitted when null
     * (Gson does not serialize nulls by default, mirroring serde's `skip_serializing_if`).
     */
    fun encode(): String {
        val json = GSON.toJson(this)
        return ENCODER.encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    /** Decode and return the public-key bytes, failing closed on bad encoding or wrong length. */
    fun publicKeyBytes(): ByteArray = PublicKeyCodec.decode(pk)

    companion object {
        // Gson with serializeNulls OFF (the default) so a null host is dropped from the JSON.
        private val GSON = Gson()
        private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        private val DECODER: Base64.Decoder = Base64.getUrlDecoder()

        /** Build a pairing payload from this device's name, public key, and connection hint. */
        fun of(name: String, publicKey: ByteArray, host: String?, port: Int): PairingPayload =
            PairingPayload(SyncConstants.PROTOCOL_VERSION, name, PublicKeyCodec.encode(publicKey), host, port)

        /**
         * Decode a pairing payload from the QR string, failing closed on bad base64url, non-JSON,
         * a missing required field, a `v` above the supported version, or a `pk` that is not
         * exactly 32 bytes. A future layout is never misread.
         */
        fun decode(encoded: String): PairingPayload {
            val jsonBytes = try {
                DECODER.decode(encoded.trim())
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("pairing payload not valid base64url", e)
            }
            val root = try {
                JsonParser.parseString(String(jsonBytes, Charsets.UTF_8))
            } catch (e: Exception) {
                throw IllegalArgumentException("pairing payload not valid JSON", e)
            }
            if (!root.isJsonObject) throw IllegalArgumentException("pairing payload is not a JSON object")
            val obj: JsonObject = root.asJsonObject
            // Required fields (serde rejects a payload missing any of these; match that, fail closed).
            for (key in REQUIRED_KEYS) {
                require(obj.has(key) && !obj.get(key).isJsonNull) { "pairing payload missing $key" }
            }
            val payload = PairingPayload(
                v = obj.get("v").asInt,
                name = obj.get("name").asString,
                pk = obj.get("pk").asString,
                host = obj.get("host")?.takeIf { !it.isJsonNull }?.asString,
                port = obj.get("port").asInt
            )
            require(payload.v <= SyncConstants.PROTOCOL_VERSION) {
                "unsupported pairing payload version ${payload.v} (this build speaks ${SyncConstants.PROTOCOL_VERSION})"
            }
            // Validate the pk decodes to exactly 32 bytes now, before any pairing UI is shown.
            payload.publicKeyBytes()
            return payload
        }

        private val REQUIRED_KEYS = listOf("v", "name", "pk", "port")
    }
}
