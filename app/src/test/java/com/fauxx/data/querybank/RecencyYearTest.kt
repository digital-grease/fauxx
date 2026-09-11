package com.fauxx.data.querybank

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Locks the recency-year contract for issue #256: recency queries track the real clock,
 * intentional historical years survive untouched, and the placeholder itself never
 * escapes into a dispatched query.
 */
class RecencyYearTest {

    private val assetsRoot = File("src/main/assets")
    private val gson = Gson()
    private val stringListType = object : TypeToken<List<String>>() {}.type

    @Test
    fun `a recency query resolves to the supplied year`() {
        assertEquals(
            "best smartphones 2031",
            freshenRecencyYear("best smartphones \$YEAR\$", 2031)
        )
    }

    @Test
    fun `a query with no token is returned unchanged`() {
        // The overwhelmingly common case, and the one that must not allocate surprises.
        val q = "how to fix slow Wi-Fi at home"
        assertEquals(q, freshenRecencyYear(q, 2031))
    }

    @Test
    fun `intentional historical years are never touched`() {
        // The reason the token is explicit rather than inferred: a heuristic that bumped
        // trailing years would wreck all of these.
        for (q in listOf(
            "moon landing 1969 footage",
            "Cyberpunk 2077 endings explained",
            "World Cup 2026 schedule",
            "2026 midterm election dates",
        )) {
            assertEquals(q, freshenRecencyYear(q, 2031))
        }
    }

    @Test
    fun `multiple tokens in one query all resolve`() {
        assertEquals(
            "2031 vs 2031 comparison",
            freshenRecencyYear("\$YEAR\$ vs \$YEAR\$ comparison", 2031)
        )
    }

    @Test
    fun `currentYear returns a plausible calendar year`() {
        // Guards against a units mix-up (Calendar.YEAR vs a two-digit or epoch value).
        assertTrue(currentYear() in 2024..2100)
    }

    @Test
    fun `the shipped corpus still carries the token`() {
        // Regression guard: if a bulk corpus edit ever reverted the migration, recency
        // queries would silently freeze at their authoring year again.
        val tokened = corpusEntries().count { it.contains(YEAR_TOKEN) }
        assertTrue(
            "Expected the query corpus to still use $YEAR_TOKEN for recency queries, found none",
            tokened > 0
        )
    }

    @Test
    fun `no token survives resolution anywhere in the corpus`() {
        // The token must never reach a search engine. Resolving every shipped entry has
        // to leave nothing behind. Checks the token specifically, NOT any dollar sign: a
        // perfectly good future entry like "how to save \$500 a month" would otherwise fail
        // here with a message blaming a placeholder that isn't there.
        val leaked = corpusEntries()
            .map { freshenRecencyYear(it, 2031) }
            .filter { it.contains(YEAR_TOKEN) }
        assertEquals(
            "Corpus entries still contain the $YEAR_TOKEN placeholder after resolution: " +
                leaked.take(5).joinToString(),
            0,
            leaked.size
        )
    }

    @Test
    fun `a malformed token variant is not silently half-resolved`() {
        // Guards the shape of the token itself: a corpus typo like \$YEAR (no closing sigil)
        // must not be partially substituted into something that ships a stray sigil.
        assertEquals("best phones \$YEAR", freshenRecencyYear("best phones \$YEAR", 2031))
        assertEquals("save \$500 a month", freshenRecencyYear("save \$500 a month", 2031))
    }

    /** Every query in every shipped locale bank. */
    private fun corpusEntries(): List<String> {
        val roots = listOf("query_banks", "query_banks/es", "query_banks/fr", "query_banks/ru")
        return roots.flatMap { dir ->
            File(assetsRoot, dir).listFiles { f -> f.extension == "json" }
                ?.flatMap { gson.fromJson<List<String>>(it.readText(), stringListType) }
                .orEmpty()
        }
    }
}
