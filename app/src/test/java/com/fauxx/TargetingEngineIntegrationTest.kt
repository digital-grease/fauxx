package com.fauxx

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.targeting.TargetingEngine
import com.fauxx.targeting.WeightNormalizer
import com.fauxx.targeting.layer0.UniformEntropyLayer
import com.fauxx.targeting.layer1.SelfReportLayer
import com.fauxx.targeting.layer2.AdversarialScraperLayer
import com.fauxx.targeting.layer3.PersonaRotationLayer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetingEngineIntegrationTest {

    private val normalizer = WeightNormalizer()

    @Test
    fun `all layers disabled produces uniform distribution`() = runTest {
        val engine = buildEngine(
            l1Weights = neutralWeights(),
            l2Weights = neutralWeights(),
            l3Weights = neutralWeights()
        )
        val weights = engine.getWeights().first()
        val expected = 1f / CategoryPool.values().size
        weights.values.forEach { w ->
            assertEquals("Uniform distribution expected", expected, w, 0.01f)
        }
    }

    @Test
    fun `L1 suppresses close categories and boosts distant`() = runTest {
        // Simulate L1: GAMING close (0.15), RETIREMENT distant (2.5), rest neutral
        val l1 = neutralWeights().toMutableMap().apply {
            put(CategoryPool.GAMING, 0.15f)
            put(CategoryPool.RETIREMENT, 2.5f)
        }
        val engine = buildEngine(
            l1Weights = l1,
            l2Weights = neutralWeights(),
            l3Weights = neutralWeights(),
            l1Enabled = true
        )
        val weights = engine.getWeights().first()
        assertTrue(
            "RETIREMENT should have higher weight than GAMING",
            weights[CategoryPool.RETIREMENT]!! > weights[CategoryPool.GAMING]!!
        )
    }

    @Test
    fun `L1 plus L2 multiplicatively suppresses confirmed close categories`() = runTest {
        // L1 says GAMING is close (0.15), L2 says platform confirmed GAMING (0.05)
        val l1 = neutralWeights().toMutableMap().apply {
            put(CategoryPool.GAMING, 0.15f)
        }
        val l2 = neutralWeights().toMutableMap().apply {
            put(CategoryPool.GAMING, 0.05f)
            put(CategoryPool.AGRICULTURE, 3.0f)
        }
        val engine = buildEngine(
            l1Weights = l1,
            l2Weights = l2,
            l3Weights = neutralWeights(),
            l1Enabled = true,
            l2Enabled = true
        )
        val weights = engine.getWeights().first()

        // GAMING: 1.0 * 0.15 * 0.05 * 1.0 = 0.0075 (heavily suppressed)
        // AGRICULTURE: 1.0 * 1.0 * 3.0 * 1.0 = 3.0 (boosted)
        assertTrue(
            "AGRICULTURE should be much higher than GAMING after multiplicative suppression",
            weights[CategoryPool.AGRICULTURE]!! > weights[CategoryPool.GAMING]!! * 10
        )
    }

    @Test
    fun `all three layers combine multiplicatively`() = runTest {
        val l1 = neutralWeights().toMutableMap().apply {
            put(CategoryPool.GAMING, 0.15f)
            put(CategoryPool.RETIREMENT, 2.5f)
        }
        val l2 = neutralWeights().toMutableMap().apply {
            put(CategoryPool.GAMING, 0.05f)
            put(CategoryPool.COOKING, 3.0f)
        }
        val l3 = neutralWeights().toMutableMap().apply {
            put(CategoryPool.COOKING, 2.0f)
            put(CategoryPool.GAMING, 0.3f)
        }

        val engine = buildEngine(
            l1Weights = l1, l2Weights = l2, l3Weights = l3,
            l1Enabled = true, l2Enabled = true, l3Enabled = true
        )
        val weights = engine.getWeights().first()

        // GAMING: 1.0 * 0.15 * 0.05 * 0.3 = 0.00225 (extremely suppressed)
        // COOKING: 1.0 * 1.0 * 3.0 * 2.0 = 6.0 (extremely boosted)
        // RETIREMENT: 1.0 * 2.5 * 1.0 * 1.0 = 2.5 (moderately boosted)
        assertTrue(
            "COOKING should dominate GAMING after 3-layer multiplication",
            weights[CategoryPool.COOKING]!! > weights[CategoryPool.GAMING]!! * 50
        )
        assertTrue(
            "COOKING should be higher than RETIREMENT",
            weights[CategoryPool.COOKING]!! > weights[CategoryPool.RETIREMENT]!!
        )
    }

    @Test
    fun `weights sum to 1_0 after normalization`() = runTest {
        val l1 = neutralWeights().toMutableMap().apply {
            put(CategoryPool.GAMING, 0.15f)
            put(CategoryPool.MEDICAL, 2.5f)
        }
        val engine = buildEngine(
            l1Weights = l1,
            l2Weights = neutralWeights(),
            l3Weights = neutralWeights(),
            l1Enabled = true
        )
        val weights = engine.getWeights().first()
        val sum = weights.values.sum()
        assertEquals("Weights must sum to ~1.0", 1.0f, sum, 0.01f)
    }

    @Test
    fun `no weight is below minimum clamp`() = runTest {
        val l1 = neutralWeights().toMutableMap().apply {
            put(CategoryPool.GAMING, 0.15f)
        }
        val l2 = neutralWeights().toMutableMap().apply {
            put(CategoryPool.GAMING, 0.05f)
        }
        val l3 = neutralWeights().toMutableMap().apply {
            put(CategoryPool.GAMING, 0.3f)
        }
        val engine = buildEngine(
            l1Weights = l1, l2Weights = l2, l3Weights = l3,
            l1Enabled = true, l2Enabled = true, l3Enabled = true
        )
        val weights = engine.getWeights().first()
        weights.values.forEach { w ->
            assertTrue("No weight should be below MIN_WEIGHT", w >= WeightNormalizer.MIN_WEIGHT)
        }
    }

    private fun neutralWeights(): Map<CategoryPool, Float> =
        CategoryPool.values().associateWith { 1.0f }

    private fun buildEngine(
        l1Weights: Map<CategoryPool, Float>,
        l2Weights: Map<CategoryPool, Float>,
        l3Weights: Map<CategoryPool, Float>,
        l1Enabled: Boolean = false,
        l2Enabled: Boolean = false,
        l3Enabled: Boolean = false
    ): TargetingEngine {
        val layer0 = UniformEntropyLayer()
        val layer1: SelfReportLayer = mockk { every { getWeights() } returns flowOf(l1Weights) }
        val layer2: AdversarialScraperLayer = mockk(relaxed = true) { every { getWeights() } returns flowOf(l2Weights) }
        val layer3: PersonaRotationLayer = mockk(relaxed = true) { every { getWeights() } returns flowOf(l3Weights) }

        val engine = TargetingEngine(layer0, layer1, layer2, layer3, normalizer)
        engine.setLayer1Enabled(l1Enabled)
        engine.setLayer2Enabled(l2Enabled)
        engine.setLayer3Enabled(l3Enabled)
        return engine
    }
}
