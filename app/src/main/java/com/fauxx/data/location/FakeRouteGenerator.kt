package com.fauxx.data.location

import android.location.Location
import com.fauxx.util.Clock
import com.fauxx.util.SystemClockImpl
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A plain data representation of a GPS point, free of Android framework dependencies.
 * Use [toLocation] to convert to an [android.location.Location] when needed.
 */
data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
    val speed: Float,
    val accuracy: Float,
    val time: Long,
    val elapsedRealtimeNanos: Long
) {
    /** Convert to an Android [Location] for use with [LocationManager]. */
    fun toLocation(): Location = Location("fauxx_mock").apply {
        latitude = this@RoutePoint.latitude
        longitude = this@RoutePoint.longitude
        this.accuracy = this@RoutePoint.accuracy
        this.speed = this@RoutePoint.speed
        time = this@RoutePoint.time
        elapsedRealtimeNanos = this@RoutePoint.elapsedRealtimeNanos
        provider = "fauxx_mock"
    }
}

/**
 * Generates GPS coordinate sequences along plausible movement paths.
 *
 * Supports three movement modes:
 * - WALKING: 3-5 km/h with gentle direction changes
 * - DRIVING: 30-100 km/h with smoother, road-like paths
 * - STATIONARY: Small GPS jitter around a fixed "home" location
 *
 * Output coordinates form a plausible track that won't trigger "teleportation" detection.
 */
@Singleton
class FakeRouteGenerator @Inject constructor(
    private val cityDatabase: CityDatabase,
    private val clock: Clock = SystemClockImpl(),
    private val random: Random = Random.Default,
) {
    enum class MovementMode { WALKING, DRIVING, STATIONARY }

    /**
     * Generate a sequence of [count] GPS locations starting from [origin] in [mode].
     * Each location has a realistic timestamp and accuracy value.
     *
     * @param origin Starting coordinates. If null, picks a random city.
     * @param mode Movement type, which determines speed and path characteristics.
     * @param count Number of location points to generate.
     * @param intervalMs Milliseconds between each location update.
     * @param maxReachM No point lies farther than this from [origin]. Moving modes slow down to
     *   stay within it. It must be at least [minReachM] for [mode]; Location Spoofing uses it to
     *   keep a route clear of sensitive places (#322 item 12).
     */
    fun generateRoute(
        origin: CityCoord? = null,
        mode: MovementMode = MovementMode.STATIONARY,
        count: Int = 10,
        intervalMs: Long = 5_000L,
        maxReachM: Double = Double.POSITIVE_INFINITY,
    ): List<RoutePoint> {
        require(maxReachM >= minReachM(mode, count, intervalMs)) {
            "a $mode route of $count points needs ${minReachM(mode, count, intervalMs)} m, not $maxReachM"
        }
        // Every point moves once (the first one included), so a route covers count steps. Capping
        // the speed caps the path, and the straight-line distance from the origin is never longer.
        // REACH_MARGIN absorbs the flat-earth step below, which is off by up to 0.4% from a
        // geodesic distance.
        val maxSpeed = (maxReachM * REACH_MARGIN / (count * intervalMs / 1000.0)).toFloat()
        val start = origin ?: cityDatabase.randomCity()
        val points = mutableListOf<RoutePoint>()
        var lat = start.lat
        var lng = start.lng
        var bearing = random.nextDouble(0.0, 360.0)
        val baseTime = clock.currentTimeMillis() - count * intervalMs
        // CLOCK_BOOTTIME, not CLOCK_MONOTONIC: Location.elapsedRealtimeNanos is defined on
        // boot time, which keeps advancing through deep sleep. System.nanoTime() freezes while
        // the device sleeps, so every fix arrived looking as stale as the device's accumulated
        // sleep, which is routinely hours. Consumers compute fix age from this field.
        val baseElapsedNanos =
            clock.elapsedRealtime() * 1_000_000L - count * intervalMs * 1_000_000L

        for (i in 0 until count) {
            val (speed, accuracy) = when (mode) {
                MovementMode.WALKING -> Pair(
                    (random.nextFloat() * 1.5f + WALKING_MIN_SPEED).coerceAtMost(maxSpeed), // 0.8-2.3 m/s (3-8 km/h)
                    random.nextFloat() * 5f + 3f      // 3-8m accuracy
                )
                MovementMode.DRIVING -> Pair(
                    (random.nextFloat() * 20f + DRIVING_MIN_SPEED).coerceAtMost(maxSpeed), // 8-28 m/s (30-100 km/h)
                    random.nextFloat() * 10f + 5f     // 5-15m accuracy
                )
                MovementMode.STATIONARY -> Pair(
                    0f,
                    random.nextFloat() * 8f + 2f      // 2-10m jitter
                )
            }

            // Apply movement, keeping coordinates valid: clamp latitude to [-90, 90] and wrap
            // longitude to [-180, 180] so a route near a pole or across the antimeridian stays
            // a valid GPS fix. Near the poles cos(lat) -> 0, which would blow the east-west
            // degrees-per-metre up toward infinity, so floor the denominator.
            if (mode != MovementMode.STATIONARY) {
                // Gentle bearing changes
                bearing += random.nextDouble(-15.0, 15.0)

                val distanceM = speed * intervalMs / 1000.0
                val deltaLat = distanceM * cos(Math.toRadians(bearing)) / 111_320.0
                val metersPerDegLng = 111_320.0 * maxOf(cos(Math.toRadians(lat)), 0.01)
                val deltaLng = distanceM * sin(Math.toRadians(bearing)) / metersPerDegLng
                lat = (lat + deltaLat).coerceIn(-90.0, 90.0)
                lng = wrapLongitude(lng + deltaLng)
            } else {
                // Stationary jitter
                lat = (lat + random.nextDouble(-JITTER_DEG, JITTER_DEG)).coerceIn(-90.0, 90.0)
                lng = wrapLongitude(lng + random.nextDouble(-JITTER_DEG, JITTER_DEG))
            }

            points.add(
                RoutePoint(
                    latitude = lat,
                    longitude = lng,
                    speed = speed,
                    accuracy = accuracy,
                    time = baseTime + i * intervalMs,
                    elapsedRealtimeNanos = baseElapsedNanos + i * intervalMs * 1_000_000L
                )
            )
        }

        return points
    }

    /** Wrap a longitude into [-180, 180] so an antimeridian crossing stays a valid coordinate. */
    private fun wrapLongitude(lng: Double): Double {
        val x = (lng + 180.0) % 360.0
        return (if (x < 0) x + 360.0 else x) - 180.0
    }

    companion object {
        const val WALKING_MIN_SPEED = 0.8f
        const val DRIVING_MIN_SPEED = 8f

        /** A stationary point jitters by up to this many degrees per step, in latitude and in longitude. */
        private const val JITTER_DEG = 0.00002

        /** One stationary step moves at most this far: [JITTER_DEG] in both axes, at most 111.7 km per degree. */
        private const val JITTER_STEP_M = 3.2

        private const val REACH_MARGIN = 0.98

        /**
         * The smallest reach a [mode] route of [count] points fits in: [count] jitter steps when
         * stationary, otherwise [count] steps at the mode's slowest speed.
         */
        fun minReachM(mode: MovementMode, count: Int, intervalMs: Long): Double {
            val stepSeconds = intervalMs / 1000.0
            return when (mode) {
                MovementMode.STATIONARY -> count * JITTER_STEP_M
                MovementMode.WALKING -> count * stepSeconds * WALKING_MIN_SPEED / REACH_MARGIN
                MovementMode.DRIVING -> count * stepSeconds * DRIVING_MIN_SPEED / REACH_MARGIN
            }
        }

        /** Great-circle distance in metres between two coordinates. */
        fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).let { it * it }
            return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }

        private const val EARTH_RADIUS_M = 6_371_008.8
    }
}
