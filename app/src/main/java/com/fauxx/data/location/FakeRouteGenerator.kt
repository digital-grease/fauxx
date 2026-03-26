package com.fauxx.data.location

import android.location.Location
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
    private val cityDatabase: CityDatabase
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
        var bearing = Random.nextDouble(0.0, 360.0)
        val baseTime = System.currentTimeMillis() - count * intervalMs
        val baseElapsedNanos = System.nanoTime() - count * intervalMs * 1_000_000L

        for (i in 0 until count) {
            val (speed, accuracy) = when (mode) {
                MovementMode.WALKING -> Pair(
                    Random.nextFloat() * 1.5f + 0.8f, // 0.8-2.3 m/s (3-8 km/h)
                    Random.nextFloat() * 5f + 3f      // 3-8m accuracy
                )
                MovementMode.DRIVING -> Pair(
                    Random.nextFloat() * 20f + 8f,    // 8-28 m/s (30-100 km/h)
                    Random.nextFloat() * 10f + 5f     // 5-15m accuracy
                )
                MovementMode.STATIONARY -> Pair(
                    0f,
                    Random.nextFloat() * 8f + 2f      // 2-10m jitter
                )
            }

            // Apply movement
            if (mode != MovementMode.STATIONARY) {
                // Gentle bearing changes
                bearing += Random.nextDouble(-15.0, 15.0)

                val distanceM = speed * intervalMs / 1000.0
                val deltaLat = distanceM * cos(Math.toRadians(bearing)) / 111_320.0
                val deltaLng = distanceM * sin(Math.toRadians(bearing)) /
                    (111_320.0 * cos(Math.toRadians(lat)))
                lat += deltaLat
                lng += deltaLng
            } else {
                // Stationary jitter
                lat += Random.nextDouble(-0.00002, 0.00002)
                lng += Random.nextDouble(-0.00002, 0.00002)
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
}
