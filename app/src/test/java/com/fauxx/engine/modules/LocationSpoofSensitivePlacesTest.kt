package com.fauxx.engine.modules

import android.app.AppOpsManager
import android.content.Context
import android.location.LocationManager
import com.fauxx.data.db.LogMetadata
import com.fauxx.data.location.CityCoord
import com.fauxx.data.location.CityDatabase
import com.fauxx.data.location.FakeRouteGenerator
import com.fauxx.data.location.FakeRouteGenerator.MovementMode
import com.fauxx.data.location.LocationAnchors
import com.fauxx.data.location.RoutePoint
import com.fauxx.data.model.PoisonProfile
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.util.Clock
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/**
 * #322 item 12: a spoofed route starts at a point clear of sensitive places and never comes
 * within [LocationAnchors.BUFFER_M] of one. Without such a point the action is skipped, never
 * spoofed elsewhere.
 */
class LocationSpoofSensitivePlacesTest {

    private lateinit var context: Context
    private lateinit var locationManager: LocationManager

    private val rome = CityCoord("Rome, Italy", 41.9028, 12.4964, "WESTERN_EUROPE")
    private val medina = CityCoord("Medina, Saudi Arabia", 24.5247, 39.5692, "MIDDLE_EAST_AFRICA")
    private val tight = LocationAnchors.Anchor(41.91339, 12.48927, 180)
    private val open = LocationAnchors.Anchor(41.96575, 12.50694, 420)

    private class FixedClock : Clock {
        override fun currentTimeMillis() = 1_800_000_000_000L
        override fun elapsedRealtime() = 1_000_000L
    }

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        val appOps = mockk<AppOpsManager>()
        locationManager = mockk(relaxed = true)
        every { context.getSystemService(Context.LOCATION_SERVICE) } returns locationManager
        every { context.getSystemService(Context.APP_OPS_SERVICE) } returns appOps
        every { context.packageName } returns "com.fauxx"
        every { appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, any(), any()) } returns AppOpsManager.MODE_ALLOWED
        every { locationManager.removeTestProvider(any()) } just runs
    }

    private fun anchorsJson(vararg anchors: LocationAnchors.Anchor) =
        """{"cities": {"Rome, Italy": [${anchors.joinToString { "[${it.lat}, ${it.lng}, ${it.clearanceM}]" }}]}}"""

    /** A module whose generator is real; every route it hands the provider is recorded. */
    private fun module(anchors: LocationAnchors, seed: Int, routes: MutableList<List<RoutePoint>>): LocationSpoofModule {
        val random = Random(seed)
        val generator = spyk(FakeRouteGenerator(mockk(), FixedClock(), random))
        every { generator.generateRoute(any(), any(), any(), any(), any()) } answers {
            (callOriginal() as List<RoutePoint>).also { routes += it }
        }
        val cities = mockk<CityDatabase> {
            every { randomEligibleCity(any(), any()) } answers {
                val eligible = secondArg<(CityCoord) -> Boolean>()
                listOf(rome, medina).filter(eligible).randomOrNull(random)
            }
        }
        return LocationSpoofModule(
            context = context,
            routeGenerator = generator,
            cityDatabase = cities,
            anchors = anchors,
            profileRepo = mockk<PoisonProfileRepository>(relaxed = true) { every { getProfile() } returns PoisonProfile() },
            personaLayer = mockk { every { personaForChannel(any()) } returns null },
            random = random,
        )
    }

    @Test
    fun `every fix stays at least the buffer from any sensitive place, in every mode`() = runTest {
        val routes = mutableListOf<List<RoutePoint>>()
        val modes = mutableSetOf<String>()
        for (seed in 1..60) {
            val m = module(LocationAnchors { anchorsJson(tight, open) }, seed, routes)
            m.start()
            val log = m.onAction(CategoryPool.COOKING)
            assertTrue(log.detail, log.success)
            assertTrue(log.detail, log.detail.endsWith("near Rome, Italy"))
            modes += log.detail.substringBefore(" near")
            val clearance = LogMetadata.parse(log.metadata).toMap()[LogMetadata.SENSITIVE_CLEARANCE]!!
            assertTrue(clearance, clearance.removePrefix("at least ").removeSuffix(" m").toInt() >= LocationAnchors.BUFFER_M)
        }
        assertEquals(setOf("STATIONARY", "WALKING", "DRIVING"), modes)
        assertEquals(60, routes.size)
        for (route in routes) {
            // Only Rome's anchors are used, and each fix keeps its anchor's clearance minus the buffer.
            val anchor = listOf(tight, open).single { a -> route.first().let { FakeRouteGenerator.distanceM(a.lat, a.lng, it.latitude, it.longitude) } < 300 }
            for (p in route) {
                assertTrue(FakeRouteGenerator.distanceM(anchor.lat, anchor.lng, p.latitude, p.longitude) <= anchor.reachM)
            }
        }
    }

    @Test
    fun `a point with little room never drives`() = runTest {
        val routes = mutableListOf<List<RoutePoint>>()
        for (seed in 1..40) {
            val m = module(LocationAnchors { anchorsJson(tight) }, seed, routes)
            m.start()
            val log = m.onAction(CategoryPool.COOKING)
            assertTrue(log.success)
            assertFalse(log.detail, log.detail.startsWith(MovementMode.DRIVING.name))
        }
    }

    @Test
    fun `without a clear point, the action is skipped and nothing is spoofed`() = runTest {
        val routes = mutableListOf<List<RoutePoint>>()
        for ((anchors, reason) in listOf(
            LocationAnchors { """{"cities": {}}""" } to "failed to load",
            LocationAnchors { """{"cities": {"Somewhere else": [[1.0, 1.0, 300]]}}""" } to "no city in the persona's region",
        )) {
            val m = module(anchors, 1, routes)
            m.start()
            val log = m.onAction(CategoryPool.COOKING)
            assertFalse(log.success)
            assertTrue(log.detail, log.detail.contains(reason))
        }
        assertTrue(routes.isEmpty())
        verify(exactly = 0) { locationManager.setTestProviderLocation(any(), any()) }
    }
}
