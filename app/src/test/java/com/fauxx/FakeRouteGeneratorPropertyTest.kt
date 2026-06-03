package com.fauxx

import com.fauxx.data.location.CityCoord
import com.fauxx.data.location.CityDatabase
import com.fauxx.data.location.FakeRouteGenerator
import com.fauxx.support.seededRandom
import com.fauxx.util.SystemClockImpl
import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property test for [FakeRouteGenerator]: every generated point must be a valid GPS fix
 * (lat in [-90, 90], lng in [-180, 180], never NaN/Inf) with monotonic timestamps, across
 * seeded RNG, all movement modes, and origins INCLUDING the poles and the antimeridian — the
 * edges where an unnormalized `lat += / lng +=` drifts out of range or blows up as
 * cos(lat) -> 0. A route is a mock GPS fix fed to the system provider, so an invalid
 * coordinate is both implausible and potentially rejected by Android.
 */
class FakeRouteGeneratorPropertyTest {

    private fun generator(seed: Long): FakeRouteGenerator =
        FakeRouteGenerator(mockk<CityDatabase>(relaxed = true), SystemClockImpl(), seededRandom(seed))

    @Test
    fun `route points are valid coordinates with monotonic timestamps`() = runBlocking<Unit> {
        val latArb = Arb.double(-90.0, 90.0)
        val lngArb = Arb.double(-180.0, 180.0)
        val modeArb = Arb.element(FakeRouteGenerator.MovementMode.values().toList())
        val countArb = Arb.int(2, 40)

        checkAll(500, Arb.long(), latArb, lngArb, modeArb, countArb) { seed, lat, lng, mode, count ->
            val route = generator(seed)
                .generateRoute(CityCoord("T", lat, lng, "X"), mode, count, intervalMs = 5_000L)

            route.forEach { p ->
                assertFalse(
                    "NaN/Inf coordinate produced: $p (origin lat=$lat lng=$lng mode=$mode seed=$seed)",
                    p.latitude.isNaN() || p.latitude.isInfinite() ||
                        p.longitude.isNaN() || p.longitude.isInfinite()
                )
                assertTrue("latitude out of range: ${p.latitude}", p.latitude in -90.0..90.0)
                assertTrue("longitude out of range: ${p.longitude}", p.longitude in -180.0..180.0)
            }
            route.zipWithNext { a, b ->
                assertTrue("timestamps must be monotonic: ${a.time} -> ${b.time}", b.time >= a.time)
            }
        }
    }
}
