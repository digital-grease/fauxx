package com.fauxx.data.location

import android.location.Location
import com.fauxx.util.Clock
import com.fauxx.util.SystemClockImpl
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.sin
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
     */
    fun generateRoute(
        origin: CityCoord? = null,
        mode: MovementMode = MovementMode.STATIONARY,
        count: Int = 10,
        intervalMs: Long = 5_000L
    ): List<RoutePoint> {
        val start = origin ?: cityDatabase.randomCity()
        val points = mutableListOf<RoutePoint>()
        var lat = start.lat
        var lng = start.lng
        var bearing = random.nextDouble(0.0, 360.0)
        val baseTime = clock.currentTimeMillis() - count * intervalMs
        val baseElapsedNanos = System.nanoTime() - count * intervalMs * 1_000_000L

        for (i in 0 until count) {
            val (speed, accuracy) = when (mode) {
                MovementMode.WALKING -> Pair(
                    random.nextFloat() * 1.5f + 0.8f, // 0.8-2.3 m/s (3-8 km/h)
                    random.nextFloat() * 5f + 3f      // 3-8m accuracy
                )
                MovementMode.DRIVING -> Pair(
                    random.nextFloat() * 20f + 8f,    // 8-28 m/s (30-100 km/h)
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
                lat = (lat + random.nextDouble(-0.00002, 0.00002)).coerceIn(-90.0, 90.0)
                lng = wrapLongitude(lng + random.nextDouble(-0.00002, 0.00002))
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
}
