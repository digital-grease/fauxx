package com.fauxx.engine.modules

import android.app.AppOpsManager
import android.content.Context
import android.location.LocationManager
import android.os.Process
import timber.log.Timber
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.db.LogMetadata
import com.fauxx.data.location.CityCoord
import com.fauxx.data.location.CityDatabase
import com.fauxx.data.location.FakeRouteGenerator
import com.fauxx.data.location.LocationAnchors
import com.fauxx.data.model.ActionType
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.targeting.layer3.PersonaChannel
import com.fauxx.targeting.layer3.PersonaRotationLayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

private const val MOCK_PROVIDER = MOCK_LOCATION_PROVIDER

/**
 * Manages the MockLocationProvider lifecycle and feeds coordinates from [FakeRouteGenerator].
 * Requires developer options enabled with "Select mock location app" pointing to Fauxx.
 *
 * Implements [LocationDiagnostics] so the UI can surface why start() failed (the silent
 * failure was the user-visible symptom in issue #48).
 *
 * **Sensitive places (#322 item 12).** A route starts at one of [LocationAnchors]' points for a
 * city in the persona's region, and every fix stays at least [LocationAnchors.BUFFER_M] from any
 * sensitive place: medical facilities, places of worship, schools, shelters and the other places
 * the FTC's location-data orders list, plus the sensitive purchases the crawl list avoids. A fake
 * visit there would plant a sensitive inference on the real user. If no city in the persona's
 * region has a clear point, or the list failed to load, the action is skipped rather than spoofed
 * anywhere else.
 */
@Singleton
class LocationSpoofModule @Inject constructor(
    @ApplicationContext private val context: Context,
    private val routeGenerator: FakeRouteGenerator,
    private val cityDatabase: CityDatabase,
    private val anchors: LocationAnchors,
    private val profileRepo: PoisonProfileRepository,
    private val personaLayer: PersonaRotationLayer,
    private val random: Random = Random.Default,
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
    override fun isMockLocationAppOpAllowed(): Boolean {
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

    /** Diagnostics-driven trigger so the UI can re-run start() outside the engine loop. */
    override suspend fun requestStart() = start()

    @Suppress("DEPRECATION", "WrongConstant")
    override suspend fun start() {
        if (!isMockLocationAppOpAllowed()) {
            mockProviderAdded = false
            _lastStartFailure.value = LocationDiagnostics.StartFailure.NOT_MOCK_APP
            Timber.w("Fauxx is not selected as mock-location app in Developer Options")
            return
        }
        // Clear any stale test provider left behind by a previous session that didn't
        // tear down cleanly — happens on Samsung devices where the OEM battery manager
        // kills the FGS process before stop() can run, and on Android 8 where
        // removeTestProvider doesn't always survive certain lifecycle paths. Without
        // this pre-remove, addTestProvider throws IllegalArgumentException("Provider
        // 'fauxx_mock' already exists") and the user sees "android refused the mock
        // provider for an unexpected reason." See issue #66.
        runCatching { locationManager.removeTestProvider(MOCK_PROVIDER) }
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

        // E8 (#174): bind spoofed locations to the active persona's region so the
        // location story corroborates the demographic one. Null persona (Layer 3 off,
        // or none generated yet) keeps the unbound any-city behavior; an unmatched
        // region hint falls back to the full list inside randomEligibleCity.
        val persona = personaLayer.personaForChannel(PersonaChannel.LOCATION)
        // #322 item 12: only a city with a point clear of sensitive places, and never one
        // outside the persona's region instead.
        val city = cityDatabase.randomEligibleCity(regionHint = persona?.region, eligible = anchors::covers)
        val anchor = city?.let { anchors.pick(it, random) }
        if (city == null || anchor == null) {
            return skipped(
                category,
                if (anchors.loadFailed) "Skipped: the list of places clear of sensitive locations failed to load"
                else "Skipped: no city in the persona's region has a place clear of sensitive locations",
            )
        }
        val modes = FakeRouteGenerator.MovementMode.values().filter {
            FakeRouteGenerator.minReachM(it, ROUTE_POINTS, ROUTE_INTERVAL_MS) <= anchor.reachM
        }
        if (modes.isEmpty()) return skipped(category, "Skipped: no room for a route clear of sensitive locations")
        val mode = modes.random(random)
        val route = routeGenerator.generateRoute(
            origin = CityCoord(city.name, anchor.lat, anchor.lng, city.region),
            mode = mode,
            count = ROUTE_POINTS,
            intervalMs = ROUTE_INTERVAL_MS,
            maxReachM = anchor.reachM,
        )
        // The generator keeps within reach by construction; checked again so a fix near a
        // sensitive place can never reach the provider.
        val farthestM = route.maxOfOrNull {
            FakeRouteGenerator.distanceM(anchor.lat, anchor.lng, it.latitude, it.longitude)
        } ?: 0.0
        if (farthestM > anchor.reachM) {
            Timber.w("Route strayed %.0f m from its anchor, reach %.0f m; not spoofed", farthestM, anchor.reachM)
            return skipped(category, "Skipped: route strayed too near a sensitive location")
        }

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

        val durationMs = (route.lastOrNull()?.time ?: 0L) - (route.firstOrNull()?.time ?: 0L)
        return ActionLogEntity(
            actionType = ActionType.LOCATION_SPOOF,
            category = category,
            detail = "${mode.name} near ${city.name}",
            metadata = LogMetadata.toJson(
                LogMetadata.ROUTE_POINTS to route.size.toString(),
                LogMetadata.ROUTE_DURATION to if (durationMs > 0) "${durationMs / 1000}s" else null,
                LogMetadata.SENSITIVE_CLEARANCE to "at least ${(anchor.clearanceM - farthestM).toInt()} m",
            )
        )
    }

    private fun skipped(category: CategoryPool, detail: String) = ActionLogEntity(
        actionType = ActionType.LOCATION_SPOOF,
        category = category,
        detail = detail,
        success = false,
    )

    private companion object {
        const val ROUTE_POINTS = 5
        const val ROUTE_INTERVAL_MS = 5_000L
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
