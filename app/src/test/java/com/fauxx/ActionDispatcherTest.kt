package com.fauxx

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.scheduling.ActionDispatcher
import com.fauxx.targeting.TargetingEngine
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionDispatcherTest {

    private val targetingEngine: TargetingEngine = mockk()
    private val dispatcher = ActionDispatcher(targetingEngine)

    @Test
    fun `weighted sample distribution matches weights within tolerance`() = runTest {
        // Assign GAMING a 10x higher weight than all others
        val weights = CategoryPool.values().associateWith { cat ->
            if (cat == CategoryPool.GAMING) 10.0f else 1.0f
        }
        val total = weights.values.sum()
        val normalized = weights.mapValues { (_, v) -> v / total }

        val samples = 10_000
        val counts = mutableMapOf<CategoryPool, Int>()
        repeat(samples) {
            val cat = dispatcher.weightedSample(normalized)
            counts[cat] = (counts[cat] ?: 0) + 1
        }

        val gamingFraction = (counts[CategoryPool.GAMING] ?: 0).toFloat() / samples
        val expectedGamingFraction = normalized[CategoryPool.GAMING]!!

        // Within 5% tolerance (chi-squared equivalent for single category)
        assertTrue(
            "GAMING fraction $gamingFraction should be near expected $expectedGamingFraction",
            Math.abs(gamingFraction - expectedGamingFraction) < 0.05f
        )
    }

    @Test
    fun `weightedSample handles uniform weights`() {
        val uniform = CategoryPool.values().associateWith { 1f / CategoryPool.values().size }
        val samples = 10_000
        val counts = mutableMapOf<CategoryPool, Int>()
        repeat(samples) {
            val cat = dispatcher.weightedSample(uniform)
            counts[cat] = (counts[cat] ?: 0) + 1
        }
        // Each category should appear roughly samples/n times, within 3%
        val expectedFraction = 1f / CategoryPool.values().size
        for (cat in CategoryPool.values()) {
            val fraction = (counts[cat] ?: 0).toFloat() / samples
            assertTrue(
                "$cat fraction $fraction diverges from expected $expectedFraction",
                Math.abs(fraction - expectedFraction) < 0.03f
            )
        }
    }
}
