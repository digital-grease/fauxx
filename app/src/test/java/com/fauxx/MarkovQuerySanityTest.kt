package com.fauxx

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.data.querybank.MarkovQueryGenerator
import com.fauxx.data.querybank.QueryBankManager
import com.fauxx.data.querybank.QueryBlocklist
import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * End-to-end sanity check for the [MarkovQueryGenerator] against each shipped locale's
 * real bundled query banks + harmful_queries blocklist. Complements
 * [QueryBankCorpusAuditTest] (which only checks the *source corpus*) by exercising the
 * bigram chaining — Markov composition can assemble harmful phrases from individually
 * safe seeds (the canonical "how to" + "hang" + "yourself" failure), and only a
 * generative test surfaces that risk.
 *
 * Per (locale, category):
 *   - load real banks from `src/main/assets/query_banks/<tag>/<cat>.json`
 *   - load real blocklist from `src/main/assets/harmful_queries/<tag>.json` (or the
 *     legacy `harmful_queries.json` for EN)
 *   - exercise the real `MarkovQueryGenerator` with these as injected dependencies
 *   - generate [GENERATIONS_PER_CATEGORY] queries
 *   - assert all outputs pass the blocklist (production guarantees this — assertion is
 *     belt-and-braces for the resample fallback path)
 *   - assert degeneracy rate (`< MIN_PLAUSIBLE_WORDS` words) stays under
 *     [MAX_DEGENERATE_RATE]; signals corpus brittleness when high
 *
 * Locale switch model: a fresh [MarkovQueryGenerator] is built per locale so the bigram
 * model isn't contaminated across runs. The watcher coroutine the generator starts on
 * `currentLocaleFlow` is harmlessly idle in the test (no emissions after `drop(1)`).
 */
class MarkovQuerySanityTest {

    private val assetsRoot = File("src/main/assets")
    private val gson = Gson()
    private val stringListType = object : TypeToken<List<String>>() {}.type

    private data class HarmfulShape(
        @SerializedName("class_a_terms") val classATerms: List<String> = emptyList(),
        @SerializedName("self_signal_terms") val selfSignalTerms: List<String> = emptyList(),
        @SerializedName("regex_patterns") val regexPatterns: List<String> = emptyList()
    )

    private data class LocaleTarget(
        val locale: SupportedLocale,
        val harmfulPath: String,
        val banksDir: String
    )

    private val targets = listOf(
        LocaleTarget(SupportedLocale.EN, "harmful_queries.json", "query_banks"),
        LocaleTarget(SupportedLocale.ES, "harmful_queries/es.json", "query_banks/es"),
        LocaleTarget(SupportedLocale.FR, "harmful_queries/fr.json", "query_banks/fr")
    )

    @Test
    fun `every locale's Markov generator produces safe, non-degenerate output`() {
        val violations = mutableListOf<String>()
        var localesExercised = 0

        for (target in targets) {
            val harmfulFile = File(assetsRoot, target.harmfulPath)
            val banksDir = File(assetsRoot, target.banksDir)
            // A locale that hasn't shipped its banks yet skips the sanity check —
            // HarmfulQueriesLocaleAuditTest is what enforces the shipping safety gate.
            if (!harmfulFile.exists() || !banksDir.isDirectory) continue
            localesExercised++

            val blocker = buildBlocker(harmfulFile.readText())
            val bankFiles = banksDir.listFiles { f -> f.extension == "json" }
                ?.sortedBy { it.name }
                ?: continue

            for (bankFile in bankFiles) {
                val categoryName = bankFile.nameWithoutExtension.uppercase()
                val category = runCatching { CategoryPool.valueOf(categoryName) }.getOrNull()
                    ?: continue
                val queries: List<String> = gson.fromJson(bankFile.readText(), stringListType)
                if (queries.isEmpty()) continue

                val generator = buildGenerator(target.locale, queries, blocker)

                var degenerateCount = 0
                var blockedFinalCount = 0
                repeat(GENERATIONS_PER_CATEGORY) {
                    val output = generator.generate(category)
                    if (blocker(output)) {
                        // Should never happen: production code resamples up to 5 times
                        // and then falls back to the COOKING safe-fallback. Capture the
                        // exact failing output so a maintainer can triage.
                        blockedFinalCount++
                        if (blockedFinalCount <= 3) {
                            violations += "[${target.locale.tag}/${bankFile.name}] " +
                                "blocked output reached caller: \"$output\""
                        }
                    }
                    val wordCount = output.split(" ").filter { it.isNotBlank() }.size
                    if (wordCount < MIN_PLAUSIBLE_WORDS) degenerateCount++
                }

                val degenerateRate = degenerateCount.toDouble() / GENERATIONS_PER_CATEGORY
                if (degenerateRate > MAX_DEGENERATE_RATE) {
                    violations += "[${target.locale.tag}/${bankFile.name}] degenerate " +
                        "rate ${"%.3f".format(degenerateRate)} > $MAX_DEGENERATE_RATE " +
                        "($degenerateCount/$GENERATIONS_PER_CATEGORY outputs under " +
                        "$MIN_PLAUSIBLE_WORDS words)"
                }
            }
        }

        assertTrue(
            "No locales exercised. Expected at least the EN bank at " +
                "src/main/assets/query_banks/. cwd=${File(".").absolutePath}",
            localesExercised > 0
        )
        assertEquals(
            buildString {
                append("Markov generator produced unsafe or degenerate output. Either ")
                append("the corpus is brittle (rewrite the affected category's queries ")
                append("to use longer, more chainable phrases) or the production safety ")
                append("net regressed (check MAX_RESAMPLE_ATTEMPTS / safeFallback).\n")
                violations.forEach { appendLine("  $it") }
            },
            0,
            violations.size
        )
    }

    /**
     * Build a fresh [MarkovQueryGenerator] backed by mocked DI dependencies that surface
     * the real per-locale corpus + blocklist. The generator's locale-change watcher
     * coroutine starts but never fires (no emissions after the dropped initial value),
     * so the bigram model stays trained on the locale we provided.
     */
    private fun buildGenerator(
        locale: SupportedLocale,
        queries: List<String>,
        blocker: (String) -> Boolean
    ): MarkovQueryGenerator {
        val bankManager: QueryBankManager = mockk {
            every { getQueries(any()) } returns queries
            // Used only by the safe-fallback path (when resample runs out). In test, the
            // production code falls back to COOKING — feed the current locale's COOKING
            // bank if available, else recycle the current category's queries.
            every { randomQuery(any()) } returns queries.random()
        }
        val blocklist: QueryBlocklist = mockk {
            // Closure-capture the standalone blocker; mockk's `isBlocked` inside this
            // DSL refers to the mock method we're stubbing, so we have to use a
            // distinct parameter name (`blocker`) to break the shadow.
            every { isBlocked(any()) } answers { blocker(firstArg()) }
        }
        val localeManager: LocaleManager = mockk(relaxed = true) {
            every { currentLocale } returns locale
            every { currentLocaleFlow } returns MutableStateFlow(locale)
        }
        return MarkovQueryGenerator(bankManager, blocklist, localeManager)
    }

    /**
     * Standalone blocker mirroring [QueryBlocklist.isBlocked] semantics. Reused (with
     * trivial naming changes) from [QueryBankCorpusAuditTest] — kept inline rather than
     * extracted so each sanity-check test stays self-contained and a refactor in one
     * doesn't silently change the matching used by the other.
     */
    private fun buildBlocker(harmfulJson: String): (String) -> Boolean {
        val parsed = gson.fromJson(harmfulJson, HarmfulShape::class.java)
        val terms = (parsed.classATerms + parsed.selfSignalTerms)
            .map { it.lowercase().trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val regexes = parsed.regexPatterns.mapNotNull {
            runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull()
        }
        return { query ->
            val n = query.lowercase()
            terms.any { n.contains(it) } || regexes.any { it.containsMatchIn(n) }
        }
    }

    companion object {
        /**
         * Per-category generation count. 1000 is plenty for the corpus sizes we ship
         * (~50–200 queries per bank) — covers seed variety and most bigram paths
         * without making the test minute-long.
         */
        private const val GENERATIONS_PER_CATEGORY = 1000

        /**
         * Mirrors [MarkovQueryGenerator.MIN_PLAUSIBLE_WORDS] — outputs below this are
         * the production degeneracy fallback (seed phrase returned verbatim). The
         * generator already protects against single-word output; we still want
         * fluency-grade output most of the time, hence the rate-based threshold below
         * rather than zero tolerance.
         */
        private const val MIN_PLAUSIBLE_WORDS = 3

        /**
         * 5% degeneracy tolerance per bank. A bank where >5% of generations fall back
         * to the verbatim seed indicates sparse vocabulary — likely 1-or-2-word seed
         * phrases dominating the corpus. The translated banks tend to have shorter
         * queries on average than EN, so this isn't a 0%-target; 5% is a corpus-health
         * canary, not a strict guarantee.
         */
        private const val MAX_DEGENERATE_RATE = 0.05
    }
}
