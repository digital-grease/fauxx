package com.fauxx.engine.scheduling

/**
 * Shared shaping math for the rate modulators (E10 #177).
 *
 * Both the circadian histogram and the persona×circadian composite need to turn an arbitrary
 * per-hour shape into a multiplier curve that (a) has a 24-hour mean of exactly 1.0 — the
 * [RateModulator] honesty contract (issue #76), modulation reshapes WHEN actions happen, not
 * how many — and (b) stays within the scheduler's [RateModulator.MIN_MULTIPLIER]..
 * [RateModulator.MAX_MULTIPLIER] band so the scheduler's own clamp never bites and silently
 * skews the mean (a hard clamp is not mean-preserving).
 */
object RateShaping {

    /**
     * Map [values] to a multiplier curve with mean exactly 1.0, shaped like [values], scaled
     * so every entry lies within [[lo], [hi]].
     *
     * Method: normalize to the input mean (so the normalized curve has mean 1.0), then
     * attenuate the deviations from 1.0 by the single largest factor `s` in `[0,1]` that pulls
     * the most extreme entry inside `[lo, hi]`. Because the attenuation is affine about 1.0
     * (`1 + s*(norm - 1)`) and `mean(norm) == 1`, the result's mean is exactly 1.0 regardless
     * of `s`. A flat (or all-zero / negative-mean degenerate) input returns a flat 1.0 curve.
     *
     * @param values raw per-hour shape (e.g. observation counts, or a product of curves).
     * @param lo lower multiplier bound (inclusive), expected < 1.0.
     * @param hi upper multiplier bound (inclusive), expected > 1.0.
     * @return a curve the same length as [values]; empty in, empty out.
     */
    fun meanPreservingFit(values: DoubleArray, lo: Double, hi: Double): DoubleArray {
        if (values.isEmpty()) return DoubleArray(0)
        val mean = values.average()
        // Degenerate input (no signal yet, or a pathological negative) -> neutral.
        if (mean <= 0.0) return DoubleArray(values.size) { 1.0 }

        val norm = DoubleArray(values.size) { values[it] / mean } // mean(norm) == 1.0

        var maxDev = 0.0 // largest positive deviation above 1.0
        var minDev = 0.0 // most negative deviation below 1.0
        for (v in norm) {
            val d = v - 1.0
            if (d > maxDev) maxDev = d
            if (d < minDev) minDev = d
        }

        var s = 1.0
        if (maxDev > 0.0) s = minOf(s, (hi - 1.0) / maxDev)
        if (minDev < 0.0) s = minOf(s, (1.0 - lo) / -minDev)
        s = s.coerceIn(0.0, 1.0)

        return DoubleArray(values.size) { 1.0 + s * (norm[it] - 1.0) }
    }
}
