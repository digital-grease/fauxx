package com.fauxx.data.model

/**
 * Controls the rate of synthetic activity generation.
 *
 * @property actionsPerHour Target number of actions per hour in this intensity mode.
 */
enum class IntensityLevel(val actionsPerHour: Int) {
    /** ~12 actions/hour — minimal footprint, suitable for battery-sensitive use. */
    LOW(12),

    /** ~60 actions/hour — balanced poisoning, default setting. */
    MEDIUM(60),

    /** ~200 actions/hour — maximum poisoning intensity (rate-limited at 200/hr). */
    HIGH(200)
}
