package com.fauxx.data.querybank

import com.fauxx.data.model.SyntheticPersona
import com.fauxx.di.QueryGrammarSeed
import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
import com.fauxx.targeting.layer3.PersonaRotationLayer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GrammarQueryGeneratorTest {

    private val HEAD = "trail shoes"

    private val queryBankManager: QueryBankManager = mockk(relaxed = true) {
        every { randomQuery(any()) } returns HEAD
    }
    private val markov: MarkovQueryGenerator = mockk(relaxed = true) {
        every { generate(any(), any()) } returns HEAD
    }
    private val localeManager: LocaleManager = mockk(relaxed = true) {
        every { currentLocale } returns SupportedLocale.EN
    }
    private val personaLayer: PersonaRotationLayer = mockk(relaxed = true) {
        every { activePersona() } returns null
    }

    private fun gen(
        seed: Long,
        random: Random = Random.Default,
        blocklist: QueryBlocklist = mockk(relaxed = true) { every { isBlocked(any()) } returns false },
        persona: SyntheticPersona? = null,
    ): GrammarQueryGenerator {
        every { personaLayer.activePersona() } returns persona
        return GrammarQueryGenerator(queryBankManager, blocklist, localeManager, markov, personaLayer, QueryGrammarSeed(seed), random)
    }

    private fun persona(vararg interests: CategoryPool) = SyntheticPersona(
        id = "p", name = "n", ageRange = "AGE_35_44", profession = "FINANCE_PROF",
        region = "US_MIDWEST", interests = interests.toSet(), activeUntil = Long.MAX_VALUE,
    )

    /** Fraction of N generations that got refined (output differs from the fixed corpus head). */
    private fun refineRate(g: GrammarQueryGenerator, n: Int = 2000): Double {
        var refined = 0
        repeat(n) { if (g.generate(CategoryPool.AUTOMOTIVE) != HEAD) refined++ }
        return refined.toDouble() / n
    }

    @Test
    fun `produces a non-blank query anchored on the corpus head`() {
        val out = gen(1L).generate(CategoryPool.AUTOMOTIVE)
        assertTrue(out.isNotBlank())
        assertTrue("output should be the head or a refinement of it", out.contains("trail") || out == HEAD)
    }

    @Test
    fun `never returns a blocklisted query`() {
        // Block everything: grammar resamples, then the Markov fallback (also blocked) -> empty.
        val blockAll: QueryBlocklist = mockk(relaxed = true) { every { isBlocked(any()) } returns true }
        assertEquals("", gen(1L, blocklist = blockAll).generate(CategoryPool.AUTOMOTIVE))
    }

    @Test
    fun `falls back to Markov when grammar output is blocked but Markov is permitted`() {
        // isBlocked: true for any grammar output, false only for the bare Markov fallback string
        // (specific stub overrides the any() stub in mockk).
        val markovOnly: QueryBlocklist = mockk {
            every { isBlocked(any()) } returns true
            every { isBlocked("markov fallback") } returns false
        }
        every { markov.generate(any(), any()) } returns "markov fallback"
        try {
            assertEquals("markov fallback", gen(1L, blocklist = markovOnly).generate(CategoryPool.AUTOMOTIVE))
        } finally {
            every { markov.generate(any(), any()) } returns HEAD
        }
    }

    @Test
    fun `is deterministic for the same seed and random sequence`() {
        val a = gen(99L, Random(7)).let { g -> List(50) { g.generate(CategoryPool.AUTOMOTIVE) } }
        val b = gen(99L, Random(7)).let { g -> List(50) { g.generate(CategoryPool.AUTOMOTIVE) } }
        assertEquals(a, b)
    }

    @Test
    fun `the refinement distribution varies across install seeds (no shared fleet signature)`() {
        // Each install's seed fixes its refinement frequency; across many seeds those rates must
        // span a real range, i.e. installs do NOT collapse onto one shared query distribution.
        val rates = (1L..20L).map { refineRate(gen(it), n = 600) }
        val spread = rates.max() - rates.min()
        assertTrue("per-install refine rates should vary (spread=$spread)", spread > 0.10)
    }

    @Test
    fun `a commercial persona refines more often than an informational one`() {
        val seed = 5L
        val commercial = refineRate(gen(seed, persona = persona(CategoryPool.FINANCE, CategoryPool.AUTOMOTIVE, CategoryPool.REAL_ESTATE)), n = 3000)
        val informational = refineRate(gen(seed, persona = persona(CategoryPool.SCIENCE, CategoryPool.HISTORY, CategoryPool.ACADEMIC)), n = 3000)
        assertTrue("commercial=$commercial should exceed informational=$informational", commercial > informational)
    }

    /** EN SearchRefinements template words, recovered by refining a unique sentinel head. */
    private val enTemplateVocab: Set<String> by lazy {
        val sentinel = "qqzzsentinel"
        SearchRefinements.refine(sentinel, SupportedLocale.EN, count = 64, Random(0))
            .flatMap { it.split(' ') }
            .filter { it.isNotBlank() && it != sentinel }
            .toSet()
    }

    @Test
    fun `output is well-formed - trimmed, single-spaced, no leaked placeholder`() {
        val g = gen(3L)
        repeat(1500) {
            val out = g.generate(CategoryPool.AUTOMOTIVE)
            assertFalse("leaked format placeholder: '$out'", out.contains("%"))
            assertEquals("not trimmed: '$out'", out.trim(), out)
            assertFalse("double space: '$out'", out.contains("  "))
            assertTrue("implausible word count: '$out'", out.split(' ').filter { it.isNotBlank() }.size in 1..10)
        }
    }

    @Test
    fun `output uses only corpus and template vocabulary - no gibberish`() {
        // Corpus-anchored heads + curated templates means every token MUST come from one of those
        // two sources; this is the hard anti-gibberish guarantee a neural model could not give.
        val corpus = listOf(
            "trail running shoes", "waterproof hiking boots", "budget wireless headphones",
            "italian pasta recipe", "home espresso machine",
        )
        val corpusTokens = corpus.flatMap { it.split(' ') }.toSet()
        every { queryBankManager.randomQuery(any()) } answers { corpus.random() }
        every { markov.generate(any(), any()) } answers { corpus.random() }
        val permitted = corpusTokens + enTemplateVocab
        val g = gen(4L)
        repeat(1500) {
            val out = g.generate(CategoryPool.AUTOMOTIVE)
            out.split(' ').filter { it.isNotBlank() }.forEach { word ->
                assertTrue("out-of-vocabulary token '$word' in '$out'", word in permitted)
            }
        }
    }

    @Test
    fun `delegates the custom-interest seed surface to the Markov fallback`() {
        val g = gen(1L)
        g.injectSeedPhrases(CategoryPool.GAMING, listOf("indie roguelikes"))
        g.clearSeedPhrases()
        g.clearAllState()
        verify { markov.injectSeedPhrases(CategoryPool.GAMING, listOf("indie roguelikes")) }
        verify { markov.clearSeedPhrases() }
        verify { markov.clearAllState() }
    }
}
