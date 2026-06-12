package com.fauxx.targeting.allocation

import com.fauxx.data.SensitiveAttributes
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.targeting.WeightNormalizer
import com.fauxx.targeting.layer2.ProfileDriftMetric
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Allocates synthetic noise as minimal, KL-budgeted adversarial perturbations to the combined
 * targeting weight map (E4 #180). Where the heuristic layers spread noise by fixed rules, this
 * step uses [BrokerSurrogate] to model what a broker would INFER and then perturbs the
 * distribution to drive that inference DOWN for the categories the user actually leans toward —
 * directly and through their co-occurring neighbours — while staying within a KL-divergence budget
 * of the input so the result stays a plausible distribution.
 *
 * The "true interests" to hide ([protectedInterests]) are derived upstream from the existing L1/L2
 * signals (self-reported-close and platform-confirmed categories); no new inference is introduced.
 *
 * Invariants: never perturbs a sensitive category (consults [SensitiveAttributes], which names E4
 * as a required caller); fully on-device, allocation-free of telemetry; deterministic; the output
 * is always a valid normalized distribution over every [CategoryPool].
 */
@Singleton
class AdversarialAllocator @Inject constructor(
    private val surrogate: BrokerSurrogate,
    private val normalizer: WeightNormalizer,
    private val driftMetric: ProfileDriftMetric,
) {
    /**
     * @param combined post-L0..L3, normalized weight map (sums to 1.0).
     * @param protectedInterests categories the broker should NOT be able to infer (the user's
     *   real/known interests). Empty => nothing to hide => [combined] is returned unchanged.
     * @param klBudget maximum KL(result || combined) in nats. Default [DEFAULT_KL_BUDGET].
     * @return a normalized distribution that minimizes surrogate inference of [protectedInterests]
     *   within the budget; never worse (by the surrogate objective) than [combined].
     */
    fun allocate(
        combined: Map<CategoryPool, Float>,
        protectedInterests: Set<CategoryPool>,
        klBudget: Double = DEFAULT_KL_BUDGET,
    ): Map<CategoryPool, Float> {
        // Invariant: only non-sensitive categories may ever be perturbed.
        val allowed = CategoryPool.values().filter { !SensitiveAttributes.matches(it.name) }
        val protectedAllowed = protectedInterests.filterTo(HashSet()) { it in allowed }
        if (protectedAllowed.isEmpty()) return combined

        var current = normalizer.normalizeComplete(combined)
        var currentObjective = objective(current, protectedAllowed)

        // Coordinate descent: each pass tries to suppress or boost one category at a time, keeping
        // the single change that most reduces protected-interest inference while staying in budget.
        repeat(PASSES) {
            for (cat in allowed) {
                var bestCandidate = current
                var bestObjective = currentObjective
                for (factor in FACTORS) {
                    val candidate = applyFactor(current, cat, factor)
                    if (driftMetric.kl(candidate, combined) > klBudget) continue
                    val candidateObjective = objective(candidate, protectedAllowed)
                    if (candidateObjective < bestObjective - EPS) {
                        bestObjective = candidateObjective
                        bestCandidate = candidate
                    }
                }
                current = bestCandidate
                currentObjective = bestObjective
            }
        }
        return current
    }

    /** Total surrogate inference over the protected set — the quantity the allocator minimizes. */
    private fun objective(weights: Map<CategoryPool, Float>, protectedSet: Set<CategoryPool>): Double {
        val inferred = surrogate.infer(weights)
        return protectedSet.sumOf { inferred[it] ?: 0.0 }
    }

    /** Multiply one category's weight by [factor] and re-normalize the whole distribution. */
    private fun applyFactor(
        weights: Map<CategoryPool, Float>,
        cat: CategoryPool,
        factor: Float,
    ): Map<CategoryPool, Float> {
        val raw = weights.toMutableMap()
        raw[cat] = (raw[cat] ?: WeightNormalizer.MIN_WEIGHT) * factor
        return normalizer.normalizeComplete(raw)
    }

    companion object {
        /** Default budget (nats) for how far the adversarial result may drift from the input. */
        const val DEFAULT_KL_BUDGET = 0.15

        /** Coordinate-descent passes over the category set. */
        private const val PASSES = 10

        /** Per-category multiplicative candidates tried each pass (suppress and boost). */
        private val FACTORS = listOf(0.4f, 0.6f, 0.8f, 1.25f, 1.7f, 2.5f)

        /** Minimum objective improvement required to accept a move (avoids tie oscillation). */
        private const val EPS = 1e-6
    }
}
