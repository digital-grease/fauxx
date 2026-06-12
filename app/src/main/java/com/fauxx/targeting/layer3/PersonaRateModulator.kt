package com.fauxx.targeting.layer3

import com.fauxx.engine.scheduling.RateModulator
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos

/** Peak-activity hours sit in BASE_PEAK_HOUR ± PEAK_JITTER_HOURS, persona-keyed. */
private const val BASE_PEAK_HOUR = 14
private const val PEAK_JITTER_HOURS = 3

/** Cosine amplitude: hourly rate swings ±20% around the mean. */
private const val AMPLITUDE = 0.2

/**
 * E8 (#174): gives each synthetic persona a consistent daily activity rhythm.
 *
 * Identical Poisson timing across persona rotations is itself a fingerprint — every
 * "different person" this device pretends to be would keep exactly the same hourly
 * activity profile. This modulator derives a peak-activity hour deterministically from
 * the persona id, so a given persona keeps the same rhythm for its whole 7±3 day life
 * and the rhythm shifts when the persona rotates.
 *
 * Shape: cosine over the 24h day, peak at 14:00 ± 3h (persona-keyed), amplitude ±20%.
 * The mean over 24 hours is exactly 1.0, honoring the [RateModulator] honesty contract
 * (issue #76). Returns 1.0 (neutral) when Layer 3 is disabled or no persona is active,
 * via [PersonaRotationLayer.personaForChannel] for [PersonaChannel.RHYTHM].
 */
@Singleton
class PersonaRateModulator @Inject constructor(
    private val personaLayer: PersonaRotationLayer,
) : RateModulator {

    override fun multiplier(hourOfDay: Int): Float {
        val persona = personaLayer.personaForChannel(PersonaChannel.RHYTHM) ?: return 1f
        val peakHour = BASE_PEAK_HOUR + peakOffset(persona.id)
        val radians = 2.0 * PI * (hourOfDay - peakHour) / 24.0
        return (1.0 + AMPLITUDE * cos(radians)).toFloat()
    }

    /** Deterministic per-persona offset in -PEAK_JITTER_HOURS..+PEAK_JITTER_HOURS. */
    private fun peakOffset(personaId: String): Int {
        val span = 2 * PEAK_JITTER_HOURS + 1
        return (personaId.hashCode().mod(span)) - PEAK_JITTER_HOURS
    }
}
