package com.fauxx.logging

import com.fauxx.data.model.ActionType

/**
 * Strips sensitive data from log output before export.
 *
 * Removes demographic profile fields, persona data, platform cache entries,
 * and common PII patterns (emails, phone numbers) to ensure no user-identifying
 * information leaves the device via crash reports or debug log exports.
 */
object LogScrubber {

    private val SCRUB_PATTERNS = listOf(
        // Data class toString dumps (must run BEFORE field-level patterns so the
        // closing paren isn't consumed by the greedy \S+ in field patterns)
        Regex("""UserDemographicProfile\([^)]*\)"""),
        Regex("""SyntheticPersona\([^)]*\)"""),
        Regex("""PlatformProfileCache\([^)]*\)"""),
        // Demographic profile fields
        Regex("""(?i)(ageRange|gender|profession|region|interests)\s*[=:]\s*\S+"""),
        // Persona data
        Regex("""(?i)(personaName|personaAge|personaInterests|syntheticPersona|archetype)\s*[=:]\s*[^\n,}\]]+"""),
        // Platform cache / scraper data
        Regex("""(?i)(scrapedCategories|platformName|lastScraped)\s*[=:]\s*[^\n,}\]]+"""),
        // Email addresses
        Regex("""[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}"""),
        // Phone numbers (various formats)
        Regex("""\b(\+?1?[-.\s]?)?\(?\d{3}\)?[-.\s]?\d{3}[-.\s]?\d{4}\b"""),
    )

    private const val REDACTED = "[REDACTED]"

    /**
     * Scrubs all sensitive patterns from the given [text].
     * Returns a copy with matched patterns replaced by `[REDACTED]`.
     */
    fun scrub(text: String): String {
        var result = text
        for (pattern in SCRUB_PATTERNS) {
            result = pattern.replace(result, REDACTED)
        }
        return result
    }

    /**
     * Matches a SearchPoison action-log detail of the form `[CATEGORY] <query> · via <ENGINE>`.
     * The `<query>` is the only action-log payload that can echo the user's own custom interests
     * verbatim (custom interests seed the Markov query generator), so it is coarsened on export.
     */
    private val SEARCH_QUERY_DETAIL = Regex("""^(\[[^\]]*] )(.*)( · via \S+)$""")

    /**
     * Export-time scrub keyed on [actionType]. For [ActionType.SEARCH_QUERY] the free-text query
     * payload is replaced with a non-reversible word-count summary, keeping the `[CATEGORY]` and
     * ` · via <ENGINE>` parts so diagnostics (e.g. verifying which SERP fired) still work. The
     * other action types carry only corpus-derived decoy content (URLs, domains, cities, UA), so
     * they are left to the generic pattern [scrub]. A SEARCH_QUERY detail that does not match the
     * query shape (e.g. `[BLOCKED] ...`) is passed through unchanged.
     */
    fun scrubForExport(actionType: ActionType, detail: String): String {
        val coarsened = if (actionType == ActionType.SEARCH_QUERY) coarsenSearchQuery(detail) else detail
        return scrub(coarsened)
    }

    private fun coarsenSearchQuery(detail: String): String {
        val match = SEARCH_QUERY_DETAIL.matchEntire(detail) ?: return detail
        val wordCount = match.groupValues[2].split(Regex("""\s+""")).count { it.isNotBlank() }
        return "${match.groupValues[1]}<$wordCount-word query>${match.groupValues[3]}"
    }
}
