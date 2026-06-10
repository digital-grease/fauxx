package com.fauxx

import com.fauxx.data.querybank.SearchRefinements
import com.fauxx.locale.SupportedLocale
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E5 (#175): refinement templates must narrow the session goal, not replace it —
 * topical relatedness is the property that makes a chain an intent chain, and goal
 * preservation is what lets the blocklist gate see the full original query content.
 */
class SearchRefinementsTest {

    private val goal = "trail running shoes"

    /** Goal or a light reformulation (one edge word dropped) — the relatedness cores. */
    private val relatedCores = listOf(goal, "running shoes", "trail running")

    @Test
    fun `every refinement stays topically related to the goal`() {
        SupportedLocale.entries.forEach { locale ->
            val refinements = SearchRefinements.refine(goal, locale, count = 3, random = Random(1))
            assertEquals(3, refinements.size)
            refinements.forEach { r ->
                assertTrue(
                    "[$locale] '$r' is not related to '$goal'",
                    relatedCores.any { core -> r.contains(core) }
                )
                assertTrue("[$locale] '$r' must narrow, not repeat", r != goal)
            }
        }
    }

    @Test
    fun `short goals are never truncated`() {
        repeat(50) { seed ->
            SearchRefinements.refine("hiking boots", SupportedLocale.EN, 3, Random(seed.toLong()))
                .forEach { r ->
                    assertTrue("'$r' must contain the 2-word goal verbatim", r.contains("hiking boots"))
                }
        }
    }

    @Test
    fun `refinements within a session are distinct`() {
        val refinements = SearchRefinements.refine(goal, SupportedLocale.EN, count = 3, random = Random(7))
        assertEquals(refinements.size, refinements.toSet().size)
    }

    @Test
    fun `refinements are localized`() {
        val es = SearchRefinements.refine(goal, SupportedLocale.ES, count = 7, random = Random(1))
        assertTrue("ES templates expected", es.any { it.contains("mejor") || it.contains("opiniones") })
        val ru = SearchRefinements.refine(goal, SupportedLocale.RU, count = 7, random = Random(1))
        assertTrue("RU templates expected", ru.any { it.any { ch -> ch in 'а'..'я' } })
    }

    @Test
    fun `count is clamped to the template pool and to zero`() {
        assertTrue(SearchRefinements.refine(goal, SupportedLocale.EN, 99, Random(1)).size <= 16)
        assertTrue(SearchRefinements.refine(goal, SupportedLocale.EN, -1, Random(1)).isEmpty())
    }
}
