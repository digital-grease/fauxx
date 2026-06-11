package com.fauxx.engine.scheduling

import javax.inject.Inject
import javax.inject.Singleton

/**
 * E10 (#177): modulates the synthetic action rate by hour of day so the fake activity curve
 * tracks the user's own observed screen-on rhythm (from [CircadianObserver]).
 *
 * Until [MIN_OBSERVATIONS] screen-on events have accumulated, this returns the neutral 1.0
 * multiplier for every hour, so the scheduler falls back to its fixed active window and flat
 * intensity rate. Once enough local data exists, the observed histogram is shaped into a
 * multiplier curve with a 24-hour mean of exactly 1.0 ([RateShaping.meanPreservingFit]),
 * honoring the [RateModulator] rate-honesty contract (issue #76): the user's busy hours get a
 * higher synthetic rate and quiet hours a lower one, with the daily total unchanged.
 *
 * The mean-1.0 guarantee is per-snapshot. The histogram keeps accumulating across the day, so
 * the curve the scheduler reads at hour 8 is normalized against a slightly smaller histogram
 * than the one read at hour 20; the realized daily mean therefore only *converges* to 1.0 as
 * the histogram matures and its per-event change shrinks. The drift is bounded and small once
 * past [MIN_OBSERVATIONS], and the modulation reshapes WHEN actions cluster, not how many.
 *
 * This modulator is composed with the persona rhythm in [CompositeRateModulator] — it is not
 * bound into the scheduler on its own.
 */
@Singleton
class CircadianRateModulator @Inject constructor(
    private val histogram: UsageHistogram,
) : RateModulator {

    override fun multiplier(hourOfDay: Int): Float {
        if (hourOfDay !in 0 until HOURS_PER_DAY) return 1f
        return curve()[hourOfDay]
    }

    /**
     * The full 24-length multiplier curve for the current histogram snapshot: all-neutral
     * before maturity, otherwise the mean-preserving shaping of the observed counts. Computed
     * once here (one snapshot read + one shaping pass) so [CompositeRateModulator] can combine
     * curves without re-shaping per hour.
     */
    internal fun curve(): FloatArray {
        val counts = histogram.hourlyCounts()
        // Fall back to neutral until the local rhythm is well-enough observed (or if the
        // histogram is the wrong shape — defensive, the real one is always 24-long).
        if (counts.size != HOURS_PER_DAY || counts.sum() < MIN_OBSERVATIONS) {
            return FloatArray(HOURS_PER_DAY) { 1f }
        }
        val shaped = RateShaping.meanPreservingFit(
            DoubleArray(counts.size) { counts[it].toDouble() },
            RateModulator.MIN_MULTIPLIER.toDouble(),
            RateModulator.MAX_MULTIPLIER.toDouble(),
        )
        return FloatArray(HOURS_PER_DAY) { shaped[it].toFloat() }
    }

    companion object {
        /**
         * Minimum total screen-on observations before the learned rhythm is trusted. A typical
         * user turns the screen on dozens of times a day, so this is a small number of active
         * days — enough for a stable daily shape, not so high the feature never engages.
         */
        const val MIN_OBSERVATIONS = 50L
    }
}
