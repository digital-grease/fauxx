package com.fauxx.targeting

import com.fauxx.data.querybank.CategoryPool
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Normalizes a raw weight map so all values sum to 1.0, suitable for probability sampling.
 * Clamps any weight below [MIN_WEIGHT] to ensure no category is ever truly excluded
 * (absence of a category is itself a detectable signal).
 */
@Singleton
class WeightNormalizer @Inject constructor() {

    companion object {
        /** Minimum weight for any category — never truly zero. */
        const val MIN_WEIGHT = 0.001f
    }

    /**
     * Normalize [weights] to sum to 1.0. Any negative or zero value is replaced by [MIN_WEIGHT]
     * before normalization. If all values sum to zero (degenerate case), returns uniform weights.
     *
     * @param weights Raw multiplicative weights per category.
     * @return Normalized probability distribution summing to 1.0.
     */
    fun normalize(weights: Map<CategoryPool, Float>): Map<CategoryPool, Float> {
        // Clamp minimums first
        val clamped = weights.mapValues { (_, v) -> maxOf(v, MIN_WEIGHT) }

        val sum = clamped.values.sum()
        if (sum <= 0f) {
            // Degenerate case: return uniform distribution
            val uniform = 1f / clamped.size
            return clamped.mapValues { uniform }
        }

        val normalized = clamped.mapValues { (_, v) -> v / sum }

        // Re-clamp after normalization: a value clamped to MIN_WEIGHT then divided by a
        // larger sum can dip just below MIN_WEIGHT (e.g., 0.001 / 1.001 ≈ 0.000999).
        val reClamped = normalized.mapValues { (_, v) -> maxOf(v, MIN_WEIGHT) }
        val reSum = reClamped.values.sum()
        return reClamped.mapValues { (_, v) -> v / reSum }
    }

    /**
     * Ensure [weights] covers all [CategoryPool] values. Any missing category is assigned
     * [MIN_WEIGHT] before normalization.
     */
    fun normalizeComplete(weights: Map<CategoryPool, Float>): Map<CategoryPool, Float> {
        val complete = CategoryPool.values().associateWith { category ->
            weights.getOrDefault(category, MIN_WEIGHT)
        }
        return normalize(complete)
    }
}
