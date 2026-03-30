package com.fauxx.data.querybank

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
 */
@Singleton
class MarkovQueryGenerator @Inject constructor(
    private val queryBankManager: QueryBankManager
) {
    /** bigram[word] = list of words that follow [word] in the training corpus. */
    private val bigramMap = mutableMapOf<String, MutableList<String>>()
    private var trained = false

    /** Extra seed phrases per category, injected from custom user interests. */
    private val seedPhrases = mutableMapOf<CategoryPool, MutableList<String>>()

    /**
     * Generate a compound search query for [category] by:
     * 1. Selecting a seed query from the category bank (or injected seed phrases)
     * 2. Using the Markov model to extend it with contextually plausible words
     *
     * @param category The target content category.
     * @param targetLength Target number of words in the output (3-8 words).
     */
    fun generate(category: CategoryPool, targetLength: Int = Random.nextInt(3, 9)): String {
        val queries = queryBankManager.getQueries(category)
        if (queries.isEmpty()) return queryBankManager.randomQuery(category)

        // Train on first call (lazy, across all categories loaded so far)
        if (!trained) {
            train(queries)
            trained = true
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

        val result = mutableListOf(seedWords.random())
        for (i in 1 until targetLength) {
            val lastWord = result.last().lowercase()
            val next = bigramMap[lastWord]?.randomOrNull() ?: break
            result.add(next)
        }

        return result.joinToString(" ").trim()
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
        trained = bigramMap.isNotEmpty()
    }

    /**
     * Inject seed phrases for a category from custom user interests.
     * Phrases are sanitized (trimmed, lowercased, non-empty words only) and trained
     * into the bigram model so their vocabulary becomes available for Markov extension.
     *
     * PRIVACY: Only call with sanitized interest strings — no raw PII, email addresses,
     * phone numbers, or other identifying information. The caller is responsible for
     * scrubbing before injection.
     */
    fun injectSeedPhrases(category: CategoryPool, phrases: List<String>) {
        val sanitized = phrases
            .map { sanitizeSeedPhrase(it) }
            .filter { it.isNotBlank() }
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
