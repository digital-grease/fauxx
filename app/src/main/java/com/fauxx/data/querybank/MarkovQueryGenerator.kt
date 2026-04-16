package com.fauxx.data.querybank

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Generates natural-sounding compound search queries using a bigram (2-gram) Markov model
 * trained on the bundled query corpus.
 *
 * The output looks like plausible human search queries rather than simple random combinations,
 * making synthetic activity harder to distinguish from organic search behavior.
 *
 * Supports optional seed phrases injected from custom user interests. These are trained
 * into the bigram model and used as additional seed candidates for the mapped category,
 * producing queries that incorporate the user's interest terminology (to better suppress it).
 *
 * **Safety**: every generated query is checked through [QueryBlocklist] BEFORE being
 * returned. Bigram chaining can assemble phrases from individually safe source queries
 * ("how to" + "hang" + "yourself") that are dangerous as dispatched activity. On a
 * blocked result, generation is resampled up to [MAX_RESAMPLE_ATTEMPTS] times; if every
 * attempt produces a blocked output, a safe-category fallback query is returned instead.
 */
@Singleton
class MarkovQueryGenerator @Inject constructor(
    private val queryBankManager: QueryBankManager,
    private val queryBlocklist: QueryBlocklist
) {
    /** bigram[word] = list of words that follow [word] in the training corpus. */
    private val bigramMap = mutableMapOf<String, MutableList<String>>()

    /** Categories whose banks have been folded into [bigramMap]. */
    private val trainedCategories = mutableSetOf<CategoryPool>()

    /** Extra seed phrases per category, injected from custom user interests. */
    private val seedPhrases = mutableMapOf<CategoryPool, MutableList<String>>()

    /**
     * Generate a compound search query for [category]. Guaranteed never to return a
     * query that matches [QueryBlocklist] — if Markov chaining produces a blocked
     * output, generation is resampled up to [MAX_RESAMPLE_ATTEMPTS] times and then
     * falls back to a safe-category seed.
     *
     * @param category The target content category.
     * @param targetLength Target number of words in the output (3-8 words).
     */
    fun generate(category: CategoryPool, targetLength: Int = Random.nextInt(3, 9)): String {
        repeat(MAX_RESAMPLE_ATTEMPTS) { attempt ->
            val candidate = generateOnce(category, targetLength)
            if (candidate.isNotEmpty() && !queryBlocklist.isBlocked(candidate)) {
                return candidate
            }
            if (attempt == MAX_RESAMPLE_ATTEMPTS - 1) {
                Timber.w(
                    "Markov generator exhausted $MAX_RESAMPLE_ATTEMPTS attempts for " +
                        "category=$category without producing a safe output — using fallback"
                )
            }
        }
        return safeFallback()
    }

    /** One generation pass. May return a blocked output; caller must check. */
    private fun generateOnce(category: CategoryPool, targetLength: Int): String {
        val queries = queryBankManager.getQueries(category)
        if (queries.isEmpty()) return queryBankManager.randomQuery(category)

        // Train incrementally per category. Earlier implementation trained one-shot on
        // whichever category was requested first, leaving the bigram model starved of
        // vocabulary for every other category — which produced seed words with no
        // outgoing bigrams and caused single-word queries like "dry".
        if (trainedCategories.add(category)) {
            train(queries)
        }

        // Build seed pool: category queries + any injected seed phrases for this category
        val extraSeeds = seedPhrases[category].orEmpty()
        val seedPool = if (extraSeeds.isNotEmpty() && Random.nextFloat() < SEED_PHRASE_PROBABILITY) {
            extraSeeds
        } else {
            queries
        }

        val seedQuery = seedPool.random()
        val seedWords = seedQuery.split(" ").filter { it.isNotBlank() }
        if (seedWords.isEmpty()) return seedQuery

        // Seed with the FULL phrase. Previously picked one random word, which threw away
        // the phrase's structure and frequently produced 1-word outputs when that word
        // had no outgoing bigram.
        val result = seedWords.toMutableList()
        while (result.size < targetLength) {
            val lastWord = result.last().lowercase()
            val next = bigramMap[lastWord]?.randomOrNull() ?: break
            result.add(next)
        }

        // Degeneracy fallback: if we somehow ended up shorter than a plausible query
        // (e.g. seed was a single word with no bigrams), return the original seed
        // phrase verbatim — it's always a natural-sounding line from the corpus.
        if (result.size < MIN_PLAUSIBLE_WORDS) return seedQuery

        return result.joinToString(" ").trim()
    }

    /**
     * Safe fallback when resample limit is exhausted. Pulls a random query from a
     * low-risk category (COOKING) whose bank is already post-filtered by
     * [QueryBankManager]. Checked once more through the blocklist as belt-and-braces;
     * empty string on failure will be suppressed by [com.fauxx.engine.modules.SearchPoisonModule].
     */
    private fun safeFallback(): String {
        val fallback = queryBankManager.randomQuery(CategoryPool.COOKING)
        return if (fallback.isNotEmpty() && !queryBlocklist.isBlocked(fallback)) fallback else ""
    }

    /**
     * Train the bigram model on a list of [queries].
     */
    fun train(queries: List<String>) {
        for (query in queries) {
            val words = query.split(" ").filter { it.isNotBlank() }
            for (i in 0 until words.size - 1) {
                val word = words[i].lowercase()
                val next = words[i + 1]
                bigramMap.getOrPut(word) { mutableListOf() }.add(next)
            }
        }
    }

    /**
     * Inject seed phrases for a category from custom user interests.
     * Phrases are sanitized (trimmed, lowercased, non-empty words only), checked
     * against [QueryBlocklist], and then trained into the bigram model so their
     * vocabulary becomes available for Markov extension.
     *
     * PRIVACY: Only call with sanitized interest strings — no raw PII, email addresses,
     * phone numbers, or other identifying information. The caller is responsible for
     * scrubbing before injection. User-supplied phrases that match the harmful-query
     * guard are silently rejected (with a `Timber.w` log).
     */
    fun injectSeedPhrases(category: CategoryPool, phrases: List<String>) {
        val sanitized = phrases
            .map { sanitizeSeedPhrase(it) }
            .filter { it.isNotBlank() }
            .filter { phrase ->
                val blocked = queryBlocklist.isBlocked(phrase)
                if (blocked) {
                    Timber.w("Rejected user-supplied interest seed: matches harmful-query guard")
                }
                !blocked
            }
        if (sanitized.isEmpty()) return

        seedPhrases.getOrPut(category) { mutableListOf() }.addAll(sanitized)
        // Train the new phrases into the bigram model
        train(sanitized)
    }

    /** Clear all injected seed phrases (e.g., when user clears profile). */
    fun clearSeedPhrases() {
        seedPhrases.clear()
    }

    companion object {
        /** Probability of using an injected seed phrase vs. a corpus query. */
        private const val SEED_PHRASE_PROBABILITY = 0.3f

        /** Max words allowed in a seed phrase to prevent abuse. */
        private const val MAX_SEED_WORDS = 6

        /** Minimum words a generated query must have to be emitted; below this we fall
         *  back to the raw seed phrase from the corpus. */
        private const val MIN_PLAUSIBLE_WORDS = 3

        /** Maximum number of resample attempts when a generated query is blocked by
         *  the harmful-query guard. */
        private const val MAX_RESAMPLE_ATTEMPTS = 5

        /**
         * Sanitize a seed phrase: lowercase, strip non-alphanumeric (keep spaces),
         * cap word count, trim.
         */
        internal fun sanitizeSeedPhrase(phrase: String): String =
            phrase.lowercase()
                .replace(Regex("[^a-z0-9 ]"), "")
                .split(" ")
                .filter { it.isNotBlank() }
                .take(MAX_SEED_WORDS)
                .joinToString(" ")
    }
}
