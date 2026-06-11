package com.fauxx.engine.scheduling

/**
 * Hook for soft, multiplicative modulation of the Poisson target rate by hour of day
 * (E8 #174). [PoissonScheduler] multiplies its target rate by [multiplier], clamped to
 * [MIN_MULTIPLIER]..[MAX_MULTIPLIER] regardless of the implementation.
 *
 * This is the SINGLE rate-modulation seam: E8 contributes the persona rhythm
 * ([com.fauxx.targeting.layer3.PersonaRateModulator]); E10 (#177) will compose the
 * screen-on circadian histogram into the same hook rather than adding a second
 * modulation point inside the scheduler.
 *
 * Contract: implementations must keep the 24-hour mean multiplier close to 1.0 so the
 * displayed actions/hour stays honest (issue #76) — modulation reshapes WHEN actions
 * happen, not how many. Note that multipliers above 1.0 can be partially clipped by
 * the engine's hard per-hour cap (PoisonEngine.recentActionTimestamps), so realized
 * throughput errs slightly BELOW the displayed rate — the honest direction.
 */
fun interface RateModulator {

    /** Rate multiplier for the given local hour of day (0-23). */
    fun multiplier(hourOfDay: Int): Float

    companion object {
        const val MIN_MULTIPLIER = 0.5f
        const val MAX_MULTIPLIER = 1.5f

        /** Identity modulation: scheduler behavior is exactly pre-E8. */
        val NEUTRAL = RateModulator { 1f }
    }
}
