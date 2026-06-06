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

    /** ~200 actions/hour — aggressive poisoning. Shortens the cross-topic dwell floor to
     *  reach this rate, which is a more distinguishable browsing pattern than LOW/MEDIUM. */
    HIGH(200),

    /** Up to ~500 actions/hour — highest volume. Drops the cross-topic dwell floor to ~5s,
     *  so throughput climbs but the activity is the easiest of all tiers for a tracker to
     *  tell apart from real browsing. */
    EXTREME(500)
}
