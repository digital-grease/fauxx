package com.fauxx.engine.scheduling

import com.fauxx.targeting.layer3.PersonaRateModulator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single rate-modulation seam bound into [PoissonScheduler] (E10 #177).
 *
 * Both timing signals feed through here rather than the scheduler gaining a second modulation
 * point: the persona's deterministic daily rhythm ([PersonaRateModulator], E8 #174) and the
 * user's observed screen-on rhythm ([CircadianRateModulator], E10). The two are combined
 * multiplicatively (a quiet hour for the persona AND the user is quieter still) and then
 * reshaped to a 24-hour mean of exactly 1.0 within the scheduler's clamp band
 * ([RateShaping.meanPreservingFit]).
 *
 * Renormalizing the product matters: each input is individually mean-1.0, but the mean of a
 * product is not the product of means, so the raw product can drift slightly off 1.0 and its
 * extremes can exceed the clamp band. Reshaping restores the per-snapshot mean to exactly 1.0
 * (issue #76) and keeps the curve inside the band so the scheduler's own clamp never has to
 * skew it. (As with the circadian input, the histogram mutates across the day, so the realized
 * daily mean converges to 1.0 as the histogram matures rather than being exact every hour.)
 *
 * Each input degrades to neutral on its own (no persona / Layer 3 off -> persona returns 1.0;
 * too little local data -> circadian returns 1.0), so the composite gracefully reduces to
 * whichever signal is present, or to a flat 1.0 curve when neither is.
 */
@Singleton
class CompositeRateModulator @Inject constructor(
    private val persona: PersonaRateModulator,
    private val circadian: CircadianRateModulator,
) : RateModulator {

    override fun multiplier(hourOfDay: Int): Float {
        if (hourOfDay !in 0 until HOURS_PER_DAY) return 1f
        // Compute each input curve ONCE (circadian shapes its histogram a single time), then
        // combine, rather than re-shaping per hour — keeps this synchronous hot path O(24).
        val circadianCurve = circadian.curve()
        val raw = DoubleArray(HOURS_PER_DAY) {
            persona.multiplier(it).toDouble() * circadianCurve[it].toDouble()
        }
        val shaped = RateShaping.meanPreservingFit(
            raw,
            RateModulator.MIN_MULTIPLIER.toDouble(),
            RateModulator.MAX_MULTIPLIER.toDouble(),
        )
        return shaped[hourOfDay].toFloat()
    }
}
