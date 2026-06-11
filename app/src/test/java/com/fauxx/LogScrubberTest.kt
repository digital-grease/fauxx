package com.fauxx

import com.fauxx.data.model.ActionType
import com.fauxx.logging.LogScrubber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogScrubberTest {

    @Test
    fun `scrubs age range field`() {
        val input = "2026-03-29 12:00:00 I/Engine: ageRange=25-34 loaded"
        val result = LogScrubber.scrub(input)
        assertFalse(result.contains("25-34"))
        assertTrue(result.contains("[REDACTED]"))
    }

    @Test
    fun `scrubs gender field`() {
        val input = "gender: FEMALE"
        val result = LogScrubber.scrub(input)
        assertFalse(result.contains("FEMALE"))
        assertTrue(result.contains("[REDACTED]"))
    }

    @Test
    fun `scrubs profession field`() {
        val input = "profession=ENGINEER setting weights"
        val result = LogScrubber.scrub(input)
        assertFalse(result.contains("ENGINEER"))
    }

    @Test
    fun `scrubs DemographicCell dumps`() {
        val input = "sampled DemographicCell(age=AGE_25_34, profession=ENGINEER, " +
            "region=US_WEST, weight=0.01) for persona"
        val result = LogScrubber.scrub(input)
        assertFalse(result.contains("AGE_25_34"))
        assertFalse(result.contains("ENGINEER"))
        assertTrue(result.contains("[REDACTED]"))
    }

    @Test
    fun `scrubs interests field`() {
        val input = "interests: [GAMING, COOKING, SPORTS]"
        val result = LogScrubber.scrub(input)
        assertFalse(result.contains("GAMING"))
    }

    @Test
    fun `scrubs persona data`() {
        val input = "personaName=Rural Retiree archetype: rural_retiree"
        val result = LogScrubber.scrub(input)
        assertFalse(result.contains("Rural Retiree"))
        assertFalse(result.contains("rural_retiree"))
    }

    @Test
    fun `scrubs platform cache fields`() {
        val input = "scrapedCategories: [GAMING, TECH] platformName=Google"
        val result = LogScrubber.scrub(input)
        assertFalse(result.contains("GAMING"))
        assertFalse(result.contains("Google"))
    }

    @Test
    fun `scrubs email addresses`() {
        val input = "user contact: john.doe@example.com in log"
        val result = LogScrubber.scrub(input)
        assertFalse(result.contains("john.doe@example.com"))
    }

    @Test
    fun `scrubs phone numbers`() {
        val input = "phone: (555) 123-4567 found"
        val result = LogScrubber.scrub(input)
        assertFalse(result.contains("123-4567"))
    }

    @Test
    fun `scrubs UserDemographicProfile toString`() {
        val input = "Profile: UserDemographicProfile(ageRange=25-34, gender=MALE, interests=[GAMING])"
        val result = LogScrubber.scrub(input)
        assertFalse(result.contains("UserDemographicProfile"))
        assertFalse(result.contains("MALE"))
    }

    @Test
    fun `scrubs SyntheticPersona toString`() {
        val input = "Current: SyntheticPersona(name=Rural Retiree, age=67)"
        val result = LogScrubber.scrub(input)
        assertFalse(result.contains("SyntheticPersona"))
    }

    @Test
    fun `scrubs PlatformProfileCache toString`() {
        val input = "Cache: PlatformProfileCache(platformName=Google, scrapedCategories=[TECH])"
        val result = LogScrubber.scrub(input)
        assertFalse(result.contains("PlatformProfileCache"))
    }

    @Test
    fun `preserves safe log content`() {
        val input = "2026-03-29 12:00:00 I/PoisonEngine: Dispatched SEARCH_QUERY to MEDICAL category"
        val result = LogScrubber.scrub(input)
        assertEquals(input, result)
    }

    @Test
    fun `preserves timestamps and log levels`() {
        val input = "2026-03-29 12:00:00.123 W/CrawlListManager: Rate limit hit for domain example.com"
        val result = LogScrubber.scrub(input)
        assertEquals(input, result)
    }

    @Test
    fun `handles empty string`() {
        assertEquals("", LogScrubber.scrub(""))
    }

    @Test
    fun `handles string with no sensitive data`() {
        val input = "Module started successfully with 15 URLs queued"
        assertEquals(input, LogScrubber.scrub(input))
    }

    // --- scrubForExport: coarsen the SEARCH_QUERY payload ----------------------

    @Test
    fun `scrubForExport coarsens the search query and drops the verbatim text`() {
        // A query can echo the user's own custom-interest text verbatim (it seeds the Markov
        // generator), so on export it must be replaced with a non-reversible summary.
        val input = "[MEDICAL] vintage synth restoration · via BING"
        val result = LogScrubber.scrubForExport(ActionType.SEARCH_QUERY, input)

        assertFalse("the verbatim query text must not survive export", result.contains("vintage"))
        assertFalse(result.contains("synth"))
        assertTrue("the category is kept for diagnostics", result.contains("[MEDICAL]"))
        assertTrue("the engine is kept for diagnostics", result.contains("· via BING"))
        assertEquals("[MEDICAL] <3-word query> · via BING", result)
    }

    @Test
    fun `scrubForExport summarizes a single-word query`() {
        val result = LogScrubber.scrubForExport(ActionType.SEARCH_QUERY, "[FINANCE] crypto · via GOOGLE")
        assertEquals("[FINANCE] <1-word query> · via GOOGLE", result)
    }

    @Test
    fun `scrubForExport leaves a blocked search detail unchanged`() {
        // The blocked path detail is a fixed safety string, not user content, and has no
        // ` via ENGINE` suffix, so it is not coarsened.
        val input = "[BLOCKED] query suppressed by safety guard"
        assertEquals(input, LogScrubber.scrubForExport(ActionType.SEARCH_QUERY, input))
    }

    @Test
    fun `scrubForExport does not coarsen non-search action detail`() {
        // PAGE_VISIT etc. carry corpus-derived decoy content (URLs/domains/cities) which is safe
        // to keep; only the generic PII scrub applies.
        val url = "https://example.com/news/some-article"
        assertEquals(url, LogScrubber.scrubForExport(ActionType.PAGE_VISIT, url))
    }
}
