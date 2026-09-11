package com.fauxx.data.querybank

import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.random.Random

/**
 * Regression guard for the half-fix found reviewing issue #256.
 *
 * [QueryBankManager] evicts its cache when the calendar year rolls, so `getQueries` starts
 * returning "... 2027". But [MarkovQueryGenerator] trains its bigram model once per category
 * per process and is a `@Singleton`, so on a phone left powered from Dec 31 into Jan 1 the
 * model kept chaining `phones -> 2026` and generated queries carried last year's value anyway
 * — exactly the staleness the eviction was added to prevent.
 *
 * The generator now tracks [QueryBankManager.corpusGeneration] and retrains when it moves.
 */
class MarkovCorpusStalenessTest {

    /**
     * Corpora chosen so a STALE bigram is observable. The new corpus ends on a word the old
     * corpus had an outgoing bigram for, so an un-retrained model extends the seed with the
     * old year, while a retrained one has nothing to chain to and stops.
     */
    private val oldCorpus = listOf("phones 2026")
    private val newCorpus = listOf("bravo phones")

    private fun build(
        queries: () -> List<String>,
        generation: () -> Int,
    ): MarkovQueryGenerator {
        val bank: QueryBankManager = mockk {
            every { getQueries(any()) } answers { queries() }
            every { randomQuery(any()) } answers { queries().first() }
            every { corpusGeneration } answers { generation() }
        }
        val blocklist: QueryBlocklist = mockk { every { isBlocked(any()) } returns false }
        val locale: LocaleManager = mockk(relaxed = true) {
            every { currentLocale } returns SupportedLocale.EN
            every { currentLocaleFlow } returns MutableStateFlow(SupportedLocale.EN)
        }
        return MarkovQueryGenerator(bank, blocklist, locale, Random(1234))
    }

    @Test
    fun `a year roll does not leave last year's bigrams in the model`() {
        var corpus = oldCorpus
        var generation = 0
        val generator = build({ corpus }, { generation })

        // Train against the pre-midnight corpus.
        val before = generator.generate(CategoryPool.TECHNOLOGY, targetLength = 4)
        assertEquals("phones 2026", before)

        // The year rolls: QueryBankManager re-resolves $YEAR$ and bumps its generation.
        corpus = newCorpus
        generation = 1

        repeat(30) {
            val out = generator.generate(CategoryPool.TECHNOLOGY, targetLength = 4)
            assertFalse(
                "generated \"$out\" from a bigram model still trained on last year's corpus",
                out.contains("2026")
            )
        }
    }

    @Test
    fun `a stable generation does not retrain on every call`() {
        // The counterpart risk: invalidating unconditionally would rebuild the bigram model
        // on every single action. With the generation held steady the model must persist,
        // which is observable because the ORIGINAL corpus's bigram still resolves.
        var corpus = oldCorpus
        val generator = build({ corpus }, { 0 })

        assertEquals("phones 2026", generator.generate(CategoryPool.TECHNOLOGY, targetLength = 4))

        // Swap the corpus WITHOUT bumping the generation: the model is entitled to keep its
        // existing bigrams, so the old chain is still reachable from the new seed.
        corpus = newCorpus
        val out = generator.generate(CategoryPool.TECHNOLOGY, targetLength = 4)
        assertEquals("bravo phones 2026", out)
    }

    @Test
    fun `clearAllState forces a retrain even at the same generation`() {
        // The data wipe must not leave the generator believing it is already in sync.
        var corpus = oldCorpus
        val generator = build({ corpus }, { 0 })
        generator.generate(CategoryPool.TECHNOLOGY, targetLength = 4)

        generator.clearAllState()
        corpus = newCorpus

        val out = generator.generate(CategoryPool.TECHNOLOGY, targetLength = 4)
        assertFalse("wiped state still produced an old-corpus bigram: \"$out\"", out.contains("2026"))
    }
}
