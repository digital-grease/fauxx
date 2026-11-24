package com.fauxx.data.location

import android.content.Context
import com.fauxx.data.location.FakeRouteGenerator.MovementMode
import com.fauxx.util.Clock
import com.google.gson.JsonParser
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.random.Random

/** Where Location Spoofing may put the decoy (#322 item 12): clear of every sensitive place. */
class LocationAnchorsTest {

    // --- reading the asset ---

    @Test
    fun `anchors are read per city, and an anchor without room beyond the buffer is dropped`() {
        val anchors = LocationAnchors {
            """{"cities": {"Rome, Italy": [[41.91339, 12.48927, 240], [41.8731, 12.49681, 150]], "Nowhere": [[0, 0, 100]]}}"""
        }
        val rome = CityCoord("Rome, Italy", 41.9028, 12.4964, "WESTERN_EUROPE")
        assertTrue(anchors.covers(rome))
        assertFalse("its only anchor leaves no room", anchors.covers(CityCoord("Nowhere", 0.0, 0.0)))
        assertFalse(anchors.covers(CityCoord("Paris, France", 48.8566, 2.3522)))
        assertFalse(anchors.loadFailed)
        repeat(20) { assertEquals(LocationAnchors.Anchor(41.91339, 12.48927, 240), anchors.pick(rome, Random(it))) }
        assertEquals(90.0, LocationAnchors.Anchor(0.0, 0.0, 240).reachM, 0.0)
        assertNull(anchors.pick(CityCoord("Paris, France", 48.8566, 2.3522), Random(1)))
    }

    @Test
    fun `a missing or unreadable asset covers no city`() {
        for (anchors in listOf(LocationAnchors { error("no asset") }, LocationAnchors { "not json" })) {
            assertTrue(anchors.loadFailed)
            assertFalse(anchors.covers(CityCoord("Rome, Italy", 41.9028, 12.4964)))
        }
    }

    // --- picking a city ---

    private val cities = listOf(
        CityCoord("Seattle, United States", 47.6062, -122.3321, "US_WEST"),
        CityCoord("Portland, United States", 45.5152, -122.6784, "US_WEST"),
        CityCoord("Medina, Saudi Arabia", 24.5247, 39.5692, "MIDDLE_EAST_AFRICA"),
        CityCoord("Rome, Italy", 41.9028, 12.4964, "WESTERN_EUROPE"),
    )

    private fun database(random: Random): CityDatabase {
        val json = com.google.gson.Gson().toJson(cities).toByteArray()
        val context = mockk<Context>(relaxed = true) {
            every { assets } returns mockk { every { open("city_coords.json") } answers { ByteArrayInputStream(json) } }
        }
        return CityDatabase(context, random)
    }

    @Test
    fun `an eligible city comes from the persona's region, and never from another region instead`() {
        val db = database(Random(3))
        val eligible = { c: CityCoord -> c.name != "Medina, Saudi Arabia" && c.name != "Portland, United States" }
        repeat(30) { assertEquals("Seattle, United States", db.randomEligibleCity("US_WEST", eligible)?.name) }
        assertNull("no fallback to other regions", db.randomEligibleCity("MIDDLE_EAST_AFRICA", eligible))
        val anywhere = (1..60).mapNotNull { db.randomEligibleCity(null, eligible)?.name }.toSet()
        assertEquals(setOf("Seattle, United States", "Rome, Italy"), anywhere)
        val unknown = (1..60).mapNotNull { db.randomEligibleCity("NO_SUCH_REGION", eligible)?.name }.toSet()
        assertEquals("an unknown region draws from every city, as randomCity does", anywhere, unknown)
        assertNull(db.randomEligibleCity(null) { false })
    }

    // --- keeping a route within reach ---

    private class FixedClock : Clock {
        override fun currentTimeMillis() = 1_800_000_000_000L
        override fun elapsedRealtime() = 1_000_000L
    }

    @Test
    fun `routes never stray beyond the reach they are given, in any mode that fits`() {
        val origin = CityCoord("Oslo, Norway", 59.9139, 10.7522)
        for (seed in 1..200) {
            val random = Random(seed)
            val generator = FakeRouteGenerator(mockk(), FixedClock(), random)
            val reach = 16.0 + random.nextDouble() * 600.0
            for (mode in MovementMode.values().filter { FakeRouteGenerator.minReachM(it, 5, 5_000L) <= reach }) {
                val route = generator.generateRoute(origin, mode, count = 5, intervalMs = 5_000L, maxReachM = reach)
                assertEquals(5, route.size)
                for (p in route) {
                    val d = FakeRouteGenerator.distanceM(origin.lat, origin.lng, p.latitude, p.longitude)
                    assertTrue("$mode point $d m from origin, reach $reach", d <= reach)
                }
            }
        }
    }

    @Test
    fun `a moving route slows to fit its reach, and one that cannot fit is refused`() {
        val origin = CityCoord("Oslo, Norway", 59.9139, 10.7522)
        val generator = FakeRouteGenerator(mockk(), FixedClock(), Random(5))
        val drive = generator.generateRoute(origin, MovementMode.DRIVING, count = 5, intervalMs = 5_000L, maxReachM = 220.0)
        assertTrue(drive.all { it.speed in FakeRouteGenerator.DRIVING_MIN_SPEED..(220f / 25f) })
        assertEquals(16.0, FakeRouteGenerator.minReachM(MovementMode.STATIONARY, 5, 5_000L), 0.0)
        assertThrows(IllegalArgumentException::class.java) {
            generator.generateRoute(origin, MovementMode.DRIVING, count = 5, intervalMs = 5_000L, maxReachM = 100.0)
        }
        // Unbounded by default, as before #322 item 12.
        val free = generator.generateRoute(origin, MovementMode.DRIVING, count = 5, intervalMs = 5_000L)
        assertTrue(free.all { it.speed in 8f..28f })
    }

    @Test
    fun `distances are great-circle metres`() {
        // One degree of latitude is about 111.2 km; Paris to London is about 344 km.
        assertEquals(111_195.0, FakeRouteGenerator.distanceM(0.0, 0.0, 1.0, 0.0), 1.0)
        assertEquals(343_500.0, FakeRouteGenerator.distanceM(48.8566, 2.3522, 51.5074, -0.1278), 1_000.0)
    }

    // --- the shipped asset ---

    private val assets = File("src/main/assets")
    private val asset by lazy { JsonParser.parseString(File(assets, LocationAnchors.ASSET_PATH).readText()).asJsonObject }
    private val shippedCities: List<CityCoord> by lazy {
        com.google.gson.Gson().fromJson(File(assets, "city_coords.json").readText(), Array<CityCoord>::class.java).toList()
    }

    @Test
    fun `the shipped anchors keep the app's buffer, leave room to stand still, and lie near their cities`() {
        assertTrue("run from the app module root", assets.exists())
        assertEquals(LocationAnchors.BUFFER_M, asset.get("buffer_m").asInt)
        val radius = asset.get("radius_m").asDouble
        val byName = shippedCities.associateBy { it.name }
        val minClearance = LocationAnchors.BUFFER_M + FakeRouteGenerator.minReachM(MovementMode.STATIONARY, 5, 5_000L)
        for ((name, points) in asset.getAsJsonObject("cities").entrySet()) {
            val city = byName[name]
            assertTrue("$name is not in city_coords.json", city != null)
            val anchors = points.asJsonArray.map { it.asJsonArray }.map { LocationAnchors.Anchor(it[0].asDouble, it[1].asDouble, it[2].asInt) }
            assertTrue(name, anchors.isNotEmpty() && anchors.size <= 12)
            for (a in anchors) {
                assertTrue("$name $a", a.clearanceM >= minClearance && a.clearanceM <= 700)
                val d = FakeRouteGenerator.distanceM(city!!.lat, city.lng, a.lat, a.lng)
                assertTrue("$name anchor $d m from the city", d <= radius + 50)
            }
            for ((i, a) in anchors.withIndex()) for (b in anchors.drop(i + 1)) {
                assertTrue("$name anchors too close", FakeRouteGenerator.distanceM(a.lat, a.lng, b.lat, b.lng) >= 390)
            }
        }
    }

    @Test
    fun `every city is either covered or left out with a reason`() {
        val covered = asset.getAsJsonObject("cities").keySet()
        val leftOut = asset.getAsJsonObject("left_out").entrySet().associate { it.key to it.value.asString }
        assertTrue(covered.intersect(leftOut.keys).isEmpty())
        assertEquals(shippedCities.map { it.name }.toSet(), covered + leftOut.keys)
        assertTrue(leftOut.values.all { it.isNotBlank() })
        // The real loader reads every covered city.
        val loaded = LocationAnchors { File(assets, LocationAnchors.ASSET_PATH).readText() }
        assertTrue(shippedCities.filter { it.name in covered }.all(loaded::covers))
        assertTrue(shippedCities.filter { it.name in leftOut }.none(loaded::covers))
    }

    @Test
    fun `every region a persona can be given keeps a covered city`() {
        // Regions come from the persona templates and the persona distribution, plus the
        // generator's fallback list. A region that matches no city at all draws from every city.
        val sources = listOf(File(assets, "persona_templates.json"), File(assets, "persona_distribution.json")) +
            (File(assets, "persona_templates").listFiles()?.toList() ?: emptyList())
        val regions = sources.flatMap { Regex("\"region\"\\s*:\\s*\"([A-Z_]+)\"").findAll(it.readText()).map { m -> m.groupValues[1] } }
            .toSet() + setOf("US_NORTHEAST", "US_SOUTHEAST", "US_MIDWEST", "US_SOUTHWEST", "US_WEST", "CANADA", "UK", "WESTERN_EUROPE")
        assertTrue(regions.size > 10)
        val covered = asset.getAsJsonObject("cities").keySet()
        for (region in regions) {
            val inRegion = shippedCities.filter { it.region.contains(region, ignoreCase = true) }
            if (inRegion.isEmpty()) continue
            assertTrue("$region has no covered city among ${inRegion.map { it.name }}", inRegion.any { it.name in covered })
        }
    }

    @Test
    fun `the categories cover the FTC orders' sensitive locations and item 7's sensitive purchases`() {
        val categories = asset.getAsJsonObject("categories").keySet()
        val orders = setOf("medical", "religious", "correctional", "union", "minors", "lgbtq", "ethnic", "shelter_social", "military_police")
        val purchases = setOf("pharmacy", "death", "alcohol", "gambling", "adult", "cannabis_tobacco", "legal", "lending", "political", "guns", "baby")
        assertTrue(categories.containsAll(orders + purchases))
    }
}
