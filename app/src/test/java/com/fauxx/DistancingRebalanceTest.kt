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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E9 (#176): the Distancing Engine rebalance toward persona dominance. Pins the
 * acceptance criteria:
 *  - the combined, normalized output is measurably peakier toward the persona's
 *    interests than the pre-E9 blend (concentration strictly increases),
 *  - coverage never collapses (every category stays at or above the normalizer floor,
 *    misaligned categories far above it),
 *  - the multiplicative combine + normalization orchestration is untouched (validated
 *    end-to-end through the real TargetingEngine).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DistancingRebalanceTest {

    private val normalizer = WeightNormalizer()

    private val interests = setOf(
        CategoryPool.COOKING, CategoryPool.TRAVEL, CategoryPool.FITNESS,
        CategoryPool.GAMING, CategoryPool.MUSIC
    )

    /** Pre-E9 blend, reproduced verbatim: 70% follow, full-strength uniform baseline. */
    private fun preE9Weights(): Map<CategoryPool, Float> {
        val follow = 0.70f
        val aligned = 2.0f * follow + 1.0f * (1f - follow)     // 1.7
        val misaligned = 0.3f * follow + 1.0f * (1f - follow)  // 0.51
        return CategoryPool.values().associateWith {
            if (it in interests) aligned else misaligned
        }
    }

    /** Probability mass the normalized distribution puts on the persona's interests. */
    private fun concentration(weights: Map<CategoryPool, Float>): Float =
        normalizer.normalizeComplete(weights)
            .filterKeys { it in interests }.values.sum()

    @Test
    fun `both E9 levers are pinned - blend values are exact`() {
        // 2.0*0.85 + 0.6*0.15 = 1.79 and 0.3*0.85 + 0.6*0.15 = 0.345. A regression of
        // EITHER lever (PERSONA_FOLLOW_FRACTION back to 0.70, or UNIFORM_BASELINE_WEIGHT
        // back to 1.0) shifts these; threshold-only assertions previously let a
        // baseline-weight regression (1.85/0.405) slip through every test.
        val weights = PersonaRotationLayer.weightsFor(interests)
        org.junit.Assert.assertEquals(1.79f, weights.getValue(CategoryPool.COOKING), 1e-3f)
        org.junit.Assert.assertEquals(0.345f, weights.getValue(CategoryPool.LEGAL), 1e-3f)
    }

    @Test
    fun `rebalanced blend is measurably peakier toward persona interests`() {
        val before = concentration(preE9Weights())
        val after = concentration(PersonaRotationLayer.weightsFor(interests))

        assertTrue(
            "concentration must increase: before=$before after=$after",
            after > before + 0.05f
        )
        // Absolute pin: full E9 yields 0.490 (k=5 of 32 categories); a partial
        // regression of the uniform baseline alone yields 0.458 and must fail.
        assertTrue("concentration $after below the full-E9 level", after > 0.47f)
    }

    @Test
    fun `aligned-to-misaligned separation exceeds the pre-E9 ratio`() {
        val weights = PersonaRotationLayer.weightsFor(interests)
        val aligned = weights.getValue(CategoryPool.COOKING)
        val misaligned = weights.getValue(CategoryPool.LEGAL)
        val ratio = aligned / misaligned

        // Pre-E9: 1.7/0.51 = 3.33. Rebalanced: 1.79/0.345 = 5.19. A uniform-baseline
        // regression to 1.0 gives 1.85/0.405 = 4.57 and must FAIL this threshold.
        assertTrue("separation ratio $ratio should exceed 5.0", ratio > 5.0f)
    }

    @Test
    fun `coverage never collapses - misaligned categories keep sampleable mass`() {
        val normalized = normalizer.normalizeComplete(PersonaRotationLayer.weightsFor(interests))

        // (The normalizer clamps every output to MIN_WEIGHT by construction, so
        // asserting >= MIN_WEIGHT would be tautological.) The meaningful guard: with
        // this layer alone, misaligned categories must sit FAR above the floor —
        // a floor-pinned category is one layer-stack away from the detectable
        // absence-signal. 0.345/18.265 = 0.019 per category.
        normalized.filterKeys { it !in interests }.forEach { (category, w) ->
            assertTrue("misaligned $category nearly collapsed: $w", w > 0.005f)
        }
    }

    @Test
    fun `end-to-end - engine output is persona-led with L3 enabled`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val layer1 = mockk<SelfReportLayer> {
            every { getWeights() } returns flowOf(neutral())
        }
        val layer2 = mockk<AdversarialScraperLayer> {
            every { getWeights() } returns flowOf(neutral())
            every { setEnabled(any()) } returns Unit
        }
        val layer3 = mockk<PersonaRotationLayer> {
            every { getWeights() } returns flowOf(PersonaRotationLayer.weightsFor(interests))
            every { setEnabled(any()) } returns Unit
        }
        val engine = TargetingEngine(
            UniformEntropyLayer(), layer1, layer2, layer3, normalizer,
            CoroutineScope(dispatcher), Unit
        )
        try {
            engine.setLayer3Enabled(true)

            val weights = engine.getWeights().first { w ->
                // Skip the pre-emission uniform default.
                w.getValue(CategoryPool.COOKING) > w.getValue(CategoryPool.LEGAL)
            }
            val interestMass = weights.filterKeys { it in interests }.values.sum()
            val uniformMass = interests.size.toFloat() / CategoryPool.values().size

            assertTrue(
                "engine output should be persona-led: interest mass $interestMass vs uniform $uniformMass",
                interestMass > uniformMass * 2.5f
            )
        } finally {
            engine.close()
        }
    }

    private fun neutral(): Map<CategoryPool, Float> =
        CategoryPool.values().associateWith { 1.0f }
}
