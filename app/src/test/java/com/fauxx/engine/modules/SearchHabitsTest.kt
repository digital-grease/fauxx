package com.fauxx.engine.modules

import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.di.QueryGrammarSeed
import com.fauxx.locale.SupportedLocale
import com.fauxx.support.seededRandom
import com.fauxx.targeting.layer3.PersonaChannel
import com.fauxx.targeting.layer3.PersonaRotationLayer
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-persona search habits (#322 item 4): stable per persona, drawn from the documented ranges,
 * usual engines in proportion to market share, and the engine pick that honours them.
 */
class SearchHabitsTest {

    private val keys = (0 until 2_000).map { "persona-$it" }

    @Test
    fun `habits are stable for a persona and differ across personas`() {
        val first = SearchHabits.derive("persona-a", SupportedLocale.EN, SEARCH_ENGINE_IDS)
        assertEquals(first, SearchHabits.derive("persona-a", SupportedLocale.EN, SEARCH_ENGINE_IDS))
        val all = keys.map { SearchHabits.derive(it, SupportedLocale.EN, SEARCH_ENGINE_IDS) }
        assertTrue("click chances must vary across personas", all.map { it.clickChance }.toSet().size > 50)
    }

    @Test
    fun `values stay inside their ranges`() {
        for (key in keys) {
            val habits = SearchHabits.derive(key, SupportedLocale.EN, SEARCH_ENGINE_IDS)
            assertTrue(habits.primaryShare in 0.85f..0.95f)
            assertTrue(habits.refineChance in 0.15f..0.45f)
            assertTrue(habits.clickChance in 0.65f..0.90f)
        }
    }

    @Test
    fun `usual engines follow market share in the noise language`() {
        fun shareOf(engine: String, locale: SupportedLocale): Double =
            keys.count { SearchHabits.derive(it, locale, SEARCH_ENGINE_IDS).primaryEngine == engine }.toDouble() / keys.size
        // EN weights: Google 867 of 998; RU: Yandex 710 of 989.
        assertEquals(0.869, shareOf("google", SupportedLocale.EN), 0.03)
        assertEquals(0.718, shareOf("yandex", SupportedLocale.RU), 0.03)
        assertTrue("small engines stay possible", shareOf("bing", SupportedLocale.EN) > 0.05)
    }

    @Test
    fun `the usual engine is always one the user allows`() {
        val allowed = listOf("duckduckgo", "bing")
        for (key in keys.take(300)) {
            assertTrue(SearchHabits.derive(key, SupportedLocale.EN, allowed).primaryEngine in allowed)
        }
    }

    @Test
    fun `most searches go to the usual engine and the rest go elsewhere`() {
        val habits = SearchHabits("google", primaryShare = 0.9f, refineChance = 0.4f, clickChance = 0.8f)
        val random = seededRandom()
        val picks = (0 until 10_000).map { habits.pickEngine(SEARCH_ENGINE_IDS, SupportedLocale.EN, random) }
        assertEquals(0.9, picks.count { it == "google" } / 10_000.0, 0.015)
        val others = picks.filterNot { it == "google" }
        // Among the others, Bing's share dominates as it does in the market (87 of 131).
        assertEquals(87.0 / 131, others.count { it == "bing" }.toDouble() / others.size, 0.05)
    }

    @Test
    fun `a usual engine that cannot be used sends every search elsewhere`() {
        val habits = SearchHabits("google", primaryShare = 0.95f, refineChance = 0.4f, clickChance = 0.8f)
        val random = seededRandom()
        val picks = (0 until 500).map { habits.pickEngine(listOf("bing", "yahoo"), SupportedLocale.EN, random) }
        assertTrue(picks.all { it == "bing" || it == "yahoo" })
        assertEquals("a single usable engine is the usual one", "google",
            habits.pickEngine(listOf("google"), SupportedLocale.EN, random))
    }

    @Test
    fun `the persona's habits come through the SEARCH channel`() {
        val persona = SyntheticPersona(
            id = "persona-b", name = "n", ageRange = "AGE_35_44", profession = "ENGINEER",
            region = "US_MIDWEST", interests = setOf(CategoryPool.TECHNOLOGY), activeUntil = Long.MAX_VALUE,
        )
        val layer = mockk<PersonaRotationLayer> { every { personaForChannel(PersonaChannel.SEARCH) } returns persona }
        val source = PersonaSearchHabits(layer, QueryGrammarSeed { 7L })
        assertEquals(
            SearchHabits.derive("persona-b", SupportedLocale.EN, SEARCH_ENGINE_IDS),
            source.habits(SupportedLocale.EN, SEARCH_ENGINE_IDS),
        )
    }

    @Test
    fun `without a persona the habits come from the install seed`() {
        val layer = mockk<PersonaRotationLayer> { every { personaForChannel(any()) } returns null }
        val install = PersonaSearchHabits(layer, QueryGrammarSeed { 7L }).habits(SupportedLocale.EN, SEARCH_ENGINE_IDS)
        assertEquals(SearchHabits.derive("install:7", SupportedLocale.EN, SEARCH_ENGINE_IDS), install)
        val other = PersonaSearchHabits(layer, QueryGrammarSeed { 8L }).habits(SupportedLocale.EN, SEARCH_ENGINE_IDS)
        assertNotEquals("installs must not share one set of habits", install, other)
    }
}
