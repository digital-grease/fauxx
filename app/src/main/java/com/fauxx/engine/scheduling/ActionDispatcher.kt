package com.fauxx.engine.scheduling

import android.util.Log
import com.fauxx.data.model.PoisonProfile
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.targeting.TargetingEngine
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

private const val TAG = "ActionDispatcher"

/**
 * Picks the next [CategoryPool] to target using weighted random sampling from the
 * [TargetingEngine] weight map. Module selection respects individual enable flags
 * from [PoisonProfile] independently of category weights.
 *
 * The category is determined first (from weights), and the selected module then
 * generates an action in that category.
 */
@Singleton
class ActionDispatcher @Inject constructor(
    private val targetingEngine: TargetingEngine
) {
    /**
     * Select the next [CategoryPool] to target using the current weight distribution.
     *
     * Over a large number of calls, the distribution of returned categories will match
     * the weight map within statistical tolerance.
     */
    suspend fun selectCategory(): CategoryPool {
        val weights = try {
            targetingEngine.getWeights().first()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get weights, falling back to uniform", e)
            CategoryPool.values().associateWith { 1f / CategoryPool.values().size }
        }

        return weightedSample(weights)
    }

    /**
     * Perform weighted random sampling over [weights].
     * All weights must be non-negative; [WeightNormalizer] guarantees this.
     */
    fun weightedSample(weights: Map<CategoryPool, Float>): CategoryPool {
        val total = weights.values.sum()
        if (total <= 0f) return CategoryPool.values().random()

        var threshold = Random.nextFloat() * total
        for ((category, weight) in weights) {
            threshold -= weight
            if (threshold <= 0f) return category
        }

        // Fallback due to floating point — return last entry
        return weights.keys.last()
    }
}
