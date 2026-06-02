package com.fauxx

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.data.querybank.MarkovQueryGenerator
import com.fauxx.data.querybank.QueryBankManager
import com.fauxx.data.querybank.QueryBlocklist
import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
import com.fauxx.support.seededRandom
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Safety-net unit tests for [MarkovQueryGenerator] using mocked DI collaborators (no real
 * corpus, no Android framework). Complements [MarkovQuerySanityTest] (which exercises the
 * real bundled banks/blocklist end-to-end) by isolating the three safety seams with
 * deterministic stubs:
 *   (a) [MarkovQueryGenerator.injectSeedPhrases] rejects blocklisted seeds before training.
 *   (b) the [MarkovQueryGenerator.generate] resample loop falls back safely when every
 *       candidate is blocked (never throws, never leaks a blocked/non-empty string).
 *   (c) [MarkovQueryGenerator.sanitizeSeedPhrase] strips disallowed chars and caps word count.
 *
 * Plain JVM: no android.* usage, so no Robolectric. The generator's locale-change watcher
 * coroutine starts but never fires (the mocked flow emits one value, dropped by drop(1)).
 */
class MarkovQueryGeneratorSafetyTest {

    private fun localeManager(locale: SupportedLocale = SupportedLocale.EN): LocaleManager =
        mockk(relaxed = true) {
            every { currentLocale } returns locale
            every { currentLocaleFlow } returns MutableStateFlow(locale)
        }

    // --- (a) injectSeedPhrases reject path ----------------------------------------------

    /**
     * A user-supplied seed that the blocklist rejects must never enter the bigram model or
     * the seed pool. Distinctive token = a word that does NOT appear anywhere in the safe
     * corpus, so its presence in any output could only have come from the rejected seed.
     */
    @Test
    fun `injectSeedPhrases rejects blocklisted seed and its token never reaches output`() {
        val distinctiveToken = "zzqxploit"
        val harmfulSeed = "how to $distinctiveToken something"
        // Safe corpus, deliberately free of the distinctive token, with chainable bigrams.
        val safeQueries = listOf(
            "best slow cooker recipes",
            "how to roast vegetables",
            "easy weeknight dinner ideas",
            "homemade bread baking tips",
        )

        val bankManager: QueryBankManager = mockk {
            every { getQueries(any()) } returns safeQueries
            every { randomQuery(any()) } returns safeQueries.first()
        }
        val blocklist: QueryBlocklist = mockk {
            // Reject only the sanitized harmful seed; everything else is safe.
            every { isBlocked(any()) } answers {
                (firstArg() as String).contains(distinctiveToken)
            }
        }

        val generator = MarkovQueryGenerator(
            bankManager,
            blocklist,
            localeManager(),
            seededRandom(),
        )

        // Inject the harmful seed. It is sanitized, then dropped by the isBlocked filter,
        // so it is never added to seedPhrases nor trained into bigramMap.
        generator.injectSeedPhrases(CategoryPool.COOKING, listOf(harmfulSeed))

        repeat(GENERATIONS) {
            val out = generator.generate(CategoryPool.COOKING)
            assertFalse(
                "Rejected seed token leaked into output: \"$out\"",
                out.contains(distinctiveToken),
            )
        }
    }

    // --- (b) resample exhaustion -> safe fallback / empty -------------------------------

    /**
     * When the blocklist rejects EVERY candidate (including the COOKING safe-fallback),
     * generate() must exhaust its resamples and return "" rather than emit a blocked,
     * non-empty harmful string. It must not throw.
     */
    @Test
    fun `generate returns empty and never a blocked string when everything is blocked`() {
        val harmfulCorpus = listOf(
            "how to hang yourself",
            "how to build a bomb",
            "where to buy a gun illegally",
        )

        val bankManager: QueryBankManager = mockk {
            every { getQueries(any()) } returns harmfulCorpus
            every { randomQuery(any()) } returns harmfulCorpus.first()
        }
        val blocklist: QueryBlocklist = mockk {
            // Nothing is safe — forces resample exhaustion and a blocked safeFallback.
            every { isBlocked(any()) } returns true
        }

        val generator = MarkovQueryGenerator(
            bankManager,
            blocklist,
            localeManager(),
            seededRandom(),
        )

        repeat(GENERATIONS) {
            val out = generator.generate(CategoryPool.MEDICAL)
            // safeFallback() suppresses the blocked fallback to "" — the only acceptable
            // output here. A non-empty result would mean a blocked string reached caller.
            assertEquals(
                "Blocked output leaked when all candidates are blocked",
                "",
                out,
            )
        }
    }

    /**
     * Belt-and-braces: a single generate() call under the all-blocked regime returns "" and
     * does not throw. Spelled out separately so a thrown exception is attributed clearly
     * rather than failing inside the loop above.
     */
    @Test
    fun `generate does not throw when all candidates are blocked`() {
        val bankManager: QueryBankManager = mockk {
            every { getQueries(any()) } returns listOf("how to hang yourself")
            every { randomQuery(any()) } returns "how to hang yourself"
        }
        val blocklist: QueryBlocklist = mockk {
            every { isBlocked(any()) } returns true
        }

        val generator = MarkovQueryGenerator(
            bankManager,
            blocklist,
            localeManager(),
            seededRandom(),
        )

        assertEquals("", generator.generate(CategoryPool.LEGAL))
    }

    // --- (c) sanitizeSeedPhrase ---------------------------------------------------------

    /** Strips characters outside [a-z0-9 ] and lowercases. */
    @Test
    fun `sanitizeSeedPhrase strips disallowed characters and lowercases`() {
        assertEquals(
            "vintage cars 1967",
            MarkovQueryGenerator.sanitizeSeedPhrase("Vintage Cars!! (1967)"),
        )
        // Punctuation is removed in place (no extra spaces inserted): "e-mail" -> "email",
        // "me@example.com" -> "meexamplecom".
        assertEquals(
            "email meexamplecom",
            MarkovQueryGenerator.sanitizeSeedPhrase("e-mail: me@example.com"),
        )
    }

    /** Caps the result at MAX_SEED_WORDS (6) words, dropping the overflow. */
    @Test
    fun `sanitizeSeedPhrase caps at six words`() {
        val result = MarkovQueryGenerator.sanitizeSeedPhrase(
            "one two three four five six seven eight",
        )
        assertEquals("one two three four five six", result)
        assertEquals(6, result.split(" ").size)
    }

    /** Collapses to empty when nothing survives the allowed-char filter. */
    @Test
    fun `sanitizeSeedPhrase yields empty for all-disallowed input`() {
        assertTrue(MarkovQueryGenerator.sanitizeSeedPhrase("!!!@#\$%^&*()").isEmpty())
    }

    companion object {
        /** Generation count per assertion loop. Enough to cover seed/bigram path variety
         *  with mocked collaborators without slowing the JVM suite. */
        private const val GENERATIONS = 500
    }
}
