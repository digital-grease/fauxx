package com.fauxx.engine.scheduling

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.util.Clock
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Generates next-action timestamps following a Poisson process with human-like circadian patterns.
 *
 * Behavioral properties:
 * - Active 7am–11pm local time by default (configurable via the allowedStart/allowedEnd
 *   parameters of [nextDelayMs]; window semantics live in [AllowedHours])
 * - Produces bursts of 3-7 actions close together, then gaps of 5-20 minutes
 * - Near-zero activity outside the allowed window
 * - Inter-arrival times follow exponential distribution (Poisson process property)
 *
 * Cross-niche dwell time:
 * Heuristic bot-detection engines (e.g., Google GWS) flag sub-second transitions between
 * disparate content niches (Finance → Legal in 4s) as a high-signal bot indicator. To
 * avoid this, [nextDelayMs] accepts the previous and next category and applies a
 * lognormal dwell-time multiplier whenever the categories differ. Within-topic activity
 * (same category) still allows the original burst behavior, since real users fire
 * multiple queries on the same subject in quick succession.
 *
 * The minimum cross-niche gap (the dwell floor) scales with intensity rather than being a
 * flat 30s: the calmer tiers stay human-paced while the aggressive tiers trade some of this
 * separation for throughput so their displayed actions/hour stays honest. See
 * [crossNicheFloorMs].
 */
@Singleton
class PoissonScheduler @Inject constructor(
    private val clock: Clock,
    private val random: Random = Random.Default,
    private val rateModulator: RateModulator = RateModulator.NEUTRAL,
) {

    companion object {
        /** Default quiet hours: 11pm to 7am. */
        const val DEFAULT_QUIET_START = 23
        const val DEFAULT_QUIET_END = 7

        /**
         * Bounds for the per-tier cross-niche dwell floor (see [crossNicheFloorMs]).
         * The calm tiers sit at the 30s ceiling; the most aggressive tier bottoms out at 5s.
         */
        private const val CROSS_NICHE_FLOOR_MIN_MS = 5_000L
        private const val CROSS_NICHE_FLOOR_MAX_MS = 30_000L

        /**
         * Lognormal parameters for the cross-niche dwell multiplier. Tuned so:
         * - median multiplier ≈ 1.0 (i.e. multiplier = e^mu = 1)
         * - p95 multiplier ≈ 4.5×
         * The multiplier is applied on top of the Poisson exponential so per-hour rate
         * targets degrade gracefully rather than being violated.
         */
        private const val DWELL_MU = 0.0
        private const val DWELL_SIGMA = 0.9
    }

    /**
     * Compute the delay in milliseconds until the next action should fire.
     *
     * @param actionsPerHour Target action rate from [com.fauxx.data.model.IntensityLevel].
     * @param prev Previously executed category, or null for the first action this run.
     * @param next Category about to be executed.
     * @param allowedStart Hour of day (0-23) when activity may begin.
     * @param allowedEnd Hour of day (0-24) when activity must stop. Equal to [allowedStart]
     *   means a 24h window — see [AllowedHours] for the shared semantics (issue #124).
     * @return Delay in milliseconds. May be large if currently in quiet hours.
     */
    fun nextDelayMs(
        actionsPerHour: Int,
        prev: CategoryPool? = null,
        next: CategoryPool? = null,
        allowedStart: Int = DEFAULT_QUIET_END,
        allowedEnd: Int = DEFAULT_QUIET_START
    ): Long {
        val now = Calendar.getInstance().apply { timeInMillis = clock.currentTimeMillis() }
        val currentHour = now.get(Calendar.HOUR_OF_DAY)

        // If in quiet hours, delay until allowed start. Uses the SAME predicate as the
        // engine's constraint gate (AllowedHours) so the two can never disagree about
        // whether "now" is active — the divergence that caused issue #124, where the
        // engine executed an action but this branch then slept until local midnight.
        if (!AllowedHours.isWithin(currentHour, allowedStart, allowedEnd)) {
            return msUntilHour(now, allowedStart)
        }

        // Use the target rate directly — actionsPerHour already represents the desired
        // rate during active hours, no need to scale by active fraction. The rate
        // modulator (E8 persona rhythm, later composed with E10's circadian histogram)
        // softly reshapes WHEN actions cluster; its 24h mean stays ~1.0 and the clamp
        // bounds the worst case, so the displayed rate stays honest (issue #76).
        val modulation = rateModulator.multiplier(currentHour)
            .coerceIn(RateModulator.MIN_MULTIPLIER, RateModulator.MAX_MULTIPLIER)
        val effectiveRate = actionsPerHour.toFloat() * modulation

        val sameTopic = prev == null || next == null || prev == next

        // Burst-gap behavior: 30% chance of burst mode, but ONLY for same-topic transitions.
        // Cross-niche bursts are the exact bot signal we are avoiding.
        return if (sameTopic && random.nextFloat() < 0.30f) {
            // Burst: 2-30 seconds (intra-topic only)
            random.nextLong(2_000L, 30_000L)
        } else {
            val baseDelay = poissonDelay(effectiveRate)
            if (sameTopic) {
                baseDelay
            } else {
                // Cross-niche: scale up by a lognormal dwell multiplier and enforce a
                // tier-derived floor (lower for the aggressive tiers — see crossNicheFloorMs).
                val dwell = (baseDelay * lognormalMultiplier()).toLong()
                maxOf(crossNicheFloorMs(actionsPerHour), dwell)
            }
        }
    }

    /**
     * Minimum gap to enforce between two different content niches, derived from the
     * target rate so the displayed actions/hour stays achievable.
     *
     * Originally a flat 30s, which silently capped real throughput well below the higher
     * intensity targets (a sustained 200/hr is impossible if every cross-niche switch waits
     * 30s). The floor is now half the mean inter-arrival time, clamped to
     * [[CROSS_NICHE_FLOOR_MIN_MS], [CROSS_NICHE_FLOOR_MAX_MS]]:
     *  - LOW (12/hr) and MEDIUM (60/hr) → 30s (unchanged, still human-paced)
     *  - HIGH (200/hr) → 9s
     *  - EXTREME (500/hr) → 5s
     *
     * The shorter floor on the aggressive tiers narrows the gap between unrelated topics,
     * which is exactly the trade-off surfaced to the user as a detectability warning.
     */
    private fun crossNicheFloorMs(actionsPerHour: Int): Long {
        if (actionsPerHour <= 0) return CROSS_NICHE_FLOOR_MAX_MS
        val halfMean = (3_600_000L / actionsPerHour) / 2
        return halfMean.coerceIn(CROSS_NICHE_FLOOR_MIN_MS, CROSS_NICHE_FLOOR_MAX_MS)
    }

    /**
     * Sample a lognormal-distributed multiplier for cross-niche dwell scaling.
     * Uses Box-Muller to generate a standard normal, then exponentiates.
     */
    private fun lognormalMultiplier(): Double {
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        val z = sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
        return exp(DWELL_MU + DWELL_SIGMA * z)
    }

    /**
     * Generate an exponentially-distributed delay matching a Poisson process
     * with rate [actionsPerHour].
     *
     * The upper clamp scales with intensity: 3× the mean inter-arrival time
     * (minimum 60 s) so HIGH mode (200/hr, mean 18 s) caps at ~54 s while
     * LOW mode (12/hr, mean 300 s) caps at ~15 min.
     */
    fun poissonDelay(actionsPerHour: Float): Long {
        if (actionsPerHour <= 0f) return 60_000L
        val ratePerMs = actionsPerHour / (60f * 60f * 1000f)
        val meanDelayMs = (3_600_000f / actionsPerHour).toLong()
        val maxDelayMs = maxOf(60_000L, meanDelayMs * 3)
        val u = random.nextDouble()
        val delayMs = (-ln(1.0 - u) / ratePerMs).toLong()
        return delayMs.coerceIn(1_000L, maxDelayMs)
    }

    private fun msUntilHour(now: Calendar, targetHour: Int): Long {
        val target = now.clone() as Calendar
        target.set(Calendar.HOUR_OF_DAY, targetHour)
        target.set(Calendar.MINUTE, 0)
        target.set(Calendar.SECOND, 0)
        target.set(Calendar.MILLISECOND, 0)

        if (target.before(now)) {
            target.add(Calendar.DAY_OF_MONTH, 1)
        }

        return maxOf(target.timeInMillis - now.timeInMillis, 60_000L)
    }
}
