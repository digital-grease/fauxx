package com.fauxx.targeting.allocation

import com.fauxx.data.querybank.CategoryPool
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

/**
 * Cheap on-device surrogate for what an ad broker would INFER from a synthetic category
 * distribution (E4 #180). For each category it returns
 *
 *     score(c) = sigmoid(bias + selfCoef * ownMass(c) + neighborCoef * neighborMass(c))
 *
 * where `ownMass(c)` is c's weight and `neighborMass(c)` is the affinity-weighted mass of its
 * co-occurring neighbors (both scaled by the category count so a uniform distribution maps each
 * feature near 1.0). A category is "inferred" when it OR its co-occurring neighbors carry
 * above-average weight — capturing the inferential closure the heuristic layers miss.
 *
 * Pure, deterministic, no ML runtime. It is a research surrogate, not a real broker model: see the
 * honesty note on [CooccurrenceTable].
 */
@Singleton
class BrokerSurrogate @Inject constructor(
    private val table: CooccurrenceTable,
) {
    /** Per-category inference scores in (0,1) for a normalized [weights] map (sums to ~1.0). */
    fun infer(weights: Map<CategoryPool, Float>): Map<CategoryPool, Double> {
        val cats = CategoryPool.values()
        val n = cats.size
        return cats.associateWith { c ->
            val own = mass(weights, c) * n
            var neighborMass = 0.0
            for ((neighbor, affinity) in table.neighborsOf(c)) {
                neighborMass += affinity * mass(weights, neighbor) * n
            }
            sigmoid(table.bias + table.selfCoef * own + table.neighborCoef * neighborMass)
        }
    }

    private fun mass(weights: Map<CategoryPool, Float>, c: CategoryPool): Double =
        weights[c]?.toDouble() ?: 0.0

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))
}
