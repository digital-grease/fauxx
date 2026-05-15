package com.fauxx.engine.modules

import android.app.AppOpsManager
import android.content.Context
import android.location.LocationManager
import android.os.Process
import timber.log.Timber
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.location.CityDatabase
import com.fauxx.data.location.FakeRouteGenerator
import com.fauxx.data.model.ActionType
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.PoisonProfileRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val MOCK_PROVIDER = "fauxx_mock"

/**
 * Manages the MockLocationProvider lifecycle and feeds coordinates from [FakeRouteGenerator].
 * Requires developer options enabled with "Select mock location app" pointing to Fauxx.
 *
 * Implements [LocationDiagnostics] so the UI can surface why start() failed (the silent
 * failure was the user-visible symptom in issue #48).
 */
@Singleton
class LocationSpoofModule @Inject constructor(
    @ApplicationContext private val context: Context,
    private val routeGenerator: FakeRouteGenerator,
    private val cityDatabase: CityDatabase,
    private val profileRepo: PoisonProfileRepository
) : Module, LocationDiagnostics {

    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val appOpsManager: AppOpsManager =
        context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    private var mockProviderAdded = false

    private val _lastStartFailure =
        MutableStateFlow(LocationDiagnostics.StartFailure.NEVER_STARTED)
    override val lastStartFailure: StateFlow<LocationDiagnostics.StartFailure> =
        _lastStartFailure.asStateFlow()

    /**
     * Returns true if the user has selected this app as the mock-location app in
     * Developer Options. The AppOp `OPSTR_MOCK_LOCATION` is the authoritative gate
     * for [LocationManager.addTestProvider] from API 23 onwards — checking it
     * proactively lets us tell the user *why* spoofing failed instead of catching
     * an opaque SecurityException.
     */
    private fun isMockLocationAppOpAllowed(): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val mode = appOpsManager.checkOpNoThrow(
                AppOpsManager.OPSTR_MOCK_LOCATION,
                Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            // Fall through if the check itself fails — let addTestProvider's outcome
            // be the signal rather than blocking on a faulty pre-check.
            Timber.w(e, "OPSTR_MOCK_LOCATION check threw, attempting addTestProvider anyway")
            true
        }
    }

    @Suppress("DEPRECATION", "WrongConstant")
    override suspend fun start() {
        if (!isMockLocationAppOpAllowed()) {
            mockProviderAdded = false
            _lastStartFailure.value = LocationDiagnostics.StartFailure.NOT_MOCK_APP
            Timber.w("Fauxx is not selected as mock-location app in Developer Options")
            return
        }
        try {
            locationManager.addTestProvider(
                MOCK_PROVIDER,
                false, false, false, false, true, true, true,
                android.location.Criteria.POWER_LOW,
                android.location.Criteria.ACCURACY_FINE
            )
            locationManager.setTestProviderEnabled(MOCK_PROVIDER, true)
            mockProviderAdded = true
            _lastStartFailure.value = LocationDiagnostics.StartFailure.OK
            Timber.d("Mock location provider started")
        } catch (e: SecurityException) {
            mockProviderAdded = false
            _lastStartFailure.value = LocationDiagnostics.StartFailure.SECURITY_EXCEPTION
            Timber.w(e, "addTestProvider threw SecurityException despite AppOp allowed")
        } catch (e: Exception) {
            mockProviderAdded = false
            _lastStartFailure.value = LocationDiagnostics.StartFailure.RUNTIME_EXCEPTION
            Timber.w(e, "Failed to start mock provider: ${e.message}")
        }
    }

    override suspend fun stop() {
        if (mockProviderAdded) {
            runCatching { locationManager.removeTestProvider(MOCK_PROVIDER) }
            mockProviderAdded = false
        }
    }

    override fun isEnabled(): Boolean = profileRepo.getProfile().locationSpoofEnabled

    override suspend fun onAction(category: CategoryPool): ActionLogEntity {
        if (!mockProviderAdded) {
            return ActionLogEntity(
                actionType = ActionType.LOCATION_SPOOF,
                category = category,
                detail = _lastStartFailure.value.toLogDetail(),
                success = false
            )
        }

        val mode = FakeRouteGenerator.MovementMode.values().random()
        val city = cityDatabase.randomCity()
        val route = routeGenerator.generateRoute(origin = city, mode = mode, count = 5)

        var lastTime = route.firstOrNull()?.time ?: 0L
        for (point in route) {
            try {
                locationManager.setTestProviderLocation(MOCK_PROVIDER, point.toLocation())
                // Inter-point delay, clamped to [500ms, 30s] to avoid CPU burn or excessive blocking
                val interDelay = (point.time - lastTime).coerceIn(500L, 30_000L)
                lastTime = point.time
                delay(interDelay)
            } catch (e: Exception) {
                Timber.w("Failed to set mock location: ${e.message}")
            }
        }

        return ActionLogEntity(
            actionType = ActionType.LOCATION_SPOOF,
            category = category,
            detail = "${mode.name} near ${city.name}"
        )
    }
}

private fun LocationDiagnostics.StartFailure.toLogDetail(): String = when (this) {
    LocationDiagnostics.StartFailure.NEVER_STARTED -> "Skipped: location module not started yet"
    LocationDiagnostics.StartFailure.OK -> "Skipped: mock provider not enabled"
    LocationDiagnostics.StartFailure.NOT_MOCK_APP ->
        "Skipped: Fauxx not selected as mock location app in Developer Options"
    LocationDiagnostics.StartFailure.SECURITY_EXCEPTION ->
        "Skipped: SecurityException registering mock provider — restart Fauxx"
    LocationDiagnostics.StartFailure.RUNTIME_EXCEPTION ->
        "Skipped: registering mock provider failed (see app logs)"
}
