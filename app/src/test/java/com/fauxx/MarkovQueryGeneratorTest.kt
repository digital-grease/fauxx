package com.fauxx

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.data.querybank.MarkovQueryGenerator
import com.fauxx.data.querybank.QueryBankManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkovQueryGeneratorTest {

    private val queryBankManager: QueryBankManager = mockk()
    private val generator = MarkovQueryGenerator(queryBankManager)

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
}
