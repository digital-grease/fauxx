package com.fauxx.engine.scheduling

/**
 * Hook for soft, multiplicative modulation of the engine's activity by hour of day (E8 #174).
 * [SessionScheduler] divides its breaks between sessions by [multiplier], clamped to
 * [MIN_MULTIPLIER]..[MAX_MULTIPLIER] regardless of the implementation, so busy hours get shorter
 * breaks and quiet hours longer ones (#322).
 *
 * This is the SINGLE rate-modulation seam: E8 contributes the persona rhythm
 * ([com.fauxx.targeting.layer3.PersonaRateModulator]); E10 (#177) will compose the
 * screen-on circadian histogram into the same hook rather than adding a second
 * modulation point inside the scheduler.
 *
 * Contract: implementations must keep the 24-hour mean multiplier close to 1.0:
 * modulation reshapes WHEN actions happen, not how many. The displayed actions/hour stays
 * honest (issue #76) whatever the multiplier, because the scheduler's action budget
 * ([ActionBudget]) pays for every action at the rate itself: a short break cannot spend
 * more than has accrued.
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
