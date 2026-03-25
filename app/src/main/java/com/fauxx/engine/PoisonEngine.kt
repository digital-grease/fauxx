package com.fauxx.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.Log
import com.fauxx.data.db.ActionLogDao
import com.fauxx.data.model.PoisonProfile
import com.fauxx.engine.modules.AdPollutionModule
import com.fauxx.engine.modules.AppSignalModule
import com.fauxx.engine.modules.CookieSaturationModule
import com.fauxx.engine.modules.DnsNoiseModule
import com.fauxx.engine.modules.FingerprintModule
import com.fauxx.engine.modules.LocationSpoofModule
import com.fauxx.engine.modules.Module
import com.fauxx.engine.modules.SearchPoisonModule
import com.fauxx.engine.scheduling.ActionDispatcher
import com.fauxx.engine.scheduling.PoissonScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

private const val TAG = "PoisonEngine"

/** Maximum consecutive failures before a module is temporarily disabled. */
private const val MAX_CONSECUTIVE_FAILURES = 5

/** Initial backoff delay in ms after a module hits the error threshold. */
private const val INITIAL_BACKOFF_MS = 30_000L

/** Maximum backoff delay (capped at 30 minutes). */
private const val MAX_BACKOFF_MS = 30 * 60 * 1000L

/** Delay between constraint re-checks when paused. */
private const val CONSTRAINT_CHECK_INTERVAL_MS = 60_000L

/** Delay after a single module failure before retrying. */
private const val FAILURE_RETRY_DELAY_MS = 5_000L

/** Milliseconds in 24 hours. */
private const val MS_PER_DAY = 24 * 60 * 60 * 1000L

/**
 * Core orchestrator for the Fauxx privacy poisoning engine.
 *
 * Reads [PoisonProfile], dispatches work to enabled module executors, manages scheduling via
 * Poisson-distributed timers, and respects battery/wifi/time constraints.
 *
 * All actions are logged to Room via [ActionLogDao] before execution (write-ahead logging).
 */
@Singleton
class PoisonEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profile: PoisonProfileRepository,
    private val dispatcher: ActionDispatcher,
    private val scheduler: PoissonScheduler,
    private val actionLogDao: ActionLogDao,
    private val searchModule: SearchPoisonModule,
    private val adModule: AdPollutionModule,
    private val locationModule: LocationSpoofModule,
    private val fingerprintModule: FingerprintModule,
    private val cookieModule: CookieSaturationModule,
    private val appSignalModule: AppSignalModule,
    private val dnsModule: DnsNoiseModule
) {
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engineJob: Job? = null

    /** Tracks consecutive failure count per module class name. */
    private val failureCounts = ConcurrentHashMap<String, Int>()

    /** Tracks when a circuit-broken module can next be retried (epoch ms). */
    private val circuitBreakerUntil = ConcurrentHashMap<String, Long>()

    /** All modules in order of dispatch preference. */
    private val allModules: List<Module> get() = listOf(
        searchModule, cookieModule, dnsModule,
        fingerprintModule, locationModule, adModule, appSignalModule
    )

    /** Start the engine main loop. */
    fun start() {
        if (engineJob?.isActive == true) return
        engineJob = scope.launch {
            Log.i(TAG, "PoisonEngine started")
            allModules.filter { it.isEnabled() }.forEach { it.start() }
            runLoop()
        }
    }

    /** Stop the engine and release all module resources. */
    fun stop() {
        engineJob?.cancel()
        engineJob = null
        runBlocking {
            allModules.forEach { runCatching { it.stop() } }
        }
        Log.i(TAG, "PoisonEngine stopped")
    }

    /**
     * Cancel the engine's coroutine scope entirely. Call from service [onDestroy]
     * to ensure no leaked coroutines survive process cleanup.
     */
    fun destroy() {
        stop()
        scope.cancel()
    }

    private suspend fun runLoop() {
        val currentProfile = profile.getProfile()

        while (scope.isActive) {
            // Constraint checks
            if (!checkConstraints(currentProfile)) {
                delay(CONSTRAINT_CHECK_INTERVAL_MS)
                continue
            }

            // Pick next category
            val category = dispatcher.selectCategory()

            // Pick an enabled module that isn't circuit-broken
            val now = System.currentTimeMillis()
            val availableModules = allModules.filter { module ->
                val name = module::class.simpleName ?: return@filter false
                module.isEnabled() && (circuitBreakerUntil[name] ?: 0L) <= now
            }
            if (availableModules.isEmpty()) {
                delay(CONSTRAINT_CHECK_INTERVAL_MS)
                continue
            }

            val module = availableModules.random()
            val moduleName = module::class.simpleName ?: "Unknown"

            // Write-ahead log
            val logEntry = try {
                module.onAction(category).also {
                    // Success — reset failure counter
                    failureCounts.remove(moduleName)
                }
            } catch (e: Exception) {
                val count = (failureCounts[moduleName] ?: 0) + 1
                failureCounts[moduleName] = count

                if (count >= MAX_CONSECUTIVE_FAILURES) {
                    val backoff = min(INITIAL_BACKOFF_MS * (1L shl (count - MAX_CONSECUTIVE_FAILURES)), MAX_BACKOFF_MS)
                    circuitBreakerUntil[moduleName] = System.currentTimeMillis() + backoff
                    Log.w(TAG, "Circuit breaker: $moduleName disabled for ${backoff / 1000}s after $count failures", e)
                } else {
                    Log.e(TAG, "Module $moduleName failed for $category ($count/$MAX_CONSECUTIVE_FAILURES)", e)
                }
                delay(FAILURE_RETRY_DELAY_MS)
                continue
            }
            actionLogDao.insert(logEntry)

            // Schedule next action
            val delayMs = scheduler.nextDelayMs(
                actionsPerHour = currentProfile.intensity.actionsPerHour,
                allowedStart = currentProfile.allowedHoursStart,
                allowedEnd = currentProfile.allowedHoursEnd
            )
            delay(delayMs)
        }
    }

    private fun checkConstraints(currentProfile: PoisonProfile): Boolean {
        if (currentProfile.wifiOnly && !isOnWifi()) {
            Log.d(TAG, "Paused: wifi-only mode, no wifi")
            return false
        }
        if (getBatteryLevel() < currentProfile.batteryThreshold) {
            Log.d(TAG, "Paused: battery below threshold")
            return false
        }
        return true
    }

    private fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun getBatteryLevel(): Int {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, filter) ?: return 100
        val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level < 0 || scale <= 0) 100 else (level * 100 / scale)
    }
}

/**
 * Repository providing the current [PoisonProfile] backed by Jetpack DataStore.
 *
 * Internally collects the DataStore [Flow] and caches the latest value so that
 * [getProfile] can be called synchronously (required by [Module.isEnabled]).
 * Writes go through [saveProfile] which is a suspend function.
 */
@Singleton
class PoisonProfileRepository @Inject constructor(
    private val dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
) {
    private val cached = java.util.concurrent.atomic.AtomicReference(PoisonProfile())

    init {
        // Seed the cache with the first read (blocking) so getProfile() never returns
        // un-initialised defaults on a warm start.
        runBlocking {
            dataStore.data.first().let { cached.set(prefsToProfile(it)) }
        }
        // Keep cache up-to-date in the background.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            dataStore.data.collect { cached.set(prefsToProfile(it)) }
        }
    }

    /** Returns the latest cached profile (non-blocking). */
    fun getProfile(): PoisonProfile = cached.get()

    /** Persists [p] to DataStore. */
    suspend fun saveProfile(p: PoisonProfile) {
        dataStore.edit { prefs ->
            prefs[com.fauxx.di.PreferenceKeys.ENABLED] = p.enabled
            prefs[com.fauxx.di.PreferenceKeys.INTENSITY] = p.intensity.name
            prefs[com.fauxx.di.PreferenceKeys.WIFI_ONLY] = p.wifiOnly
            prefs[com.fauxx.di.PreferenceKeys.BATTERY_THRESHOLD] = p.batteryThreshold
            prefs[com.fauxx.di.PreferenceKeys.ALLOWED_HOURS_START] = p.allowedHoursStart
            prefs[com.fauxx.di.PreferenceKeys.ALLOWED_HOURS_END] = p.allowedHoursEnd
            prefs[com.fauxx.di.PreferenceKeys.MODULE_SEARCH] = p.searchPoisonEnabled
            prefs[com.fauxx.di.PreferenceKeys.MODULE_AD] = p.adPollutionEnabled
            prefs[com.fauxx.di.PreferenceKeys.MODULE_LOCATION] = p.locationSpoofEnabled
            prefs[com.fauxx.di.PreferenceKeys.MODULE_FINGERPRINT] = p.fingerprintEnabled
            prefs[com.fauxx.di.PreferenceKeys.MODULE_COOKIE] = p.cookieSaturationEnabled
            prefs[com.fauxx.di.PreferenceKeys.MODULE_APPSIGNAL] = p.appSignalEnabled
            prefs[com.fauxx.di.PreferenceKeys.MODULE_DNS] = p.dnsNoiseEnabled
            prefs[com.fauxx.di.PreferenceKeys.LAYER1_ENABLED] = p.layer1Enabled
            prefs[com.fauxx.di.PreferenceKeys.LAYER2_ENABLED] = p.layer2Enabled
            prefs[com.fauxx.di.PreferenceKeys.LAYER3_ENABLED] = p.layer3Enabled
        }
    }

    private fun prefsToProfile(prefs: androidx.datastore.preferences.core.Preferences): PoisonProfile =
        PoisonProfile(
            enabled = prefs[com.fauxx.di.PreferenceKeys.ENABLED] ?: false,
            intensity = com.fauxx.data.model.IntensityLevel.valueOf(
                prefs[com.fauxx.di.PreferenceKeys.INTENSITY]
                    ?: com.fauxx.data.model.IntensityLevel.MEDIUM.name
            ),
            wifiOnly = prefs[com.fauxx.di.PreferenceKeys.WIFI_ONLY] ?: true,
            batteryThreshold = prefs[com.fauxx.di.PreferenceKeys.BATTERY_THRESHOLD] ?: 20,
            allowedHoursStart = prefs[com.fauxx.di.PreferenceKeys.ALLOWED_HOURS_START] ?: 7,
            allowedHoursEnd = prefs[com.fauxx.di.PreferenceKeys.ALLOWED_HOURS_END] ?: 23,
            searchPoisonEnabled = prefs[com.fauxx.di.PreferenceKeys.MODULE_SEARCH] ?: true,
            adPollutionEnabled = prefs[com.fauxx.di.PreferenceKeys.MODULE_AD] ?: true,
            locationSpoofEnabled = prefs[com.fauxx.di.PreferenceKeys.MODULE_LOCATION] ?: false,
            fingerprintEnabled = prefs[com.fauxx.di.PreferenceKeys.MODULE_FINGERPRINT] ?: true,
            cookieSaturationEnabled = prefs[com.fauxx.di.PreferenceKeys.MODULE_COOKIE] ?: true,
            appSignalEnabled = prefs[com.fauxx.di.PreferenceKeys.MODULE_APPSIGNAL] ?: false,
            dnsNoiseEnabled = prefs[com.fauxx.di.PreferenceKeys.MODULE_DNS] ?: true,
            layer1Enabled = prefs[com.fauxx.di.PreferenceKeys.LAYER1_ENABLED] ?: false,
            layer2Enabled = prefs[com.fauxx.di.PreferenceKeys.LAYER2_ENABLED] ?: false,
            layer3Enabled = prefs[com.fauxx.di.PreferenceKeys.LAYER3_ENABLED] ?: true
        )
}
