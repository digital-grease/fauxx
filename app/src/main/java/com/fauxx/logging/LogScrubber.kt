package com.fauxx.logging

/**
 * Strips sensitive data from log output before export.
 *
 * Removes demographic profile fields, persona data, platform cache entries,
 * and common PII patterns (emails, phone numbers) to ensure no user-identifying
 * information leaves the device via crash reports or debug log exports.
 */
object LogScrubber {

    private val SCRUB_PATTERNS = listOf(
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
        // UserDemographicProfile toString / data class dumps
        Regex("""UserDemographicProfile\([^)]*\)"""),
        // SyntheticPersona toString / data class dumps
        Regex("""SyntheticPersona\([^)]*\)"""),
        // PlatformProfileCache toString / data class dumps
        Regex("""PlatformProfileCache\([^)]*\)"""),
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
}
