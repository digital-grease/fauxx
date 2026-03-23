package com.fauxx

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.targeting.WeightNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeightNormalizerTest {

    private val normalizer = WeightNormalizer()

    @Test
    fun `normalized weights sum to 1_0`() {
        val input = CategoryPool.values().associateWith { 1.0f }
        val result = normalizer.normalize(input)
        val sum = result.values.sum()
        assertEquals(1.0f, sum, 0.001f)
    }

    @Test
    fun `minimum weight clamp enforced`() {
        val input = CategoryPool.values().associateWith { 0.0f }
        val result = normalizer.normalize(input)
        result.values.forEach { weight ->
            assertTrue("Weight $weight must be >= MIN_WEIGHT", weight >= WeightNormalizer.MIN_WEIGHT)
        }
    }

    @Test
    fun `all zeros returns uniform distribution`() {
        val input = CategoryPool.values().associateWith { 0.0f }
        val result = normalizer.normalize(input)
        val expected = 1f / result.size
        result.values.forEach { weight ->
            assertEquals(expected, weight, 0.001f)
        }
    }

    @Test
    fun `single category gets full weight after normalization`() {
        val input = mapOf(CategoryPool.GAMING to 1.0f)
        val result = normalizer.normalize(input)
        assertEquals(1.0f, result[CategoryPool.GAMING]!!, 0.001f)
    }

    @Test
    fun `negative weights are clamped to minimum`() {
        val input = mapOf(
            CategoryPool.GAMING to -5.0f,
            CategoryPool.MEDICAL to 1.0f
        )
        val result = normalizer.normalize(input)
        result.values.forEach { weight ->
            assertTrue("Negative weight should be clamped", weight >= WeightNormalizer.MIN_WEIGHT)
        }
    }

    @Test
    fun `normalizeComplete fills missing categories`() {
        val partial = mapOf(CategoryPool.GAMING to 1.0f)
        val result = normalizer.normalizeComplete(partial)
        assertEquals(CategoryPool.values().size, result.size)
        val sum = result.values.sum()
        assertEquals(1.0f, sum, 0.001f)
    }

    @Test
    fun `high weight category has proportionally higher probability`() {
        val input = mapOf(
            CategoryPool.GAMING to 10.0f,
            CategoryPool.MEDICAL to 1.0f
        )
        val result = normalizer.normalize(input)
        assertTrue(
            "High weight category should have higher probability",
            result[CategoryPool.GAMING]!! > result[CategoryPool.MEDICAL]!!
        )
    }
}
