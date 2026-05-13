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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    /** Running but paused due to WiFi/battery/time constraints. */
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

/** Milliseconds in 24 hours. */
private const val MS_PER_DAY = 24 * 60 * 60 * 1000L

/** Sliding window duration for per-hour rate limiting. */
private const val RATE_LIMIT_WINDOW_MS = 60 * 60 * 1000L

/** Delay when rate limit is hit before rechecking. */
private const val RATE_LIMIT_PAUSE_MS = 15_000L

/** Maximum time to wait for all modules to finish stop() before giving up. */
private const val MODULE_STOP_TIMEOUT_MS = 2_000L

/**
 * Threshold for a "long pause" — wifi/battery pauses past this duration trigger the
 * engine to resign and switch to a WorkManager-constraint-driven resume notification,
 * freeing the foreground-service runtime budget.
 */
private const val LONG_PAUSE_THRESHOLD_MS = 30L * 60 * 1000

/**
 * Hard runtime safety net. Android 14+ kills `dataSync` foreground services at 6h of
 * cumulative background runtime per 24h. We resign at 5h to keep a 1h margin and avoid
 * the [android.app.RemoteServiceException.ForegroundServiceDidNotStopInTimeException]
 * crashes reported by F-Droid users.
 */
private const val FGS_HARD_LIMIT_MS = 5L * 60 * 60 * 1000

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
    private val budgetTracker: FgsBudgetTracker,
    /**
     * Dispatcher the engine's main loop runs on. Provided by [com.fauxx.di.DispatchersModule]
     * as [Dispatchers.IO] in production; tests substitute a `TestDispatcher` so `runLoop`
     * runs under the scheduler that controls virtual time.
     */
    @IoDispatcher private val loopDispatcher: CoroutineDispatcher
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

    /** Engine start time on [Clock.elapsedRealtime] for FGS runtime tracking. */
    private var engineStartElapsedMs: Long = 0L

    /** [Clock.elapsedRealtime] timestamp at the moment the current pause began. */
    private var pauseEnteredAtElapsedMs: Long = 0L

    /** State that triggered the current pause; used to detect state transitions. */
    private var lastPauseState: EngineState = EngineState.ACTIVE

    /** Set true once [runLoop] has invoked [onLongPause] for the current run, to avoid double-firing. */
    private var hasResigned: Boolean = false

    /**
     * Effective FGS-runtime limit for the *current* engine session, computed from
     * the persisted 24h budget on each [start]. Falls back to [FGS_HARD_LIMIT_MS]
     * if the budget tracker hasn't been initialised (cold cache). Treated as
     * "session cap" — when elapsed since start exceeds this, the engine resigns.
     */
    private var sessionBudgetMs: Long = FGS_HARD_LIMIT_MS

    /** Tracks consecutive failure count per module class name. */
    private val failureCounts = ConcurrentHashMap<String, Int>()

    /** Tracks when a circuit-broken module can next be retried (epoch ms). */
    private val circuitBreakerUntil = ConcurrentHashMap<String, Long>()

    // --- Cached constraint state (updated via BroadcastReceivers) ---
    private val cachedBatteryLevel = AtomicInteger(100)
    private val cachedOnWifi = AtomicBoolean(false)

    /** Today's successful action count, incremented on each action. Reset on day rollover. */
    private val todayActionCount = AtomicInteger(0)
    @Volatile
    private var actionCountDayStart = clock.currentTimeMillis().let { it - (it % MS_PER_DAY) }

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
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            cachedOnWifi.set(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        }

        override fun onLost(network: Network) {
            cachedOnWifi.set(false)
        }
    }

    /** Returns today's successful action count (thread-safe, cached). */
    @Synchronized
    fun getTodayActionCount(): Int {
        val now = clock.currentTimeMillis()
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

    /**
     * Register a handler invoked when [runLoop] decides the engine should resign
     * during a long constraint pause. The handler is responsible for scheduling
     * the resume notification (via [com.fauxx.service.ResumeScheduler]) and stopping
     * the foreground service. Safe to call before or after [start].
     */
    fun setOnLongPause(handler: ((ResumeSpec) -> Unit)?) {
        onLongPause = handler
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
        // Cap this session's effective limit by what the cross-session 24h budget
        // has left. min() because we never want to exceed FGS_HARD_LIMIT_MS even
        // with a fresh budget — that's our intra-session safety net.
        sessionBudgetMs = minOf(FGS_HARD_LIMIT_MS, budgetTracker.remainingBudgetMs())
        Timber.i("Engine session budget = ${sessionBudgetMs}ms (${sessionBudgetMs / 60_000}min)")
        registerConstraintReceivers()
        engineJob = scope.launch {
            // Sync targeting layer enable flags from persisted profile
            val savedProfile = profile.getProfile()
            targetingEngine.setLayer1Enabled(savedProfile.layer1Enabled)
            targetingEngine.setLayer2Enabled(savedProfile.layer2Enabled)
            targetingEngine.setLayer3Enabled(savedProfile.layer3Enabled)

            // Seed today's action count from DB once on start
            val dayStart = clock.currentTimeMillis().let { it - (it % MS_PER_DAY) }
            actionCountDayStart = dayStart
            todayActionCount.set(
                try { actionLogDao.countSince(dayStart).first() } catch (_: Exception) { 0 }
            )
            _engineState.value = EngineState.ACTIVE
            Timber.i("PoisonEngine started (layers: L1=${savedProfile.layer1Enabled}, L2=${savedProfile.layer2Enabled}, L3=${savedProfile.layer3Enabled})")
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
        val sessionDurationMs = if (engineStartElapsedMs != 0L && !hasResigned) {
            clock.elapsedRealtime() - engineStartElapsedMs
        } else 0L
        engineJob?.cancel()
        engineJob = null
        _engineState.value = EngineState.STOPPED
        onLongPause = null
        unregisterConstraintReceivers()
        val modules = allModules
        scope.launch {
            // Credit this user-initiated stop's session to the budget tracker if
            // we haven't already (resign paths record their own).
            if (sessionDurationMs > 0) {
                runCatching { budgetTracker.recordSession(sessionDurationMs) }
                    .onFailure { Timber.e(it, "Failed to record FGS session duration on stop") }
            }
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
        val sessionDurationMs = if (engineStartElapsedMs != 0L && !hasResigned) {
            clock.elapsedRealtime() - engineStartElapsedMs
        } else 0L
        engineJob?.cancel()
        engineJob = null
        _engineState.value = EngineState.STOPPED
        onLongPause = null
        unregisterConstraintReceivers()
        val modules = allModules
        // Fire-and-forget teardown; scope is cancelled after a short grace period so the
        // launched stop coroutine has a chance to run. Bounded by timeout to avoid leaks.
        scope.launch {
            if (sessionDurationMs > 0) {
                runCatching { budgetTracker.recordSession(sessionDurationMs) }
                    .onFailure { Timber.e(it, "Failed to record FGS session duration on destroy") }
            }
            withTimeoutOrNull(MODULE_STOP_TIMEOUT_MS) {
                modules.forEach { runCatching { it.stop() } }
            }
            scope.cancel()
            Timber.i("PoisonEngine destroyed")
        }
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
        }
    }

    private fun unregisterConstraintReceivers() {
        runCatching { context.unregisterReceiver(batteryReceiver) }
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)
                .unregisterNetworkCallback(networkCallback)
        }
    }

    private suspend fun runLoop() {
        // Tracks the last successfully-executed category so the scheduler can apply
        // human-like dwell time on cross-niche transitions. In-memory only — a fresh
        // engine run starts a fresh "browsing session," which is realistic.
        var lastCategory: CategoryPool? = null
        while (scope.isActive) {
            val currentProfile = profile.getProfile()

            val constraintRetryMs = constraintCheckMs(currentProfile.intensity.actionsPerHour)

            // Constraint checks
            val constraintState = checkConstraints(currentProfile)
            if (constraintState != null) {
                // Track pause-entry time on state transition so [decidePauseAction] can
                // measure how long we've been stuck on the same constraint.
                if (lastPauseState != constraintState) {
                    pauseEnteredAtElapsedMs = clock.elapsedRealtime()
                    lastPauseState = constraintState
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

            // Hard safety net even when constraints pass — protect the FGS budget so a
            // user who keeps the app active without ever pausing still doesn't crash.
            // sessionBudgetMs accounts for cross-session 24h cumulative usage.
            if (!hasResigned && clock.elapsedRealtime() - engineStartElapsedMs >= sessionBudgetMs) {
                val spec = ResumeSpec.AtTime(budgetTracker.nextWindowResetMs())
                resignAndRecord(state = null, spec = spec, reason = "FGS budget exhausted")
                return
            }

            // Per-hour rate limit: prune stale timestamps and check cap
            val rateLimitNow = clock.currentTimeMillis()
            val cutoff = rateLimitNow - RATE_LIMIT_WINDOW_MS
            while (recentActionTimestamps.peek()?.let { it < cutoff } == true) {
                recentActionTimestamps.poll()
            }
            if (recentActionTimestamps.size >= currentProfile.intensity.actionsPerHour) {
                _engineState.value = EngineState.PAUSED_RATE_LIMIT
                Timber.d("Rate limit reached: ${recentActionTimestamps.size}/${currentProfile.intensity.actionsPerHour} actions/hour")
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

            val module = availableModules.random()
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
                    val jitter = (baseBackoff * Random.nextFloat() * 0.25f).toLong()
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
                todayActionCount.incrementAndGet()
                recentActionTimestamps.add(clock.currentTimeMillis())
            }

            // Schedule next action, subtracting module execution time so the
            // inter-action interval (not post-action gap) matches the target rate.
            val execElapsed = clock.currentTimeMillis() - execStart
            val scheduledMs = scheduler.nextDelayMs(
                actionsPerHour = currentProfile.intensity.actionsPerHour,
                prev = lastCategory,
                next = category,
                allowedStart = currentProfile.allowedHoursStart,
                allowedEnd = currentProfile.allowedHoursEnd
            )
            // Update lastCategory only on success — failed actions shouldn't poison the
            // dwell signal (a circuit-broken module isn't really "browsing" anything).
            if (logEntry.success) lastCategory = category
            val effectiveDelay = maxOf(0L, scheduledMs - execElapsed)
            Timber.d("Action: $moduleName/$category exec=${execElapsed}ms scheduled=${scheduledMs}ms effective=${effectiveDelay}ms")
            delay(effectiveDelay)
        }
    }

    /**
     * Returns null if all constraints pass, or the specific [EngineState] pause reason.
     */
    private fun checkConstraints(currentProfile: PoisonProfile): EngineState? {
        if (currentProfile.wifiOnly && !cachedOnWifi.get()) {
            Timber.d("Paused: wifi-only mode, no wifi")
            return EngineState.PAUSED_WIFI
        }
        if (cachedBatteryLevel.get() < currentProfile.batteryThreshold) {
            Timber.d("Paused: battery below threshold")
            return EngineState.PAUSED_BATTERY
        }
        if (!isWithinAllowedHours(currentProfile)) {
            Timber.d("Paused: outside allowed hours (${currentProfile.allowedHoursStart}-${currentProfile.allowedHoursEnd})")
            return EngineState.PAUSED_QUIET_HOURS
        }
        return null
    }

    /** Returns true if the current local hour falls within the profile's allowed window.
     *  Handles midnight-wrap (e.g., start=22, end=6 means 22:00 to 06:00). */
    internal fun isWithinAllowedHours(profile: PoisonProfile, nowHour: Int = currentHourOfDay()): Boolean {
        val start = profile.allowedHoursStart
        val end = profile.allowedHoursEnd
        return if (start == end) {
            true // degenerate: treat as always allowed
        } else if (start < end) {
            nowHour in start until end
        } else {
            nowHour >= start || nowHour < end
        }
    }

    /** Returns the constraint-check retry interval scaled by intensity.
     *  HIGH (200/hr) → ~3.6s, MEDIUM (60/hr) → ~12s, LOW (12/hr) → 60s. */
    private fun constraintCheckMs(actionsPerHour: Int): Long =
        maxOf(CONSTRAINT_CHECK_MIN_MS, CONSTRAINT_CHECK_BASE_MS / maxOf(1, actionsPerHour / 12).toLong())

    /** Current wall-clock hour-of-day (0-23), read from the injected [Clock]. */
    private fun currentHourOfDay(): Int =
        Calendar.getInstance().apply { timeInMillis = clock.currentTimeMillis() }.get(Calendar.HOUR_OF_DAY)

    /**
     * Mark the engine resigned, credit this session's runtime to the cross-session
     * budget tracker, and invoke the [onLongPause] callback. Centralised so every
     * resign path (pause-triggered, hard-limit-triggered, budget-exhausted) records
     * the session — the original cumulative-budget gap came from forgetting one path.
     */
    private suspend fun resignAndRecord(
        state: EngineState?,
        spec: ResumeSpec,
        reason: String? = null
    ) {
        hasResigned = true
        val sessionDurationMs = clock.elapsedRealtime() - engineStartElapsedMs
        runCatching { budgetTracker.recordSession(sessionDurationMs) }
            .onFailure { Timber.e(it, "Failed to record FGS session duration") }
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
     *   far exceeding the 6h Android-14+ `dataSync` FGS budget.
     * - [EngineState.PAUSED_WIFI] / [EngineState.PAUSED_BATTERY]: resign once the pause has
     *   lasted [LONG_PAUSE_THRESHOLD_MS]. Short blips (commute, brief WiFi drop) keep the
     *   FGS up, but sustained pauses release the budget.
     * - Cumulative FGS runtime ≥ [FGS_HARD_LIMIT_MS]: always resign, regardless of state.
     *   This catches edge cases where the user is on cellular all day with wifiOnly off, or
     *   active hours span >5h continuously.
     */
    @VisibleForTesting
    internal fun decidePauseAction(
        state: EngineState,
        currentProfile: PoisonProfile,
        pauseElapsedMs: Long,
        totalRuntimeMs: Long,
        nowMs: Long = clock.currentTimeMillis()
    ): PauseDecision {
        if (totalRuntimeMs >= FGS_HARD_LIMIT_MS) {
            val spec: ResumeSpec = when (state) {
                EngineState.PAUSED_QUIET_HOURS ->
                    ResumeSpec.AtTime(nextAllowedHoursStartMs(currentProfile, nowMs))
                EngineState.PAUSED_WIFI ->
                    ResumeSpec.WhenConstraintMet(network = NetworkType.UNMETERED)
                EngineState.PAUSED_BATTERY ->
                    ResumeSpec.WhenConstraintMet(batteryNotLow = true)
                else -> ResumeSpec.AtTime(nowMs + 60 * 60 * 1000L)
            }
            return PauseDecision.Resign(spec)
        }

        return when (state) {
            EngineState.PAUSED_QUIET_HOURS ->
                PauseDecision.Resign(ResumeSpec.AtTime(nextAllowedHoursStartMs(currentProfile, nowMs)))
            EngineState.PAUSED_WIFI ->
                if (pauseElapsedMs >= LONG_PAUSE_THRESHOLD_MS)
                    PauseDecision.Resign(ResumeSpec.WhenConstraintMet(network = NetworkType.UNMETERED))
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
        prefs[com.fauxx.di.PreferenceKeys.THEME_MODE] = p.themeMode.name
        prefs[com.fauxx.di.PreferenceKeys.RESUME_ON_BOOT] = p.resumeOnBoot
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
            layer3Enabled = prefs[com.fauxx.di.PreferenceKeys.LAYER3_ENABLED] ?: true,
            themeMode = runCatching {
                com.fauxx.ui.theme.ThemeMode.valueOf(
                    prefs[com.fauxx.di.PreferenceKeys.THEME_MODE]
                        ?: com.fauxx.ui.theme.ThemeMode.SYSTEM.name
                )
            }.getOrDefault(com.fauxx.ui.theme.ThemeMode.SYSTEM),
            resumeOnBoot = prefs[com.fauxx.di.PreferenceKeys.RESUME_ON_BOOT] ?: true
        )
}
