package com.fauxx.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
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
import com.fauxx.targeting.TargetingEngine
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.random.Random

private const val TAG = "PoisonEngine"

/** Observable state of the engine for UI/notification display. */
enum class EngineState {
    /** Actively dispatching noise actions. */
    ACTIVE,
    /** Running but paused due to WiFi/battery/time constraints. */
    PAUSED_WIFI,
    PAUSED_BATTERY,
    PAUSED_RATE_LIMIT,
    /** Not started or stopped. */
    STOPPED
}

/** Maximum consecutive failures before a module is temporarily disabled. */
private const val MAX_CONSECUTIVE_FAILURES = 5

/** Initial backoff delay in ms after a module hits the error threshold. */
private const val INITIAL_BACKOFF_MS = 30_000L

/** Maximum backoff delay (capped at 30 minutes). */
private const val MAX_BACKOFF_MS = 30 * 60 * 1000L

/** Base delay between constraint re-checks when paused (scaled by intensity). */
private const val CONSTRAINT_CHECK_BASE_MS = 60_000L

/** Minimum constraint re-check interval (HIGH intensity floor). */
private const val CONSTRAINT_CHECK_MIN_MS = 3_000L

/** Delay after a single module failure before retrying. */
private const val FAILURE_RETRY_DELAY_MS = 5_000L

/** Milliseconds in 24 hours. */
private const val MS_PER_DAY = 24 * 60 * 60 * 1000L

/** Sliding window duration for per-hour rate limiting. */
private const val RATE_LIMIT_WINDOW_MS = 60 * 60 * 1000L

/** Delay when rate limit is hit before rechecking. */
private const val RATE_LIMIT_PAUSE_MS = 15_000L

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
    private val targetingEngine: TargetingEngine,
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

    // --- Cached constraint state (updated via BroadcastReceivers) ---
    private val cachedBatteryLevel = AtomicInteger(100)
    private val cachedOnWifi = AtomicBoolean(false)

    /** Today's successful action count, incremented on each action. Reset on day rollover. */
    private val todayActionCount = AtomicInteger(0)
    private var actionCountDayStart = System.currentTimeMillis() - (System.currentTimeMillis() % MS_PER_DAY)

    /**
     * Sliding window of action timestamps (epoch ms) for per-hour rate limiting.
     * Entries older than [RATE_LIMIT_WINDOW_MS] are pruned on each check.
     */
    private val recentActionTimestamps = java.util.concurrent.ConcurrentLinkedQueue<Long>()

    /** Current engine state for notification/UI display. */
    @Volatile
    var engineState: EngineState = EngineState.STOPPED
        private set

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) cachedBatteryLevel.set(level * 100 / scale)
        }
    }

    private val connectivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            cachedOnWifi.set(checkWifiNow())
        }
    }

    /** Returns today's successful action count (non-blocking, cached). */
    fun getTodayActionCount(): Int {
        val now = System.currentTimeMillis()
        val dayStart = now - (now % MS_PER_DAY)
        if (dayStart != actionCountDayStart) {
            actionCountDayStart = dayStart
            todayActionCount.set(0)
        }
        return todayActionCount.get()
    }

    /** All modules in order of dispatch preference. */
    private val allModules: List<Module> get() = listOf(
        searchModule, cookieModule, dnsModule,
        fingerprintModule, locationModule, adModule, appSignalModule
    )

    /** Start the engine main loop. */
    fun start() {
        if (engineJob?.isActive == true) return
        registerConstraintReceivers()
        engineJob = scope.launch {
            // Sync targeting layer enable flags from persisted profile
            val savedProfile = profile.getProfile()
            targetingEngine.setLayer1Enabled(savedProfile.layer1Enabled)
            targetingEngine.setLayer2Enabled(savedProfile.layer2Enabled)
            targetingEngine.setLayer3Enabled(savedProfile.layer3Enabled)

            // Seed today's action count from DB once on start
            val dayStart = System.currentTimeMillis() - (System.currentTimeMillis() % MS_PER_DAY)
            actionCountDayStart = dayStart
            todayActionCount.set(
                try { actionLogDao.countSince(dayStart).first() } catch (_: Exception) { 0 }
            )
            engineState = EngineState.ACTIVE
            Log.i(TAG, "PoisonEngine started (layers: L1=${savedProfile.layer1Enabled}, L2=${savedProfile.layer2Enabled}, L3=${savedProfile.layer3Enabled})")
            allModules.filter { it.isEnabled() }.forEach { it.start() }
            runLoop()
        }
    }

    /** Stop the engine and release all module resources. */
    fun stop() {
        engineJob?.cancel()
        engineJob = null
        engineState = EngineState.STOPPED
        unregisterConstraintReceivers()
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

    private fun registerConstraintReceivers() {
        // Seed battery level from sticky broadcast
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent != null) {
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) cachedBatteryLevel.set(level * 100 / scale)
        }
        // Seed WiFi state
        cachedOnWifi.set(checkWifiNow())

        // Register ongoing receivers
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
            context.registerReceiver(connectivityReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION), Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            @Suppress("DEPRECATION")
            context.registerReceiver(connectivityReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
        }
    }

    private fun unregisterConstraintReceivers() {
        runCatching { context.unregisterReceiver(batteryReceiver) }
        runCatching { context.unregisterReceiver(connectivityReceiver) }
    }

    private suspend fun runLoop() {
        while (scope.isActive) {
            val currentProfile = profile.getProfile()

            val constraintRetryMs = constraintCheckMs(currentProfile.intensity.actionsPerHour)

            // Constraint checks
            val constraintState = checkConstraints(currentProfile)
            if (constraintState != null) {
                engineState = constraintState
                delay(constraintRetryMs)
                continue
            }

            // Per-hour rate limit: prune stale timestamps and check cap
            val rateLimitNow = System.currentTimeMillis()
            val cutoff = rateLimitNow - RATE_LIMIT_WINDOW_MS
            while (recentActionTimestamps.peek()?.let { it < cutoff } == true) {
                recentActionTimestamps.poll()
            }
            if (recentActionTimestamps.size >= currentProfile.intensity.actionsPerHour) {
                engineState = EngineState.PAUSED_RATE_LIMIT
                Log.d(TAG, "Rate limit reached: ${recentActionTimestamps.size}/${currentProfile.intensity.actionsPerHour} actions/hour")
                delay(RATE_LIMIT_PAUSE_MS)
                continue
            }

            engineState = EngineState.ACTIVE

            // Pick next category
            val category = dispatcher.selectCategory()

            // Pick an enabled module that isn't circuit-broken
            val now = System.currentTimeMillis()
            val availableModules = allModules.filter { module ->
                val name = module::class.simpleName ?: return@filter false
                module.isEnabled() && (circuitBreakerUntil[name] ?: 0L) <= now
            }
            if (availableModules.isEmpty()) {
                delay(constraintRetryMs)
                continue
            }

            val module = availableModules.random()
            val moduleName = module::class.simpleName ?: "Unknown"

            // Write-ahead log — track execution time to subtract from scheduled delay
            val execStart = System.currentTimeMillis()
            val logEntry = try {
                module.onAction(category).also {
                    // Success — reset failure counter
                    failureCounts.remove(moduleName)
                }
            } catch (e: Exception) {
                val count = (failureCounts[moduleName] ?: 0) + 1
                failureCounts[moduleName] = count

                if (count >= MAX_CONSECUTIVE_FAILURES) {
                    val baseBackoff = min(INITIAL_BACKOFF_MS * (1L shl (count - MAX_CONSECUTIVE_FAILURES)), MAX_BACKOFF_MS)
                    // Add 0-25% random jitter to prevent thundering herd on recovery
                    val jitter = (baseBackoff * Random.nextFloat() * 0.25f).toLong()
                    val backoff = baseBackoff + jitter
                    circuitBreakerUntil[moduleName] = System.currentTimeMillis() + backoff
                    Log.w(TAG, "Circuit breaker: $moduleName disabled for ${backoff / 1000}s after $count failures", e)
                } else {
                    Log.e(TAG, "Module $moduleName failed for $category ($count/$MAX_CONSECUTIVE_FAILURES)", e)
                }
                delay(FAILURE_RETRY_DELAY_MS)
                continue
            }
            actionLogDao.insert(logEntry)
            if (logEntry.success) {
                todayActionCount.incrementAndGet()
                recentActionTimestamps.add(System.currentTimeMillis())
            }

            // Schedule next action, subtracting module execution time so the
            // inter-action interval (not post-action gap) matches the target rate.
            val execElapsed = System.currentTimeMillis() - execStart
            val scheduledMs = scheduler.nextDelayMs(
                actionsPerHour = currentProfile.intensity.actionsPerHour,
                allowedStart = currentProfile.allowedHoursStart,
                allowedEnd = currentProfile.allowedHoursEnd
            )
            val effectiveDelay = maxOf(0L, scheduledMs - execElapsed)
            Log.d(TAG, "Action: $moduleName/$category exec=${execElapsed}ms scheduled=${scheduledMs}ms effective=${effectiveDelay}ms")
            delay(effectiveDelay)
        }
    }

    /**
     * Returns null if all constraints pass, or the specific [EngineState] pause reason.
     */
    private fun checkConstraints(currentProfile: PoisonProfile): EngineState? {
        if (currentProfile.wifiOnly && !cachedOnWifi.get()) {
            Log.d(TAG, "Paused: wifi-only mode, no wifi")
            return EngineState.PAUSED_WIFI
        }
        if (cachedBatteryLevel.get() < currentProfile.batteryThreshold) {
            Log.d(TAG, "Paused: battery below threshold")
            return EngineState.PAUSED_BATTERY
        }
        return null
    }

    /** Returns the constraint-check retry interval scaled by intensity.
     *  HIGH (200/hr) → ~3.6s, MEDIUM (60/hr) → ~12s, LOW (12/hr) → 60s. */
    private fun constraintCheckMs(actionsPerHour: Int): Long =
        maxOf(CONSTRAINT_CHECK_MIN_MS, CONSTRAINT_CHECK_BASE_MS / maxOf(1, actionsPerHour / 12).toLong())

    /** One-shot WiFi check used to seed the cache. */
    private fun checkWifiNow(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
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
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Seed the cache with the first read (blocking) so getProfile() never returns
        // un-initialised defaults on a warm start.
        runBlocking {
            dataStore.data.first().let { cached.set(prefsToProfile(it)) }
        }
        // Keep cache up-to-date in the background.
        backgroundScope.launch {
            dataStore.data.collect { cached.set(prefsToProfile(it)) }
        }
    }

    /** Cancel background collection. Call during app teardown. */
    fun close() {
        backgroundScope.cancel()
    }

    /** Returns the latest cached profile (non-blocking). */
    fun getProfile(): PoisonProfile = cached.get()

    /** Persists [p] to DataStore. */
    suspend fun saveProfile(p: PoisonProfile) {
        dataStore.edit { prefs ->
            profileToPrefs(p, prefs)
        }
    }

    /**
     * Atomically reads the current profile, applies [transform], and writes the result back
     * inside a single DataStore transaction. This prevents concurrent writes from losing
     * earlier changes (e.g., rapid slider drags overwriting a prior intensity change).
     */
    suspend fun updateProfile(transform: (PoisonProfile) -> PoisonProfile) {
        dataStore.edit { prefs ->
            val current = prefsToProfile(prefs)
            val updated = transform(current)
            profileToPrefs(updated, prefs)
        }
    }

    private fun profileToPrefs(p: PoisonProfile, prefs: androidx.datastore.preferences.core.MutablePreferences) {
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
