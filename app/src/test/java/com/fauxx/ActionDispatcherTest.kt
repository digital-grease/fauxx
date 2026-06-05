package com.fauxx

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.scheduling.ActionDispatcher
import com.fauxx.support.seededRandom
import com.fauxx.targeting.TargetingEngine
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    // --- Deterministic fallback / seam-injected tests ---------------------------------------
    //
    // These build a SECOND dispatcher per assertion that injects the seededRandom() seam as the
    // 2nd positional ctor arg: ActionDispatcher(targetingEngine, seededRandom()). A FRESH
    // dispatcher is built for each independent deterministic assertion because random.random()
    // advances the RNG state, so reusing one instance would not give a clean cross-instance
    // equality. We prefer cross-instance-equality assertions (two freshly seededRandom()-seeded
    // dispatchers must agree) over hardcoded magic enum literals — the seed could change.

    @Test
    fun `empty weight map falls back to deterministic uniform-random category`() {
        // Two independently constructed dispatchers, each seeded by the same default seed, must
        // resolve the empty-map uniform-random fallback to the SAME category.
        val first = ActionDispatcher(targetingEngine, seededRandom()).weightedSample(emptyMap())
        val second = ActionDispatcher(targetingEngine, seededRandom()).weightedSample(emptyMap())
        assertEquals(
            "Empty-map uniform fallback must be deterministic under a fixed seed",
            first,
            second
        )
    }

    @Test
    fun `zero-sum weight map falls back to deterministic uniform-random category`() {
        // Distinct guard from the empty-map branch: a non-empty map whose values all sum to 0f
        // (total <= 0f) takes the second fallback. Two fresh seeded dispatchers must agree.
        val zeroSum = CategoryPool.values().associateWith { 0f }
        val first = ActionDispatcher(targetingEngine, seededRandom()).weightedSample(zeroSum)
        val second = ActionDispatcher(targetingEngine, seededRandom()).weightedSample(zeroSum)
        assertEquals(
            "Zero-sum-map uniform fallback must be deterministic under a fixed seed",
            first,
            second
        )
    }

    @Test
    fun `negative-summing weight map falls back to deterministic uniform-random category`() {
        // Defensive: in production WeightNormalizer guarantees non-negative weights, so a
        // negative sum cannot occur from real inputs. The total <= 0f guard still covers it
        // identically to the zero-sum case; two fresh seeded dispatchers must agree.
        val negativeSum = CategoryPool.values().associateWith { -1f }
        val first = ActionDispatcher(targetingEngine, seededRandom()).weightedSample(negativeSum)
        val second = ActionDispatcher(targetingEngine, seededRandom()).weightedSample(negativeSum)
        assertEquals(
            "Negative-sum-map uniform fallback must be deterministic under a fixed seed",
            first,
            second
        )
    }

    @Test
    fun `single-entry weight map returns that entry`() {
        // With exactly one positive-weight entry the sampling loop can only resolve to it,
        // regardless of the RNG draw — no fallback, no magic literal needed beyond the input.
        val single = mapOf(CategoryPool.GAMING to 1f)
        val picked = ActionDispatcher(targetingEngine, seededRandom()).weightedSample(single)
        assertEquals(CategoryPool.GAMING, picked)
    }

    @Test
    fun `weighted pick is deterministic under a fixed seed`() {
        // A skewed distribution (GAMING heavily favored) still routes through random.nextFloat();
        // two independently constructed, identically seeded dispatchers must pick the SAME
        // category from the same map. Cross-instance equality avoids asserting a magic enum.
        val skewed = CategoryPool.values().associateWith { cat ->
            if (cat == CategoryPool.GAMING) 10f else 1f
        }
        val first = ActionDispatcher(targetingEngine, seededRandom()).weightedSample(skewed)
        val second = ActionDispatcher(targetingEngine, seededRandom()).weightedSample(skewed)
        assertEquals(
            "Weighted pick must be deterministic under a fixed seed",
            first,
            second
        )
    }

    @Test
    fun `selectCategory reads cachedWeights snapshot and delegates to weightedSample`() {
        // selectCategory() reads targetingEngine.cachedWeights.value then delegates to
        // weightedSample. Mock the snapshot (every { ... } returns MutableStateFlow(...)) and
        // assert selectCategory equals what a freshly seeded dispatcher returns for the SAME map.
        val skewed = CategoryPool.values().associateWith { cat ->
            if (cat == CategoryPool.GAMING) 10f else 1f
        }
        every { targetingEngine.cachedWeights } returns MutableStateFlow(skewed)

        val viaSelect = ActionDispatcher(targetingEngine, seededRandom()).selectCategory()
        val viaSample = ActionDispatcher(targetingEngine, seededRandom()).weightedSample(skewed)
        assertEquals(
            "selectCategory must delegate to weightedSample over the cachedWeights snapshot",
            viaSample,
            viaSelect
        )
    }

    @Test
    fun `selectCategory with empty cached weights falls back to uniform-random category`() {
        // An empty cachedWeights snapshot drives selectCategory through the empty-map uniform
        // fallback; it must match a freshly seeded dispatcher's empty-map weightedSample result.
        every { targetingEngine.cachedWeights } returns MutableStateFlow(emptyMap())

        val viaSelect = ActionDispatcher(targetingEngine, seededRandom()).selectCategory()
        val viaSample = ActionDispatcher(targetingEngine, seededRandom()).weightedSample(emptyMap())
        assertEquals(
            "selectCategory over an empty snapshot must match the empty-map uniform fallback",
            viaSample,
            viaSelect
        )
    }
}
