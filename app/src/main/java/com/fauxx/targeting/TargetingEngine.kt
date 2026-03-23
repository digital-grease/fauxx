package com.fauxx.targeting

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.targeting.layer0.UniformEntropyLayer
import com.fauxx.targeting.layer1.SelfReportLayer
import com.fauxx.targeting.layer2.AdversarialScraperLayer
import com.fauxx.targeting.layer3.PersonaRotationLayer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrator for the Demographic Distancing Engine.
 *
 * Combines all four targeting layers into a final normalized weight map via multiplicative
 * combination: finalWeight = L0 × L1 × L2 × L3, then normalized so all weights sum to 1.0.
 *
 * Exposes [getWeights] as a reactive [Flow] that recalculates automatically when any layer's
 * inputs change (user edits their profile, scraper returns new data, persona rotates).
 *
 * Layer 1/2/3 enable flags are respected independently — disabled layers contribute 1.0
 * (neutral) weights across all categories, so they have no effect on the final distribution.
 */
@Singleton
class TargetingEngine @Inject constructor(
    private val layer0: UniformEntropyLayer,
    private val layer1: SelfReportLayer,
    private val layer2: AdversarialScraperLayer,
    private val layer3: PersonaRotationLayer,
    private val normalizer: WeightNormalizer
) {
    private var layer1Enabled: Boolean = false
    private var layer2Enabled: Boolean = false
    private var layer3Enabled: Boolean = false

    /** Enable or disable Layer 1 (self-report). */
    fun setLayer1Enabled(enabled: Boolean) { layer1Enabled = enabled }

    /** Enable or disable Layer 2 (adversarial scraper). */
    fun setLayer2Enabled(enabled: Boolean) {
        layer2Enabled = enabled
        layer2.setEnabled(enabled)
    }

    /** Enable or disable Layer 3 (persona rotation). */
    fun setLayer3Enabled(enabled: Boolean) {
        layer3Enabled = enabled
        layer3.setEnabled(enabled)
    }

    /**
     * Returns a [Flow] emitting the current normalized weight map across all [CategoryPool] values.
     * Recalculates reactively whenever any underlying layer emits new weights.
     *
     * The output is always a valid probability distribution: all values are positive and sum to 1.0.
     */
    fun getWeights(): Flow<Map<CategoryPool, Float>> =
        combine(
            layer0.getWeights(),
            layer1.getWeights(),
            layer2.getWeights(),
            layer3.getWeights()
        ) { l0, l1, l2, l3 ->
            val combined = CategoryPool.values().associateWith { category ->
                val w0 = l0.getOrDefault(category, 1f)
                val w1 = if (layer1Enabled) l1.getOrDefault(category, 1f) else 1f
                val w2 = if (layer2Enabled) l2.getOrDefault(category, 1f) else 1f
                val w3 = if (layer3Enabled) l3.getOrDefault(category, 1f) else 1f
                w0 * w1 * w2 * w3
            }
            normalizer.normalizeComplete(combined)
        }
}
