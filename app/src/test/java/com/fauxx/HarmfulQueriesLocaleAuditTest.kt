package com.fauxx

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Per-locale audit of the safety-critical `harmful_queries/<locale>.json` files.
 *
 * For each locale that has shipped its harmful-queries blocklist, this test asserts:
 *   1. The file exists and parses.
 *   2. None of the term lists are empty (an empty list trips QueryBlocklist's fail-closed
 *      path at runtime, but it's better to catch the regression at build time).
 *   3. The blocklist contains at least one entry referencing the locale's national
 *      suicide hotline (US 988, ES 024, FR 3114). A blocklist that omits its region's
 *      crisis line is the exact failure mode the spike memo
 *      `.devloop/spikes/multilingual-support.md` flags as critical.
 *
 * Failing this test means the locale's safety blocklist is missing a sentinel that
 * native-speaker review must restore before the locale can be added to
 * `BuildConfig.SHIPPED_LOCALES` (see memory file
 * `project_multilingual_safety_gate.md`).
 *
 * Read assets directly from `src/main/assets/...` (JVM unit test, no AssetManager).
 */
class HarmfulQueriesLocaleAuditTest {

    private val assetsRoot = File("src/main/assets")

    private data class HarmfulQueries(
        val class_a_terms: List<String> = emptyList(),
        val self_signal_terms: List<String> = emptyList(),
        val regex_patterns: List<String> = emptyList()
    )

    private fun load(path: String): HarmfulQueries {
        val file = File(assetsRoot, path)
        require(file.exists()) {
            "Expected $file to exist (cwd=${File(".").absolutePath}). Run from the app module root."
        }
        val type = object : TypeToken<HarmfulQueries>() {}.type
        return Gson().fromJson(file.readText(), type)
    }

    @Test
    fun `english blocklist contains 988 sentinel`() {
        // The legacy single-file location is the en source until the directory split lands.
        val parsed = load("harmful_queries.json")
        assertNonEmpty("en", parsed)
        assertContainsAny(
            "en",
            parsed,
            listOf("988"),
            "the US suicide & crisis lifeline number"
        )
    }

    @Test
    fun `spanish blocklist contains 024 and 016 sentinels`() {
        val parsed = load("harmful_queries/es.json")
        assertNonEmpty("es", parsed)
        assertContainsAny(
            "es",
            parsed,
            listOf("024"),
            "the Spanish suicide-prevention line (024, since 2022)"
        )
        assertContainsAny(
            "es",
            parsed,
            listOf("016"),
            "the Spanish gender-violence helpline (016)"
        )
    }

    @Test
    fun `french blocklist contains 3114 and 3919 sentinels`() {
        val parsed = load("harmful_queries/fr.json")
        assertNonEmpty("fr", parsed)
        assertContainsAny(
            "fr",
            parsed,
            listOf("3114"),
            "the French suicide-prevention line (3114, since 2021)"
        )
        assertContainsAny(
            "fr",
            parsed,
            listOf("3919"),
            "the French domestic-violence helpline (3919)"
        )
    }

    private fun assertNonEmpty(locale: String, parsed: HarmfulQueries) {
        assertTrue(
            "[$locale] class_a_terms must not be empty (would fail-closed at runtime)",
            parsed.class_a_terms.isNotEmpty()
        )
        assertTrue(
            "[$locale] self_signal_terms must not be empty",
            parsed.self_signal_terms.isNotEmpty()
        )
        assertTrue(
            "[$locale] regex_patterns must not be empty",
            parsed.regex_patterns.isNotEmpty()
        )
    }

    private fun assertContainsAny(
        locale: String,
        parsed: HarmfulQueries,
        needles: List<String>,
        description: String
    ) {
        val haystacks = (parsed.class_a_terms + parsed.self_signal_terms + parsed.regex_patterns)
            .map { it.lowercase() }
        for (needle in needles) {
            if (haystacks.any { it.contains(needle.lowercase()) }) return
        }
        fail(
            "[$locale] blocklist is missing a reference to $description. " +
                "Expected at least one term containing one of $needles. " +
                "Without this sentinel, fauxx could dispatch a synthetic query that " +
                "data brokers interpret as a real first-person distress signal — see " +
                ".devloop/spikes/multilingual-support.md for the threat model."
        )
    }
}
