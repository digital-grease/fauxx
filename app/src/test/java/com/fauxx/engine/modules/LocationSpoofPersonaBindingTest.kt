package com.fauxx.engine.modules

import android.app.AppOpsManager
import android.content.Context
import android.location.LocationManager
import com.fauxx.data.location.CityCoord
import com.fauxx.data.location.CityDatabase
import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.targeting.layer3.PersonaRotationLayer
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * E8 (#174): spoofed locations must be drawn from the active persona's region so the
 * location story corroborates the demographic one — and must stay unbound when Layer 3
 * is off (activePersona == null).
 */
class LocationSpoofPersonaBindingTest {

    private lateinit var context: Context
    private lateinit var locationManager: LocationManager
    private lateinit var cityDatabase: CityDatabase

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        val appOps = mockk<AppOpsManager>()
        locationManager = mockk(relaxed = true)
        every { context.getSystemService(Context.LOCATION_SERVICE) } returns locationManager
        every { context.getSystemService(Context.APP_OPS_SERVICE) } returns appOps
        every { context.packageName } returns "com.fauxx"
        every {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, any(), any())
        } returns AppOpsManager.MODE_ALLOWED
        every { locationManager.removeTestProvider(any()) } just runs

        cityDatabase = mockk {
            every { randomCity(any()) } returns CityCoord("Seattle", 47.6062, -122.3321, "US_WEST")
        }
    }

    private fun module(persona: SyntheticPersona?) = LocationSpoofModule(
        context = context,
        routeGenerator = mockk(relaxed = true),
        cityDatabase = cityDatabase,
        profileRepo = mockk<PoisonProfileRepository>(relaxed = true),
        personaLayer = mockk { every { personaForChannel(any()) } returns persona }
    )

    @Test
    fun `onAction passes the active persona region as the city hint`() = runBlocking {
        val persona = SyntheticPersona(
            id = "p1", name = "Test", ageRange = "AGE_25_34", profession = "ENGINEER",
            region = "US_WEST", interests = setOf(CategoryPool.COOKING),
            activeUntil = Long.MAX_VALUE
        )
        val m = module(persona)
        m.start()

        val result = m.onAction(CategoryPool.COOKING)

        verify(exactly = 1) { cityDatabase.randomCity(regionHint = "US_WEST") }
        assertTrue(result.detail.contains("Seattle"))
    }

    @Test
    fun `onAction stays unbound when no persona is active`() = runBlocking {
        val m = module(persona = null)
        m.start()

        m.onAction(CategoryPool.COOKING)

        verify(exactly = 1) { cityDatabase.randomCity(regionHint = null) }
    }
}
