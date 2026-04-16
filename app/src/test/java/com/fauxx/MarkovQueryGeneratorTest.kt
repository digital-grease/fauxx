package com.fauxx

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.data.querybank.MarkovQueryGenerator
import com.fauxx.data.querybank.QueryBankManager
import com.fauxx.data.querybank.QueryBlocklist
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkovQueryGeneratorTest {

    private val queryBankManager: QueryBankManager = mockk()
    private val queryBlocklist: QueryBlocklist = mockk<QueryBlocklist>().also {
        every { it.isBlocked(any()) } returns false
    }
    private val generator = MarkovQueryGenerator(queryBankManager, queryBlocklist)

    @Test
    fun `generate returns non-empty string`() {
        every { queryBankManager.getQueries(any()) } returns listOf(
            "how to invest money wisely",
            "best savings account rates",
            "retirement fund strategies 2024"
        )
        every { queryBankManager.randomQuery(any()) } returns "invest money wisely"

        val result = generator.generate(CategoryPool.FINANCE)
        assertFalse("Generated query should not be empty", result.isBlank())
    }

    @Test
    fun `generate falls back when bank is empty`() {
        every { queryBankManager.getQueries(any()) } returns emptyList()
        every { queryBankManager.randomQuery(CategoryPool.GAMING) } returns "gaming info"

        val result = generator.generate(CategoryPool.GAMING)
        assertFalse("Fallback should return non-empty string", result.isBlank())
    }

    @Test
    fun `train builds bigram model`() {
        val queries = listOf(
            "how to cook pasta",
            "best pasta sauce recipes",
            "how to make fresh pasta"
        )
        generator.train(queries)
        // After training, generate should produce output without crashing
        every { queryBankManager.getQueries(any()) } returns queries
        every { queryBankManager.randomQuery(any()) } returns "how to cook"
        val result = generator.generate(CategoryPool.COOKING)
        assertFalse(result.isBlank())
    }

    @Test
    fun `generated query length within target range`() {
        every { queryBankManager.getQueries(any()) } returns listOf(
            "best retirement savings strategies for seniors",
            "how to maximize 401k contributions early retirement",
            "social security benefits calculation retirement income"
        )
        every { queryBankManager.randomQuery(any()) } returns "retirement savings"

        repeat(20) {
            val result = generator.generate(CategoryPool.RETIREMENT)
            val wordCount = result.trim().split(" ").size
            assertTrue("Query should have at least 1 word", wordCount >= 1)
            assertTrue("Query should not be excessively long", wordCount <= 15)
        }
    }

    /**
     * Regression: generate() previously picked ONE random seed word and aborted the
     * Markov walk on the first missing bigram, producing 1-word queries like "dry".
     * With full-phrase seeding + degeneracy fallback, output is never shorter than
     * the seed phrase from the corpus (which is always >= 3 words in our banks).
     */
    @Test
    fun `generate never produces single-word output when bank has multi-word queries`() {
        val bank = listOf(
            "dry mouth remedies",
            "chronic cough treatment options",
            "migraine relief home remedies"
        )
        every { queryBankManager.getQueries(any()) } returns bank
        every { queryBankManager.randomQuery(any()) } returns bank.first()

        repeat(200) {
            val result = generator.generate(CategoryPool.MEDICAL)
            val wordCount = result.trim().split(" ").filter { it.isNotBlank() }.size
            assertTrue(
                "Expected >= 3 words, got ${wordCount}: '$result'",
                wordCount >= 3
            )
        }
    }

    /**
     * Regression: the previous one-shot `trained` flag meant only the first-requested
     * category's bigrams were learned. Asking for a second category then starved the
     * Markov walk and emitted single seed words. With per-category incremental training,
     * each category's vocabulary is learned on first use.
     */
    @Test
    fun `generate trains incrementally per category`() {
        val finance = listOf("how to invest retirement savings wisely")
        val medical = listOf("symptoms of high blood pressure")
        every { queryBankManager.getQueries(CategoryPool.FINANCE) } returns finance
        every { queryBankManager.getQueries(CategoryPool.MEDICAL) } returns medical
        every { queryBankManager.randomQuery(any()) } returns "fallback"

        // Prime with FINANCE (trains the bigram map on finance vocabulary only)
        generator.generate(CategoryPool.FINANCE)

        // Now ask for MEDICAL — should still produce plausible output, not degenerate
        repeat(50) {
            val result = generator.generate(CategoryPool.MEDICAL)
            val wordCount = result.trim().split(" ").filter { it.isNotBlank() }.size
            assertTrue(
                "MEDICAL output starved after FINANCE trained first: '$result'",
                wordCount >= 3
            )
        }
    }
}
