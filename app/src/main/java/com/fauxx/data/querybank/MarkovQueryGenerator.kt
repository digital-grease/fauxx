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
 */
@Singleton
class MarkovQueryGenerator @Inject constructor(
    private val queryBankManager: QueryBankManager
) {
    /** bigram[word] = list of words that follow [word] in the training corpus. */
    private val bigramMap = mutableMapOf<String, MutableList<String>>()
    private var trained = false

    /**
     * Generate a compound search query for [category] by:
     * 1. Selecting a seed query from the category bank
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

        // Pick a seed: a random word from a random query in this category
        val seedQuery = queries.random()
        val seedWords = seedQuery.split(" ").filter { it.isNotBlank() }
        if (seedWords.isEmpty()) return seedQuery

        val result = mutableListOf(seedWords.random())
        repeat(targetLength - 1) {
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
}
