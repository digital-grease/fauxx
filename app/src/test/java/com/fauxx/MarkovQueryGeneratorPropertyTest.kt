package com.fauxx

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.data.querybank.MarkovQueryGenerator
import com.fauxx.data.querybank.QueryBankManager
import com.fauxx.data.querybank.QueryBlocklist
import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
import com.fauxx.support.seededRandom
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Safety property for [MarkovQueryGenerator]: across many seeded RNG states, `generate()` must
 * NEVER return a query the blocklist rejects — even when the corpus itself can produce the
 * blocked phrase (verbatim or by bigram composition). The production guarantee is the
 * resample-up-to-5 + safe-fallback path; this fuzzes it deterministically with a fixed seed
 * per case (so a failure is reproducible from the seed).
 */
class MarkovQueryGeneratorPropertyTest {

    /** Blocked phrase the corpus can emit; the generator must never let it through. */
    private fun isHarmful(q: String): Boolean = q.lowercase().contains("hang yourself")

    /**
     * Individually-plausible queries whose words also chain into the harmful phrase (one even
     * contains it verbatim) — the adversarial corpus the resample guard must survive.
     */
    private val adversarialCorpus = listOf(
        "how to hang a picture frame",
        "hang yourself a hammock in the yard",
        "teach yourself guitar basics",
        "best ways to hang string lights",
        "calm yourself with breathing exercises",
        "how to cook pasta properly",
    )

    private fun generator(seed: Long): MarkovQueryGenerator {
        val bank: QueryBankManager = mockk {
            every { getQueries(any()) } returns adversarialCorpus
            // Safe fallback the generator uses when every resample is blocked.
            every { randomQuery(any()) } returns "easy weeknight dinner ideas"
        }
        val blocklist: QueryBlocklist = mockk {
            every { isBlocked(any()) } answers { isHarmful(firstArg()) }
        }
        val locale: LocaleManager = mockk(relaxed = true) {
            every { currentLocale } returns SupportedLocale.EN
            every { currentLocaleFlow } returns MutableStateFlow(SupportedLocale.EN)
        }
        return MarkovQueryGenerator(bank, blocklist, locale, seededRandom(seed))
    }

    @Test
    fun `generate never emits a blocklisted query across seeds`() = runBlocking<Unit> {
        checkAll(300, Arb.long()) { seed ->
            val gen = generator(seed)
            repeat(50) {
                val output = gen.generate(CategoryPool.COOKING)
                assertFalse(
                    "generate() emitted a blocklisted query \"$output\" (seed=$seed)",
                    isHarmful(output),
                )
            }
        }
    }
}
