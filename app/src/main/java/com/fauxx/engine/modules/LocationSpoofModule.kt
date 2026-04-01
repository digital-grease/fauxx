package com.fauxx.engine.modules

import android.content.Context
import android.location.LocationManager
import timber.log.Timber
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.location.CityDatabase
import com.fauxx.data.location.FakeRouteGenerator
import com.fauxx.data.model.ActionType
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.PoisonProfileRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

private const val MOCK_PROVIDER = "fauxx_mock"

/**
 * Manages the MockLocationProvider lifecycle and feeds coordinates from [FakeRouteGenerator].
 * Requires developer options enabled with "Select mock location app" pointing to Fauxx.
 */
@Singleton
class LocationSpoofModule @Inject constructor(
    @ApplicationContext private val context: Context,
    private val routeGenerator: FakeRouteGenerator,
    private val cityDatabase: CityDatabase,
    private val profileRepo: PoisonProfileRepository
) : Module {

    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var mockProviderAdded = false

    @Suppress("DEPRECATION")
    override suspend fun start() {
        try {
            locationManager.addTestProvider(
                MOCK_PROVIDER,
                false, false, false, false, true, true, true,
                android.location.Criteria.POWER_LOW,
                android.location.Criteria.ACCURACY_FINE
            )
            locationManager.setTestProviderEnabled(MOCK_PROVIDER, true)
            mockProviderAdded = true
            Timber.d("Mock location provider started")
        } catch (e: SecurityException) {
            Timber.w("Cannot add mock provider — developer options not enabled")
        } catch (e: Exception) {
            Timber.w("Failed to start mock provider: ${e.message}")
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
                detail = "Skipped: mock provider not enabled",
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
