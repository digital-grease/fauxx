package com.fauxx.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.work.NetworkType
import com.fauxx.di.IoDispatcher
import com.fauxx.service.ResumeSpec
import com.fauxx.util.Clock
import kotlinx.coroutines.CoroutineDispatcher
import timber.log.Timber
import com.fauxx.data.crawllist.CrawlListManager
import com.fauxx.data.crawllist.DomainBlocklist
import com.fauxx.data.db.ActionLogDao
import com.fauxx.data.location.CityDatabase
import com.fauxx.data.model.IntensityLevel
import com.fauxx.data.model.PoisonProfile
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.data.querybank.QueryBankManager
import com.fauxx.di.AdModuleImpl
import com.fauxx.di.LocationModuleImpl
import com.fauxx.engine.modules.AppSignalModule
import com.fauxx.engine.modules.CookieSaturationModule
import com.fauxx.engine.modules.DnsNoiseModule
import com.fauxx.engine.modules.FingerprintModule
import com.fauxx.engine.modules.Module
import com.fauxx.engine.modules.SearchPoisonModule
import com.fauxx.engine.scheduling.ActionDispatcher
import com.fauxx.engine.scheduling.AllowedHours
import com.fauxx.engine.scheduling.PoissonScheduler
import com.fauxx.engine.scheduling.UsageObserver
import com.fauxx.targeting.TargetingEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.random.Random

/** Observable state of the engine for UI/notification display. */
enum class EngineState {
    /** Actively dispatching noise actions. */
    ACTIVE,
    /** Running but paused because no usable network is available for the current
     *  settings: no connectivity at all, or on mobile data with the mobile intensity
     *  set to Off (issue #62). Name kept from the legacy wifi-only toggle era. */
    PAUSED_WIFI,
    PAUSED_BATTERY,
    PAUSED_RATE_LIMIT,
    PAUSED_QUIET_HOURS,
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


/** Sliding window duration for per-hour rate limiting. */
private const val RATE_LIMIT_WINDOW_MS = 60 * 60 * 1000L

/** Delay when rate limit is hit before rechecking. */
private const val RATE_LIMIT_PAUSE_MS = 15_000L

/** Maximum time to wait for all modules to finish stop() before giving up. */
private const val MODULE_STOP_TIMEOUT_MS = 2_000L

/**
 * Maximum chunk of the inter-action sleep before constraints are re-checked. Bounds how
 * long the engine can sit in a scheduled delay while a constraint (quiet hours entered,
 * WiFi dropped, battery sank) goes unnoticed — and, as defense in depth for issue #124,
 * caps the damage of any future oversized delay to one minute of unwarranted ACTIVE state
 * instead of hours of silent stall.
 */
private const val SLEEP_CONSTRAINT_RECHECK_MS = 60_000L

/**
 * Threshold for a "long pause" — wifi/battery pauses past this duration trigger the
 * engine to resign and switch to a WorkManager-constraint-driven resume notification,
 * releasing the foreground service rather than spinning idle.
 */
private const val LONG_PAUSE_THRESHOLD_MS = 30L * 60 * 1000

/**
 * Decision returned by [PoisonEngine.decidePauseAction]: either keep the current
 * delay-loop ([Continue]) or [Resign] from the foreground service and schedule a
 * resume notification when the constraint clears.
 */
sealed class PauseDecision {
    object Continue : PauseDecision()
    data class Resign(val resumeSpec: ResumeSpec) : PauseDecision()
}

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
    private val blocklist: DomainBlocklist,
    private val queryBankManager: QueryBankManager,
    private val crawlListManager: CrawlListManager,
    private val cityDatabase: CityDatabase,
    private val searchModule: SearchPoisonModule,
    @AdModuleImpl private val adModule: Module,
    @LocationModuleImpl private val locationModule: Module,
    private val fingerprintModule: FingerprintModule,
    private val cookieModule: CookieSaturationModule,
    private val appSignalModule: AppSignalModule,
    private val dnsModule: DnsNoiseModule,
    private val clock: Clock,
    /**
     * Dispatcher the engine's main loop runs on. Provided by [com.fauxx.di.DispatchersModule]
     * as [Dispatchers.IO] in production; tests substitute a `TestDispatcher` so `runLoop`
     * runs under the scheduler that controls virtual time.
     */
    @IoDispatcher private val loopDispatcher: CoroutineDispatcher,
    private val random: Random = Random.Default,
    /**
     * Observes local screen-on events to learn the user's daily rhythm (E10 #177). Registered
     * and unregistered alongside the constraint receivers so it only runs while the engine is
     * active. Defaults to a no-op so engine tests need not stand one up.
     */
    private val usageObserver: UsageObserver = UsageObserver.NONE,
) {
    private var scope = CoroutineScope(SupervisorJob() + loopDispatcher)
    private var engineJob: Job? = null

    /**
     * Set by [PhantomForegroundService] before starting the engine. When [runLoop]
     * decides to resign during a long pause (see [decidePauseAction]), it invokes this
     * callback with the resume spec; the service is expected to schedule a
     * [com.fauxx.service.ResumeWorker] and then stop the foreground service.
     */
    @Volatile
    private var onLongPause: ((ResumeSpec) -> Unit)? = null

    /**
     * Invoked once per [start], the first time the loop clears its constraint gate and is
     * about to actually run (issue #156). The FGS uses this to cancel any stale pending
     * resume — but only now that the engine has committed to running, not at start, so a
     * process death during engine init can no longer wipe a still-needed resume before it
     * fires.
     */
    @Volatile
    private var onActive: (() -> Unit)? = null

    /** Set true once [runLoop] has invoked [onActive] for the current run. */
    private var hasSignaledActive: Boolean = false

    /** Engine start time on [Clock.elapsedRealtime] for session-duration logging. */
    private var engineStartElapsedMs: Long = 0L

    /** [Clock.elapsedRealtime] timestamp at the moment the current pause began. */
    private var pauseEnteredAtElapsedMs: Long = 0L

    /** State that triggered the current pause; used to detect state transitions. */
    private var lastPauseState: EngineState = EngineState.ACTIVE

    /** Set true once [runLoop] has invoked [onLongPause] for the current run, to avoid double-firing. */
    private var hasResigned: Boolean = false

    /** Tracks consecutive failure count per module class name. */
    private val failureCounts = ConcurrentHashMap<String, Int>()

    /** Tracks when a circuit-broken module can next be retried (epoch ms). */
    private val circuitBreakerUntil = ConcurrentHashMap<String, Long>()

    // --- Cached constraint state (updated via BroadcastReceivers) ---
    private val cachedBatteryLevel = AtomicInteger(100)
    private val cachedIsCharging = AtomicBoolean(false)

    /**
     * Transport class of the active network, kept fresh by [networkCallback]. Replaces the
     * old boolean `cachedOnWifi`: per-network intensity (issue #62) needs to distinguish
     * Wi-Fi from mobile data from no-network, not just "on Wi-Fi or not".
     */
    private val cachedTransport = java.util.concurrent.atomic.AtomicReference(NetworkTransport.NONE)

    /**
     * Seed the cached constraint state directly. Mirrors what the battery/connectivity
     * BroadcastReceivers do, for tests that drive constraint transitions without a live
     * broadcast (e.g. a no-network↔battery flicker exercising the cumulative resign clock).
     */
    @VisibleForTesting
    internal fun setCachedConstraintStateForTest(
        transport: NetworkTransport? = null,
        batteryLevel: Int? = null,
        charging: Boolean? = null,
    ) {
        transport?.let { cachedTransport.set(it) }
        batteryLevel?.let { cachedBatteryLevel.set(it) }
        charging?.let { cachedIsCharging.set(it) }
    }

    /** Today's successful action count, incremented on each action. Reset on day rollover. */
    private val todayActionCount = AtomicInteger(0)
    @Volatile
    private var actionCountDayStart = localDayStartMs(clock.currentTimeMillis())

    /**
     * Sliding window of action timestamps (epoch ms) for per-hour rate limiting.
     * Entries older than [RATE_LIMIT_WINDOW_MS] are pruned on each check.
     */
    private val recentActionTimestamps = java.util.concurrent.ConcurrentLinkedQueue<Long>()

    /** Current engine state for notification/UI display. */
    private val _engineState = MutableStateFlow(EngineState.STOPPED)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    /**
     * Health warnings generated at startup when asset data failed to load.
     * Observable by UI (e.g., DashboardScreen) to show degradation warnings.
     */
    private val _healthWarnings = MutableStateFlow<List<String>>(emptyList())
    val healthWarnings: StateFlow<List<String>> = _healthWarnings.asStateFlow()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) cachedBatteryLevel.set(level * 100 / scale)
            // EXTRA_PLUGGED is 0 when unplugged; non-zero values are AC / USB / wireless.
            cachedIsCharging.set(intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            cachedTransport.set(classifyTransport(caps, lookupUnderlyingCaps()))
        }

        override fun onLost(network: Network) {
            cachedTransport.set(NetworkTransport.NONE)
        }
    }

    /**
     * Returns a callable that resolves the VPN underlying-network caps. Looked up lazily —
     * only the VPN branch in [classifyTransport] invokes it, so the non-VPN path stays
     * cheap. Falls back to scanning `cm.allNetworks` on API < 31 where
     * `NetworkCapabilities.underlyingNetworks` is unavailable.
     */
    private fun lookupUnderlyingCaps(): () -> List<NetworkCapabilities> = {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        // `NetworkCapabilities.getUnderlyingNetworks()` was hidden on early platform
        // SDKs even when present at runtime, so the cleanest portable strategy is to
        // scan `cm.allNetworks` for any non-VPN WiFi connection as a proxy for "the
        // VPN is most likely tunneling over WiFi." Covers TrackerControl / NetGuard /
        // local-VPN ad-blockers per issue #59.
        cm?.allNetworks?.mapNotNull { cm.getNetworkCapabilities(it) }.orEmpty()
    }

    /**
     * Local-midnight start of the day containing [nowMs] (mirrors nextAllowedHoursStartMs's
     * Calendar field-zeroing). Local rather than UTC (epoch-ms modulo) so "actions today" rolls
     * over at the user's wall-clock midnight, not UTC midnight — audit fix for non-UTC zones.
     */
    private fun localDayStartMs(nowMs: Long): Long =
        java.util.Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** Roll the action counter to a fresh day if [nowMs] has crossed local midnight. */
    @Synchronized
    private fun rollIfNewDay(nowMs: Long) {
        val dayStart = localDayStartMs(nowMs)
        if (dayStart != actionCountDayStart) {
            actionCountDayStart = dayStart
            todayActionCount.set(0)
        }
    }

    /**
     * Record one successful action under the same monitor as the rollover, so a concurrent
     * day-rollover can never zero a just-incremented new-day count — audit fix for the race
     * between the engine's first post-midnight action and the FGS poll's reset.
     */
    @Synchronized
    private fun recordTodayAction(nowMs: Long) {
        rollIfNewDay(nowMs)
        todayActionCount.incrementAndGet()
    }

    /** Returns today's successful action count (thread-safe, cached). */
    @Synchronized
    fun getTodayActionCount(): Int {
        rollIfNewDay(clock.currentTimeMillis())
        return todayActionCount.get()
    }

    /** All modules in order of dispatch preference. */
    private val allModules: List<Module> get() = listOf(
        searchModule, cookieModule, dnsModule,
        fingerprintModule, locationModule, adModule, appSignalModule
    )

    /**
     * Register a handler invoked when [runLoop] decides the engine should resign
     * during a long constraint pause. The handler is responsible for scheduling
     * the resume notification (via [com.fauxx.service.ResumeScheduler]) and stopping
     * the foreground service. Safe to call before or after [start].
     */
    fun setOnLongPause(handler: ((ResumeSpec) -> Unit)?) {
        onLongPause = handler
    }

    /**
     * Register a handler invoked once per [start], the first time the loop confirms the
     * engine is running (constraint gate cleared). Used by the FGS to retire a stale
     * pending resume only after the engine has actually committed to running. Safe to call
     * before or after [start].
     */
    fun setOnActive(handler: (() -> Unit)?) {
        onActive = handler
    }

    /** Start the engine main loop. */
    fun start() {
        if (engineJob?.isActive == true) return
        // Recreate the scope if it was previously cancelled by destroy()
        if (!scope.isActive) {
            scope = CoroutineScope(SupervisorJob() + loopDispatcher)
        }
        engineStartElapsedMs = clock.elapsedRealtime()
        pauseEnteredAtElapsedMs = 0L
        lastPauseState = EngineState.ACTIVE
        hasResigned = false
        hasSignaledActive = false
        registerConstraintReceivers()
        engineJob = scope.launch {
            // Sync targeting layer enable flags from persisted profile
            val savedProfile = profile.getProfile()
            targetingEngine.setLayer1Enabled(savedProfile.layer1Enabled)
            targetingEngine.setLayer2Enabled(savedProfile.layer2Enabled)
            targetingEngine.setLayer3Enabled(savedProfile.layer3Enabled)
            targetingEngine.setAdversarialAllocationEnabled(savedProfile.adversarialAllocationEnabled)

            // Seed today's action count from DB once on start
            val dayStart = localDayStartMs(clock.currentTimeMillis())
            actionCountDayStart = dayStart
            todayActionCount.set(
                try { actionLogDao.countSince(dayStart).first() } catch (_: Exception) { 0 }
            )
            _engineState.value = EngineState.ACTIVE
            Timber.i("PoisonEngine started (layers: L1=${savedProfile.layer1Enabled}, L2=${savedProfile.layer2Enabled}, L3=${savedProfile.layer3Enabled}, adversarialAlloc=${savedProfile.adversarialAllocationEnabled})")
            // Fail-closed: if blocklist couldn't load, permanently circuit-break all
            // URL-loading modules. These modules load arbitrary URLs and MUST have a
            // working blocklist to prevent navigation to harmful domains.
            if (blocklist.loadFailed) {
                val urlModules = listOf(searchModule, cookieModule, adModule, appSignalModule)
                for (m in urlModules) {
                    val name = m::class.simpleName ?: "Unknown"
                    circuitBreakerUntil[name] = Long.MAX_VALUE
                    Timber.e("Blocklist failed to load — permanently disabling $name")
                }
            }

            // Startup asset health check — verify critical data loaded successfully.
            _healthWarnings.value = checkAssetHealth()

            allModules.filter { it.isEnabled() }.forEach { module ->
                try {
                    module.start()
                } catch (e: Exception) {
                    val name = module::class.simpleName ?: "Unknown"
                    Timber.e(e, "Module $name failed to start, circuit-breaking")
                    circuitBreakerUntil[name] = clock.currentTimeMillis() + MAX_BACKOFF_MS
                }
            }
            runLoop()
        }
    }

    /**
     * Stop the engine and release all module resources.
     *
     * Safe to call from the main thread: module teardown runs asynchronously on the
     * engine's IO scope with a bounded timeout. Never blocks the caller.
     */
    fun stop() {
        engineJob?.cancel()
        engineJob = null
        _engineState.value = EngineState.STOPPED
        onLongPause = null
        onActive = null
        unregisterConstraintReceivers()
        val modules = allModules
        scope.launch {
            withTimeoutOrNull(MODULE_STOP_TIMEOUT_MS) {
                modules.forEach { runCatching { it.stop() } }
            } ?: Timber.w("Module stop timed out after ${MODULE_STOP_TIMEOUT_MS}ms")
            Timber.i("PoisonEngine stopped")
        }
    }

    /**
     * Cancel the engine's coroutine scope entirely. Call from service [onDestroy]
     * to ensure no leaked coroutines survive process cleanup.
     */
    fun destroy() {
        engineJob?.cancel()
        engineJob = null
        _engineState.value = EngineState.STOPPED
        onLongPause = null
        onActive = null
        unregisterConstraintReceivers()
        val modules = allModules
        // Fire-and-forget teardown; scope is cancelled after a short grace period so the
        // launched stop coroutine has a chance to run. Bounded by timeout to avoid leaks.
        scope.launch {
            withTimeoutOrNull(MODULE_STOP_TIMEOUT_MS) {
                modules.forEach { runCatching { it.stop() } }
            }
            scope.cancel()
            Timber.i("PoisonEngine destroyed")
        }
    }

    private fun registerConstraintReceivers() {
        // Seed battery level + charging state from sticky broadcast
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent != null) {
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) cachedBatteryLevel.set(level * 100 / scale)
            cachedIsCharging.set(batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
        }
        // Seed network transport state
        cachedTransport.set(checkTransportNow())

        // Register ongoing receivers. Battery still uses a broadcast; connectivity uses
        // NetworkCallback (CONNECTIVITY_ACTION was deprecated in API 28).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)
                .registerDefaultNetworkCallback(networkCallback)
        }.onFailure {
            // Without the callback, cachedTransport freezes at its seed value and a later
            // WiFi→mobile move would keep the engine acting at the WiFi tier. Surface it.
            Timber.w(it, "registerDefaultNetworkCallback failed; transport tracking degraded")
        }
        // Start learning the user's daily rhythm while the engine runs (E10 #177).
        runCatching { usageObserver.start() }
            .onFailure { Timber.w(it, "usageObserver.start failed; circadian rhythm degraded") }
    }

    private fun unregisterConstraintReceivers() {
        runCatching { context.unregisterReceiver(batteryReceiver) }
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)
                .unregisterNetworkCallback(networkCallback)
        }
        runCatching { usageObserver.stop() }
    }

    private suspend fun runLoop() {
        // Tracks the last successfully-executed category so the scheduler can apply
        // human-like dwell time on cross-niche transitions. In-memory only — a fresh
        // engine run starts a fresh "browsing session," which is realistic.
        var lastCategory: CategoryPool? = null
        while (scope.isActive) {
            val currentProfile = profile.getProfile()

            // Per-network intensity (issue #62): the rate everything below keys off depends
            // on the current transport — Wi-Fi runs `intensity`, mobile runs `mobileIntensity`.
            // Null means "may not act right now" (no network, or mobile with mobile set to
            // Off); checkConstraints reports that as PAUSED_WIFI. The retry cadence falls
            // back to the Wi-Fi rate while paused so the pause is noticed promptly.
            val effectiveIntensity = effectiveIntensity(currentProfile)
            val constraintRetryMs = constraintCheckMs(
                (effectiveIntensity ?: currentProfile.intensity).actionsPerHour
            )

            // Constraint checks
            val constraintState = checkConstraints(currentProfile)
            if (constraintState != null) {
                if (lastPauseState != constraintState) {
                    // Start the cumulative pause clock only on the ACTIVE→pause edge. Keeping
                    // it running across pause→pause transitions (e.g. a wifi↔battery flicker)
                    // means the resign threshold measures total time paused, not time in the
                    // current state — otherwise a flicker resets the clock every few seconds
                    // and the engine never resigns (#158).
                    if (lastPauseState == EngineState.ACTIVE) {
                        pauseEnteredAtElapsedMs = clock.elapsedRealtime()
                    }
                    lastPauseState = constraintState
                    // Log the reason once per transition, not on every retry tick (#158).
                    logPauseReason(constraintState, currentProfile)
                }
                _engineState.value = constraintState

                if (!hasResigned) {
                    val pauseElapsedMs = clock.elapsedRealtime() - pauseEnteredAtElapsedMs
                    val totalRuntimeMs = clock.elapsedRealtime() - engineStartElapsedMs
                    val decision = decidePauseAction(constraintState, currentProfile, pauseElapsedMs, totalRuntimeMs)
                    if (decision is PauseDecision.Resign) {
                        resignAndRecord(constraintState, decision.resumeSpec)
                        return
                    }
                }

                delay(constraintRetryMs)
                continue
            }

            // Reset pause tracking on a successful constraint check
            lastPauseState = EngineState.ACTIVE
            pauseEnteredAtElapsedMs = 0L

            // checkConstraints re-evaluated the transport freshly and passed; this is the
            // loop-top SNAPSHOT, which a mid-iteration transport flip can have left null.
            // The elvis re-enters the loop so the pause branch can handle that flip; when
            // non-null-but-stale (flip between tiers), the wrong tier governs for at most
            // this one iteration before the loop-top recompute corrects it.
            val activeIntensity = effectiveIntensity ?: continue

            // First time we clear the constraint gate this run (with a usable network),
            // tell the FGS the engine has committed to running so it can retire any stale
            // pending resume (#156). Fires after the gate, never at start, so a still-needed
            // resume is never cancelled before the engine actually takes over from it.
            if (!hasSignaledActive) {
                hasSignaledActive = true
                runCatching { onActive?.invoke() }
                    .onFailure { Timber.e(it, "onActive handler failed") }
            }

            // Per-hour rate limit: prune stale timestamps and check cap. The window is
            // deliberately network-agnostic: after a HIGH-on-WiFi hour, dropping to mobile
            // LOW keeps the engine paused until the window drains below the mobile cap —
            // the whole point of a lower mobile tier is not to burn mobile data.
            val rateLimitNow = clock.currentTimeMillis()
            val cutoff = rateLimitNow - RATE_LIMIT_WINDOW_MS
            while (recentActionTimestamps.peek()?.let { it < cutoff } == true) {
                recentActionTimestamps.poll()
            }
            if (recentActionTimestamps.size >= activeIntensity.actionsPerHour) {
                _engineState.value = EngineState.PAUSED_RATE_LIMIT
                Timber.d("Rate limit reached: ${recentActionTimestamps.size}/${activeIntensity.actionsPerHour} actions/hour")
                delay(RATE_LIMIT_PAUSE_MS)
                continue
            }

            _engineState.value = EngineState.ACTIVE

            // Pick next category
            val category = dispatcher.selectCategory()

            // Pick an enabled module that isn't circuit-broken
            val now = clock.currentTimeMillis()
            val availableModules = allModules.filter { module ->
                val name = module::class.simpleName ?: return@filter false
                module.isEnabled() && (circuitBreakerUntil[name] ?: 0L) <= now
            }
            if (availableModules.isEmpty()) {
                delay(constraintRetryMs)
                continue
            }

            val module = availableModules.random(random)
            val moduleName = module::class.simpleName ?: "Unknown"

            // Re-check enable state to avoid acting on a stale snapshot
            if (!module.isEnabled()) continue

            // Write-ahead log — track execution time to subtract from scheduled delay
            val execStart = clock.currentTimeMillis()
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
                    val jitter = (baseBackoff * random.nextFloat() * 0.25f).toLong()
                    val backoff = baseBackoff + jitter
                    circuitBreakerUntil[moduleName] = clock.currentTimeMillis() + backoff
                    Timber.w(e, "Circuit breaker: $moduleName disabled for ${backoff / 1000}s after $count failures")
                } else {
                    Timber.e(e, "Module $moduleName failed for $category ($count/$MAX_CONSECUTIVE_FAILURES)")
                }
                delay(FAILURE_RETRY_DELAY_MS)
                continue
            }
            try {
                actionLogDao.insert(logEntry)
            } catch (e: Exception) {
                Timber.e(e, "Failed to insert action log entry")
            }
            if (logEntry.success) {
                recordTodayAction(clock.currentTimeMillis())
                recentActionTimestamps.add(clock.currentTimeMillis())
            }

            // Schedule next action, subtracting module execution time so the
            // inter-action interval (not post-action gap) matches the target rate.
            // The dwell/burst state machine keys off what the module actually emitted
            // (logEntry.category), not what was planned — a module may redirect the
            // action (E8: AppSignal's persona-interest swap), and cross-niche pacing
            // only protects against bot signals if it tracks the visible stream.
            val executedCategory = logEntry.category
            val execElapsed = clock.currentTimeMillis() - execStart
            val scheduledMs = scheduler.nextDelayMs(
                actionsPerHour = activeIntensity.actionsPerHour,
                prev = lastCategory,
                next = executedCategory,
                allowedStart = currentProfile.allowedHoursStart,
                allowedEnd = currentProfile.allowedHoursEnd
            )
            // Update lastCategory only on success — failed actions shouldn't poison the
            // dwell signal (a circuit-broken module isn't really "browsing" anything).
            if (logEntry.success) lastCategory = executedCategory
            val effectiveDelay = maxOf(0L, scheduledMs - execElapsed)
            Timber.d("Action: $moduleName/$category exec=${execElapsed}ms scheduled=${scheduledMs}ms effective=${effectiveDelay}ms")
            sleepRespectingConstraints(effectiveDelay)
        }
    }

    /**
     * Sleep [totalMs] in chunks of at most [SLEEP_CONSTRAINT_RECHECK_MS], re-reading the
     * profile and re-checking constraints between chunks. Returns early when a constraint
     * trips so the main loop's pause/resign handling reacts within a minute instead of
     * after the full inter-action delay, which can legitimately reach tens of minutes at
     * LOW intensity — and reached 9-21 hours when issue #124 made the scheduler return a
     * quiet-hours-sized delay the engine slept through while reporting ACTIVE. The chunk
     * sum equals [totalMs] exactly when nothing trips, so the Poisson pacing is preserved.
     */
    private suspend fun sleepRespectingConstraints(totalMs: Long) {
        var remaining = totalMs
        while (remaining > 0) {
            val chunk = minOf(remaining, SLEEP_CONSTRAINT_RECHECK_MS)
            delay(chunk)
            remaining -= chunk
            if (remaining > 0 && checkConstraints(profile.getProfile()) != null) return
        }
    }

    /**
     * The action rate for the current network transport, or null when the engine must
     * pause: no network at all, or on mobile data with [PoisonProfile.mobileIntensity]
     * set to Off (issue #62).
     */
    private fun effectiveIntensity(p: PoisonProfile): IntensityLevel? = when (cachedTransport.get()) {
        NetworkTransport.WIFI -> p.intensity
        NetworkTransport.CELLULAR -> p.mobileIntensity
        NetworkTransport.NONE -> null
    }

    /**
     * Returns null if all constraints pass, or the specific [EngineState] pause reason.
     */
    private fun checkConstraints(currentProfile: PoisonProfile): EngineState? {
        // No logging here — this runs every retry tick (and again per 60s sleep chunk).
        // The reason is logged once per transition by [logPauseReason] from runLoop (#158).
        if (effectiveIntensity(currentProfile) == null) {
            return EngineState.PAUSED_WIFI
        }
        if (shouldPauseForBattery(
                batteryLevel = cachedBatteryLevel.get(),
                thresholdBattery = currentProfile.batteryThresholdBattery,
                thresholdCharging = currentProfile.batteryThresholdCharging,
                isCharging = cachedIsCharging.get()
            )
        ) {
            return EngineState.PAUSED_BATTERY
        }
        if (!isWithinAllowedHours(currentProfile)) {
            return EngineState.PAUSED_QUIET_HOURS
        }
        return null
    }

    /**
     * Log the reason for a constraint pause. Called once per pause-state transition from
     * [runLoop], NOT from [checkConstraints] (which runs every few seconds), so a long pause
     * produces a single log line instead of one per retry tick (#158). A wifi↔battery flicker
     * still logs each genuine state change.
     */
    private fun logPauseReason(state: EngineState, profile: PoisonProfile) {
        when (state) {
            EngineState.PAUSED_WIFI ->
                Timber.d("Paused: no usable network (transport=${cachedTransport.get()}, mobile=${profile.mobileIntensity})")
            EngineState.PAUSED_BATTERY -> Timber.d("Paused: battery below threshold")
            EngineState.PAUSED_QUIET_HOURS ->
                Timber.d("Paused: outside allowed hours (${profile.allowedHoursStart}-${profile.allowedHoursEnd})")
            else -> { /* ACTIVE / PAUSED_RATE_LIMIT / STOPPED log via their own paths */ }
        }
    }

    /**
     * Pure decision function for the battery branch of [checkConstraints]. Extracted so
     * the bypass-while-charging behavior (issue #20) can be exercised without registering
     * BroadcastReceivers in tests.
     */
    internal fun shouldPauseForBattery(
        batteryLevel: Int,
        thresholdBattery: Int,
        thresholdCharging: Int,
        isCharging: Boolean
    ): Boolean {
        if(
            (isCharging && batteryLevel >= thresholdCharging)||
            (!isCharging && batteryLevel >= thresholdBattery)
        ) {
            return false
        }

        // Battery above threshold, don't pause
        return true
    }

    /** Returns true if the current local hour falls within the profile's allowed window.
     *  Delegates to [AllowedHours], the same predicate [PoissonScheduler] uses, so the
     *  constraint gate and the delay computation can never disagree (issue #124). */
    internal fun isWithinAllowedHours(profile: PoisonProfile, nowHour: Int = currentHourOfDay()): Boolean =
        AllowedHours.isWithin(nowHour, profile.allowedHoursStart, profile.allowedHoursEnd)

    /** Returns the constraint-check retry interval scaled by intensity (integer-divided).
     *  HIGH (200/hr) → 3.75s, MEDIUM (60/hr) → 12s, LOW (12/hr) → 60s. */
    private fun constraintCheckMs(actionsPerHour: Int): Long =
        maxOf(CONSTRAINT_CHECK_MIN_MS, CONSTRAINT_CHECK_BASE_MS / maxOf(1, actionsPerHour / 12).toLong())

    /** Current wall-clock hour-of-day (0-23), read from the injected [Clock]. */
    private fun currentHourOfDay(): Int =
        Calendar.getInstance().apply { timeInMillis = clock.currentTimeMillis() }.get(Calendar.HOUR_OF_DAY)

    /**
     * Mark the engine resigned and invoke the [onLongPause] callback so the FGS can
     * schedule a resume and tear itself down. The session-duration log is kept for
     * diagnostic value when reading exported logs.
     */
    private fun resignAndRecord(
        state: EngineState?,
        spec: ResumeSpec,
        reason: String? = null
    ) {
        hasResigned = true
        val sessionDurationMs = clock.elapsedRealtime() - engineStartElapsedMs
        val ctx = reason ?: state?.toString() ?: "unknown"
        Timber.i("Engine resigning ($ctx) after ${sessionDurationMs}ms; resume spec: $spec")
        onLongPause?.invoke(spec)
    }

    /**
     * Decide whether the engine should keep delay-looping in the FGS or resign
     * (stop the FGS and schedule a resume notification).
     *
     * Rules:
     * - [EngineState.PAUSED_QUIET_HOURS]: always resign — quiet hours can be 8+ hours long,
     *   so we release the FGS slot rather than spin idle.
     * - [EngineState.PAUSED_WIFI] / [EngineState.PAUSED_BATTERY]: resign once the pause has
     *   lasted [LONG_PAUSE_THRESHOLD_MS]. Short blips (commute, brief WiFi drop) keep the
     *   FGS up, but sustained pauses surrender it.
     */
    @VisibleForTesting
    internal fun decidePauseAction(
        state: EngineState,
        currentProfile: PoisonProfile,
        pauseElapsedMs: Long,
        @Suppress("UNUSED_PARAMETER") totalRuntimeMs: Long,
        nowMs: Long = clock.currentTimeMillis()
    ): PauseDecision {
        return when (state) {
            EngineState.PAUSED_QUIET_HOURS ->
                PauseDecision.Resign(ResumeSpec.AtTime(nextAllowedHoursStartMs(currentProfile, nowMs)))
            EngineState.PAUSED_WIFI ->
                if (pauseElapsedMs >= LONG_PAUSE_THRESHOLD_MS)
                    // When the user allows mobile data (mobileIntensity set), this pause can
                    // only mean "no network at all", so ANY connection should resume us.
                    // With mobile Off, only an unmetered network ends the pause (issue #62).
                    PauseDecision.Resign(
                        ResumeSpec.WhenConstraintMet(
                            network = if (currentProfile.mobileIntensity != null) NetworkType.CONNECTED
                            else NetworkType.UNMETERED
                        )
                    )
                else PauseDecision.Continue
            EngineState.PAUSED_BATTERY ->
                if (pauseElapsedMs >= LONG_PAUSE_THRESHOLD_MS)
                    PauseDecision.Resign(ResumeSpec.WhenConstraintMet(batteryNotLow = true))
                else PauseDecision.Continue
            else -> PauseDecision.Continue
        }
    }

    /**
     * Returns the epoch-ms timestamp of the next `allowedHoursStart` hour boundary
     * strictly after [nowMs]. Used to schedule the resume notification at the start of
     * the next active window after a quiet-hours pause.
     */
    @VisibleForTesting
    internal fun nextAllowedHoursStartMs(currentProfile: PoisonProfile, nowMs: Long): Long {
        val target = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, currentProfile.allowedHoursStart)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= nowMs) {
            target.add(Calendar.DAY_OF_MONTH, 1)
        }
        return target.timeInMillis
    }

    /**
     * Verifies that critical asset data loaded successfully.
     * Returns a list of human-readable warnings for any degraded subsystems.
     */
    private fun checkAssetHealth(): List<String> {
        val warnings = mutableListOf<String>()
        if (blocklist.loadFailed) {
            warnings.add("Safety blocklist failed to load — URL-based modules disabled")
        }
        // Trigger a load of one category to check if query banks are accessible
        if (queryBankManager.getQueries(CategoryPool.GAMING).isEmpty()) {
            warnings.add("Query banks failed to load — search noise may be generic")
        }
        if (crawlListManager.corpusSize() == 0) {
            warnings.add("URL corpus is empty — browsing modules will not function")
        }
        if (cityDatabase.cities.size <= 1) {
            warnings.add("City database failed to load — location noise limited to one city")
        }
        if (warnings.isNotEmpty()) {
            Timber.w("Asset health check: ${warnings.size} warning(s): $warnings")
        }
        return warnings
    }

    /** One-shot transport check used to seed the cache. */
    private fun checkTransportNow(): NetworkTransport {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return NetworkTransport.NONE
        val caps = cm.getNetworkCapabilities(network) ?: return NetworkTransport.NONE
        return classifyTransport(caps, lookupUnderlyingCaps())
    }

    companion object {
        /**
         * Pure classifier for "what kind of network is the engine on right now?", the
         * basis of per-network intensity (issue #62). Extracted so the VPN cases
         * (issue #59 — TrackerControl / other per-app VPNs make the active network appear
         * as `TRANSPORT_VPN`, so plain `hasTransport` checks miss the physical transport)
         * can be exercised without standing up a real ConnectivityManager.
         *
         * Mapping:
         *  - WiFi or ethernet → [NetworkTransport.WIFI] (unmetered bucket)
         *  - VPN whose underlying networks include non-VPN WiFi → [NetworkTransport.WIFI]
         *  - VPN otherwise (tunneled over cellular, or underlying unknown) →
         *    [NetworkTransport.CELLULAR] — when in doubt, bill it as mobile data so the
         *    engine never exceeds the user's mobile budget
         *  - cellular, or any other connected transport → [NetworkTransport.CELLULAR]
         *  - no capabilities → [NetworkTransport.NONE]
         */
        internal fun classifyTransport(
            activeCaps: NetworkCapabilities?,
            underlyingCaps: () -> List<NetworkCapabilities>
        ): NetworkTransport {
            if (activeCaps == null) return NetworkTransport.NONE
            if (activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) {
                return NetworkTransport.WIFI
            }
            if (activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                // Same unmetered bucket as the direct branch: WiFi or ethernet underneath.
                val unmeteredUnderneath = underlyingCaps().any {
                    (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                        !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                }
                return if (unmeteredUnderneath) NetworkTransport.WIFI else NetworkTransport.CELLULAR
            }
            return NetworkTransport.CELLULAR
        }

        /**
         * Legacy boolean view of [classifyTransport], kept because "is this WiFi for the
         * engine's purposes" remains a meaningful question (issue #59 test surface).
         */
        internal fun isWifiActive(
            activeCaps: NetworkCapabilities?,
            underlyingCaps: () -> List<NetworkCapabilities>
        ): Boolean = classifyTransport(activeCaps, underlyingCaps) == NetworkTransport.WIFI
    }
}

/**
 * Transport class the engine is currently running on, as cached from the default-network
 * callback. Drives per-network intensity (issue #62): WIFI runs [PoisonProfile.intensity],
 * CELLULAR runs [PoisonProfile.mobileIntensity] (null = pause), NONE always pauses.
 */
internal enum class NetworkTransport { WIFI, CELLULAR, NONE }

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
        // Seed the cache from the first emission and keep it up-to-date.
        // No runBlocking — getProfile() returns safe defaults until the first read completes.
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

    /** Cold flow of the persisted profile; emits on every DataStore change. */
    val profiles: kotlinx.coroutines.flow.Flow<PoisonProfile>
        get() = dataStore.data.map { prefsToProfile(it) }

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
        // The "OFF" sentinel (not key removal) distinguishes a user-chosen pause-on-mobile
        // from a legacy profile that has never written the key (which migrates from
        // WIFI_ONLY on read). WIFI_ONLY itself keeps being written, derived, so a
        // downgrade to a pre-0.3.2 build lands on the equivalent on/off behavior.
        prefs[com.fauxx.di.PreferenceKeys.MOBILE_INTENSITY] = p.mobileIntensity?.name ?: MOBILE_INTENSITY_OFF
        prefs[com.fauxx.di.PreferenceKeys.WIFI_ONLY] = (p.mobileIntensity == null)
        prefs[com.fauxx.di.PreferenceKeys.BATTERY_THRESHOLD_BATTERY] = p.batteryThresholdBattery
        prefs[com.fauxx.di.PreferenceKeys.BATTERY_THRESHOLD_CHARGING] = p.batteryThresholdCharging
        prefs[com.fauxx.di.PreferenceKeys.ALLOWED_HOURS_START] = p.allowedHoursStart
        prefs[com.fauxx.di.PreferenceKeys.ALLOWED_HOURS_END] = p.allowedHoursEnd
        prefs[com.fauxx.di.PreferenceKeys.LOG_RETENTION_DAYS] = p.logRetentionDays
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
        prefs[com.fauxx.di.PreferenceKeys.ADVERSARIAL_ALLOCATION_ENABLED] = p.adversarialAllocationEnabled
        prefs[com.fauxx.di.PreferenceKeys.THEME_MODE] = p.themeMode.name
        prefs[com.fauxx.di.PreferenceKeys.RESUME_ON_BOOT] = p.resumeOnBoot
        // Persist customUserAgent only when set; clear the key on null so a
        // subsequent prefsToProfile read returns null, not stale empty string.
        val ua = p.customUserAgent
        if (ua.isNullOrBlank()) {
            prefs.remove(com.fauxx.di.PreferenceKeys.CUSTOM_USER_AGENT)
        } else {
            prefs[com.fauxx.di.PreferenceKeys.CUSTOM_USER_AGENT] = ua
        }
    }

    private fun prefsToProfile(prefs: androidx.datastore.preferences.core.Preferences): PoisonProfile {
        val intensity = readIntensity(prefs)
        return PoisonProfile(
            enabled = prefs[com.fauxx.di.PreferenceKeys.ENABLED] ?: false,
            intensity = intensity,
            mobileIntensity = readMobileIntensity(prefs, intensity),
            batteryThresholdBattery = prefs[com.fauxx.di.PreferenceKeys.BATTERY_THRESHOLD_BATTERY] ?: 20,
            batteryThresholdCharging = prefs[com.fauxx.di.PreferenceKeys.BATTERY_THRESHOLD_CHARGING] ?: 20,
            allowedHoursStart = prefs[com.fauxx.di.PreferenceKeys.ALLOWED_HOURS_START] ?: 7,
            allowedHoursEnd = prefs[com.fauxx.di.PreferenceKeys.ALLOWED_HOURS_END] ?: 23,
            logRetentionDays = prefs[com.fauxx.di.PreferenceKeys.LOG_RETENTION_DAYS] ?: 7,
            searchPoisonEnabled = prefs[com.fauxx.di.PreferenceKeys.MODULE_SEARCH] ?: true,
            adPollutionEnabled = prefs[com.fauxx.di.PreferenceKeys.MODULE_AD] ?: true,
            locationSpoofEnabled = prefs[com.fauxx.di.PreferenceKeys.MODULE_LOCATION] ?: false,
            fingerprintEnabled = prefs[com.fauxx.di.PreferenceKeys.MODULE_FINGERPRINT] ?: true,
            cookieSaturationEnabled = prefs[com.fauxx.di.PreferenceKeys.MODULE_COOKIE] ?: true,
            appSignalEnabled = prefs[com.fauxx.di.PreferenceKeys.MODULE_APPSIGNAL] ?: false,
            dnsNoiseEnabled = prefs[com.fauxx.di.PreferenceKeys.MODULE_DNS] ?: true,
            layer1Enabled = prefs[com.fauxx.di.PreferenceKeys.LAYER1_ENABLED] ?: false,
            layer2Enabled = prefs[com.fauxx.di.PreferenceKeys.LAYER2_ENABLED] ?: false,
            layer3Enabled = prefs[com.fauxx.di.PreferenceKeys.LAYER3_ENABLED] ?: true,
            adversarialAllocationEnabled =
                prefs[com.fauxx.di.PreferenceKeys.ADVERSARIAL_ALLOCATION_ENABLED] ?: false,
            themeMode = runCatching {
                com.fauxx.ui.theme.ThemeMode.valueOf(
                    prefs[com.fauxx.di.PreferenceKeys.THEME_MODE]
                        ?: com.fauxx.ui.theme.ThemeMode.SYSTEM.name
                )
            }.getOrDefault(com.fauxx.ui.theme.ThemeMode.SYSTEM),
            resumeOnBoot = prefs[com.fauxx.di.PreferenceKeys.RESUME_ON_BOOT] ?: true,
            customUserAgent = prefs[com.fauxx.di.PreferenceKeys.CUSTOM_USER_AGENT]?.takeIf { it.isNotBlank() }
        )
    }

    /** Wi-Fi intensity, hardened like THEME_MODE: a corrupt enum name falls back to the
     *  default instead of crashing the read (the old bare `valueOf` was a latent crash). */
    private fun readIntensity(prefs: androidx.datastore.preferences.core.Preferences): com.fauxx.data.model.IntensityLevel =
        runCatching {
            com.fauxx.data.model.IntensityLevel.valueOf(
                prefs[com.fauxx.di.PreferenceKeys.INTENSITY]
                    ?: com.fauxx.data.model.IntensityLevel.MEDIUM.name
            )
        }.getOrDefault(com.fauxx.data.model.IntensityLevel.MEDIUM)

    /**
     * Mobile-data intensity with lazy migration from the legacy WIFI_ONLY toggle (issue #62):
     * - key present: "OFF" sentinel → null (pause on mobile); otherwise the enum name, with a
     *   corrupt value failing safe to null so the engine never burns mobile data by accident.
     * - key absent (pre-0.3.2 profile): legacy `wifiOnly == true` (or unset — the old default)
     *   → null; `wifiOnly == false` meant "run the single intensity on any network" → mirror
     *   the Wi-Fi [intensity].
     */
    private fun readMobileIntensity(
        prefs: androidx.datastore.preferences.core.Preferences,
        intensity: com.fauxx.data.model.IntensityLevel
    ): com.fauxx.data.model.IntensityLevel? {
        val raw = prefs[com.fauxx.di.PreferenceKeys.MOBILE_INTENSITY]
            ?: return if (prefs[com.fauxx.di.PreferenceKeys.WIFI_ONLY] != false) null else intensity
        if (raw == MOBILE_INTENSITY_OFF) return null
        return runCatching { com.fauxx.data.model.IntensityLevel.valueOf(raw) }.getOrNull()
    }

    private companion object {
        /** Stored sentinel for "paused on mobile data" in the MOBILE_INTENSITY pref. */
        const val MOBILE_INTENSITY_OFF = "OFF"
    }
}
