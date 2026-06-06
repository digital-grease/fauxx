package com.fauxx.data.db

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Helper for the optional [ActionLogEntity.metadata] column (issue #73): a small ordered map
 * of scalar facts about an action (page title, cookie count, resolved-IP count, search engine,
 * route summary, User-Agent, ...) serialized as a JSON object so the detail view can render
 * label/value rows and the export paths can include it.
 *
 * Values are scalars only — no page content, no filename or cookie-name lists — per the
 * privacy decision. Export paths still run each value through
 * [com.fauxx.logging.LogScrubber] as a backstop.
 */
object LogMetadata {
    // Stable keys: used as both the on-disk JSON object keys and the detail-row labels.
    const val PAGE_TITLE = "Page title"
    const val COOKIES_IN_JAR = "Cookies in jar"
    const val RESOURCES_LOADED = "Resources loaded"
    const val DNS_IPS = "Resolved IPs"
    const val SEARCH_ENGINE = "Engine"
    const val HTTP_STATUS = "HTTP status"
    const val ROUTE_POINTS = "Route points"
    const val ROUTE_DISTANCE = "Route distance"
    const val ROUTE_DURATION = "Route duration"
    const val USER_AGENT = "User-Agent"

    private val gson = Gson()
    private val mapType = object : TypeToken<LinkedHashMap<String, String>>() {}.type

    /**
     * Build a metadata JSON object from ordered key->value pairs, dropping any whose value is
     * null or blank. Returns null when nothing is left (so the column stays NULL rather than
     * storing an empty object). Insertion order is preserved for display.
     */
    fun toJson(vararg pairs: Pair<String, String?>): String? {
        val map = LinkedHashMap<String, String>()
        for ((key, value) in pairs) {
            val v = value?.takeIf { it.isNotBlank() } ?: continue
            map[key] = v
        }
        return if (map.isEmpty()) null else gson.toJson(map)
    }

    /** Parse a metadata JSON object back to ordered pairs. Null/blank/malformed -> empty list. */
    fun parse(json: String?): List<Pair<String, String>> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val map: LinkedHashMap<String, String> = gson.fromJson(json, mapType)
            map.map { it.key to it.value }
        }.getOrDefault(emptyList())
    }
}
