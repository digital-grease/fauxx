package com.fauxx.engine.modules

import android.app.AppOpsManager
import android.content.Context
import android.location.LocationManager
import com.fauxx.data.location.CityDatabase
import com.fauxx.data.location.FakeRouteGenerator
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.PoisonProfileRepository
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Locks the diagnostic-capture wiring added for issue #48. Without these tests, a
 * regression in start() that re-introduces the silent-failure path (catch + log,
 * no state update) would make ModulesScreen's failure banner go dark again — and
 * users would once more see "Skipped: mock provider not enabled" in their logs
 * with no way to discover why.
 */
class LocationSpoofModuleFailureTest {

    private lateinit var context: Context
    private lateinit var appOps: AppOpsManager
    private lateinit var locationManager: LocationManager
    private lateinit var profileRepo: PoisonProfileRepository
    private lateinit var module: LocationSpoofModule

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        appOps = mockk()
        locationManager = mockk()
        profileRepo = mockk(relaxed = true)
        every { context.getSystemService(Context.LOCATION_SERVICE) } returns locationManager
        every { context.getSystemService(Context.APP_OPS_SERVICE) } returns appOps
        every { context.packageName } returns "com.fauxx"

        module = LocationSpoofModule(
            context = context,
            routeGenerator = mockk(relaxed = true),
            cityDatabase = mockk(relaxed = true),
            profileRepo = profileRepo
        )
    }

    @Test
    fun `initial state is NEVER_STARTED before start runs`() {
        assertEquals(
            LocationDiagnostics.StartFailure.NEVER_STARTED,
            module.lastStartFailure.value
        )
    }

    @Test
    fun `start with mock-app op disallowed sets NOT_MOCK_APP and onAction reflects it`() = runBlocking {
        every {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, any(), any())
        } returns AppOpsManager.MODE_IGNORED

        module.start()

        assertEquals(
            LocationDiagnostics.StartFailure.NOT_MOCK_APP,
            module.lastStartFailure.value
        )
        val log = module.onAction(CategoryPool.BEAUTY)
        assertFalse(log.success)
        assertTrue(
            "Expected detail to call out the mock-app gap, got: ${log.detail}",
            log.detail.contains("not selected as mock location app", ignoreCase = true)
        )
    }

    @Test
    fun `start with SecurityException from addTestProvider sets SECURITY_EXCEPTION`() = runBlocking {
        every {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, any(), any())
        } returns AppOpsManager.MODE_ALLOWED
        every {
            locationManager.addTestProvider(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } throws SecurityException("mock_location_app_op_denied")

        module.start()

        assertEquals(
            LocationDiagnostics.StartFailure.SECURITY_EXCEPTION,
            module.lastStartFailure.value
        )
        val log = module.onAction(CategoryPool.BEAUTY)
        assertFalse(log.success)
        assertTrue(
            "Expected detail to mention restart, got: ${log.detail}",
            log.detail.contains("restart", ignoreCase = true)
        )
    }

    @Test
    fun `start with non-Security exception sets RUNTIME_EXCEPTION`() = runBlocking {
        every {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, any(), any())
        } returns AppOpsManager.MODE_ALLOWED
        every {
            locationManager.addTestProvider(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } throws IllegalArgumentException("provider already exists")

        module.start()

        assertEquals(
            LocationDiagnostics.StartFailure.RUNTIME_EXCEPTION,
            module.lastStartFailure.value
        )
    }

    @Test
    fun `successful start transitions to OK`() = runBlocking {
        every {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, any(), any())
        } returns AppOpsManager.MODE_ALLOWED
        every {
            locationManager.addTestProvider(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } just runs
        every {
            locationManager.setTestProviderEnabled(any(), any())
        } just runs

        module.start()

        assertEquals(LocationDiagnostics.StartFailure.OK, module.lastStartFailure.value)
    }
}
