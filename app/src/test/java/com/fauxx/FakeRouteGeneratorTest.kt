package com.fauxx

import com.fauxx.data.location.CityCoord
import com.fauxx.data.location.CityDatabase
import com.fauxx.data.location.FakeRouteGenerator
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeRouteGeneratorTest {

    private val cityDatabase: CityDatabase = mockk()
    private val generator = FakeRouteGenerator(cityDatabase)
    private val testCity = CityCoord("Test City", 40.0, -74.0, "US_NORTHEAST")

    @Test
    fun `generates correct number of locations`() {
        every { cityDatabase.randomCity(any()) } returns testCity
        val route = generator.generateRoute(testCity, FakeRouteGenerator.MovementMode.STATIONARY, count = 10)
        assertEquals(10, route.size)
    }

    @Test
    fun `walking speed within valid range`() {
        val route = generator.generateRoute(testCity, FakeRouteGenerator.MovementMode.WALKING, count = 20)
        route.forEach { point ->
            // Walking speed: 0.8 to 2.3 m/s
            assertTrue("Speed ${point.speed} should be in walking range", point.speed in 0f..3f)
        }
    }

    @Test
    fun `driving speed within valid range`() {
        val route = generator.generateRoute(testCity, FakeRouteGenerator.MovementMode.DRIVING, count = 20)
        route.forEach { point ->
            // Driving speed: 8 to 28 m/s (30-100 km/h)
            assertTrue("Speed ${point.speed} should be in driving range", point.speed in 0f..30f)
        }
    }

    @Test
    fun `stationary mode stays near origin`() {
        val route = generator.generateRoute(testCity, FakeRouteGenerator.MovementMode.STATIONARY, count = 20)
        route.forEach { point ->
            val latDiff = Math.abs(point.latitude - testCity.lat)
            val lngDiff = Math.abs(point.longitude - testCity.lng)
            // Should not stray more than ~10 meters (~0.0001 degrees)
            assertTrue("Stationary lat drift $latDiff too large", latDiff < 0.001)
            assertTrue("Stationary lng drift $lngDiff too large", lngDiff < 0.001)
        }
    }

    @Test
    fun `timestamps are monotonically increasing`() {
        val route = generator.generateRoute(testCity, FakeRouteGenerator.MovementMode.WALKING, count = 10, intervalMs = 1000L)
        for (i in 1 until route.size) {
            assertTrue("Timestamps should be increasing", route[i].time > route[i - 1].time)
        }
    }

    @Test
    fun `all locations have valid coordinates`() {
        val route = generator.generateRoute(testCity, FakeRouteGenerator.MovementMode.DRIVING, count = 10)
        route.forEach { point ->
            assertTrue("Latitude out of range", point.latitude in -90.0..90.0)
            assertTrue("Longitude out of range", point.longitude in -180.0..180.0)
        }
    }
}
