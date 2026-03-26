package com.fauxx.engine.scheduling

import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.random.Random

/**
 * Generates next-action timestamps following a Poisson process with human-like circadian patterns.
 *
 * Behavioral properties:
 * - Active 7am–11pm local time (configurable via [allowedHoursStart]/[allowedHoursEnd])
 * - Produces bursts of 3-7 actions close together, then gaps of 5-20 minutes
 * - Near-zero activity between 11pm-7am
 * - Inter-arrival times follow exponential distribution (Poisson process property)
 */
@Singleton
class PoissonScheduler @Inject constructor() {

    companion object {
        /** Default quiet hours: 11pm to 7am. */
        const val DEFAULT_QUIET_START = 23
        const val DEFAULT_QUIET_END = 7

    }

    /**
     * Compute the delay in milliseconds until the next action should fire.
     *
     * @param actionsPerHour Target action rate from [com.fauxx.data.model.IntensityLevel].
     * @param allowedStart Hour of day (0-23) when activity may begin.
     * @param allowedEnd Hour of day (0-23) when activity must stop.
     * @return Delay in milliseconds. May be large if currently in quiet hours.
     */
    fun nextDelayMs(
        actionsPerHour: Int,
        allowedStart: Int = DEFAULT_QUIET_END,
        allowedEnd: Int = DEFAULT_QUIET_START
    ): Long {
        val now = Calendar.getInstance()
        val currentHour = now.get(Calendar.HOUR_OF_DAY)

        // If in quiet hours, delay until allowed start
        if (!isWithinAllowedHours(currentHour, allowedStart, allowedEnd)) {
            return msUntilHour(now, allowedStart)
        }

        // Use the target rate directly — actionsPerHour already represents the desired
        // rate during active hours, no need to scale by active fraction.
        val effectiveRate = actionsPerHour.toFloat()

        // Burst-gap behavior: 30% chance of burst mode (short delay), 70% normal
        return if (Random.nextFloat() < 0.30f) {
            // Burst: 2-30 seconds
            Random.nextLong(2_000L, 30_000L)
        } else {
            // Normal: exponential inter-arrival time
            poissonDelay(effectiveRate)
        }
    }

    /**
     * Generate an exponentially-distributed delay matching a Poisson process
     * with rate [actionsPerHour].
     */
    fun poissonDelay(actionsPerHour: Float): Long {
        if (actionsPerHour <= 0f) return 60_000L
        val ratePerMs = actionsPerHour / (60f * 60f * 1000f)
        val u = Random.nextDouble()
        val delayMs = (-ln(1.0 - u) / ratePerMs).toLong()
        // Clamp to reasonable range (1s - 30min)
        return delayMs.coerceIn(1_000L, 30L * 60L * 1000L)
    }

    private fun isWithinAllowedHours(currentHour: Int, start: Int, end: Int): Boolean {
        return if (start <= end) {
            currentHour in start until end
        } else {
            // Wraps midnight: e.g., start=22, end=6 means 22:00 to 06:00
            currentHour >= start || currentHour < end
        }
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
