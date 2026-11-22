package com.fauxx.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.work.NetworkType
import com.fauxx.di.IoDispatcher
import com.fauxx.engine.dns.CustomDns
import com.fauxx.engine.webview.TrackerWatch
import com.fauxx.service.ResumeSpec
import com.fauxx.util.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
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
import com.fauxx.engine.power.EngineWakeLock
import com.fauxx.engine.power.EngineWakeup
import com.fauxx.engine.scheduling.ActionDispatcher
import com.fauxx.engine.scheduling.AllowedHours
import com.fauxx.engine.scheduling.ActionPacer
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
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
    /** Running but paused because the current Wi-Fi network is marked metered by the
     *  system (a phone hotspot, or a network the user flagged as metered) and the mobile
     *  intensity is Off. Distinct from [PAUSED_WIFI] so the UI can say "this Wi-Fi is
     *  metered" instead of the misleading "waiting for a usable network" (issue #288). */
    PAUSED_METERED_WIFI,
    /** Running but paused because Android is blocking this app's network while the network
     *  itself is up (#335): Doze, app standby, an OEM sleep mode, Data Saver or background data
     *  turned off for Fauxx. Distinct from [PAUSED_WIFI] so the UI points at the restriction
     *  instead of "waiting for a usable network". Never resigns: there is no WorkManager
     *  constraint for "the block lifted", and the loop resumes on the network callback. */
    PAUSED_NETWORK_BLOCKED,
    PAUSED_BATTERY,
    /** Running but paused because Android's Battery Saver is on and the user asked Fauxx to
     *  stand down while it is (issue #313). Never resigns: the loop waits for Battery Saver to
     *  turn off and resumes at once. */
    PAUSED_BATTERY_SAVER,
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

/**
 * No sliding hour may hold more than this many times the rate (owner ruling on #322, 2026-11-22).
 * The session budget ([com.fauxx.engine.scheduling.ActionBudget]) already keeps to it at a steady
 * rate; this holds it when the rate changes, such as on leaving Wi-Fi for a lower mobile tier.
 */
private const val HOURLY_CEILING_FACTOR = 2

/** How much of the action log the budget and the hourly ceiling are rebuilt from at start. */
private const val BUDGET_REPLAY_MS = 24 * 60 * 60 * 1000L

/**
 * No action starts this close to the end of the allowed hours, so none is still running when the
 * window closes: a search session's 60 s budget plus the longest step it can be in (#322).
 */
private const val CLOSE_GUARD_MS = 2 * 60 * 1000L

/** How far past the close a wait in the close guard runs, so the next pass sees the window shut. */
private const val CLOSE_GUARD_SLACK_MS = 1_000L

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
 * Longest the engine holds the CPU awake for one action (#335). Actions finish well inside this;
 * it only bounds a module that hangs, so a stuck page load cannot keep the phone awake all night.
 * The engine releases the lock itself the moment the action and its bookkeeping are done.
 */
private const val ACTION_WAKE_LOCK_TIMEOUT_MS = 3L * 60 * 1000

/**
 * How many wakeups may end one wait before its deadline before the wait stops trusting them and
 * falls back to plain delays (#335). An early wakeup is rare (the alarm signal carries its own
 * sequence number, so a stale broadcast never counts); the bound only keeps a misbehaving wake
 * source from spinning the loop.
 */
private const val MAX_EARLY_WAKES = 3

/**
 * Elapsed realtime between two passes of a network pause beyond the wait in between that means
 * the CPU was suspended (#335). No pass runs while suspended, so a gap this much longer than the
 * wait is sleep, and it does not count toward the resign clock.
 */
private const val SUSPEND_GAP_SLACK_MS = 10_000L

/**
 * Threshold for a "long pause" — wifi/battery pauses past this duration trigger the
 * engine to resign and switch to a WorkManager-constraint-driven resume notification,
 * releasing the foreground service rather than spinning idle.
 */
private const val LONG_PAUSE_THRESHOLD_MS = 30L * 60 * 1000

/**
 * Delay before auto-resuming after the engine loop terminates by an uncaught exception (issue
 * #197). Long enough that a deterministic fault doesn't hot-loop, short enough that a transient
 * one self-heals without waiting on the 6h reconcile watchdog. The full flavor's exact-alarm
 * resume path auto-restarts the FGS at this time with no user interaction (#126).
 */
private const val LOOP_FAILURE_RESUME_DELAY_MS = 60_000L

/**
 * Longest wait between re-checks while paused for Battery Saver (issue #313). The end of Battery
 * Saver wakes the loop at once through [PoisonEngine]'s power-save receiver; this bound only
 * governs how soon other changes are noticed (the setting switched off, quiet hours starting),
 * and keeps the paused loop from waking every few seconds at HIGH intensity while the device is
 * trying to save power.
 */
private const val BATTERY_SAVER_RECHECK_MS = 60_000L

/**
 * Battery-level floor used when no stored preference exists at all. Mirrors the
 * [PoisonProfile.batteryThresholdBattery] / [PoisonProfile.batteryThresholdCharging] defaults, and
 * is the last-resort fallback in the pre-#216 migration path.
 */
private const val DEFAULT_BATTERY_THRESHOLD = 20

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
    private val pacer: ActionPacer,
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
    /**
     * Opt-in custom DNS for the WebView's traffic (#227). Started before any module so the first
     * page load already goes through the user's resolver; stopped after the modules. Defaults to
     * a no-op so engine tests need not stand one up.
     */
    private val customDns: CustomDns = CustomDns.NONE,
    /**
     * Holds the CPU awake while an action runs (#335). Defaults to a no-op so engine tests need
     * not stand one up.
     */
    private val wakeLock: EngineWakeLock = EngineWakeLock.NONE,
    /**
     * Wakes the CPU when the next action is due, so the engine keeps its pace with the screen
     * off (#335). Defaults to a plain delay so engine tests keep running on virtual time.
     */
    private val wakeup: EngineWakeup = EngineWakeup.NONE,
    /**
     * Checks whether something on the phone blocks Fauxx's requests to trackers (#341): resets its
     * verdict on start and probes now and then while the engine runs. Defaults to a no-op so engine
     * tests need not stand one up.
     */
    private val trackerWatch: TrackerWatch = TrackerWatch.NONE,
) {
    /**
     * Backstop for an uncaught exception in a direct child of [scope] (issue #197). A
     * [SupervisorJob] does NOT absorb a child failure, so without this the exception would reach
     * the app's default uncaught-exception handler ([com.fauxx.FauxxApp]) and crash the whole
     * process. The primary guard is the try/catch around the engineJob body in [start]; this
     * additionally catches anything launched separately on [scope].
     */
    private val loopExceptionHandler = CoroutineExceptionHandler { _, t -> handleLoopFailure(t) }

    private var scope = CoroutineScope(SupervisorJob() + loopDispatcher + loopExceptionHandler)
    private var engineJob: Job? = null

    /** The #341 tracker checks for the current session; see [startTrackerWatch]. */
    private var trackerJob: Job? = null

    /**
     * The asynchronous teardown launched by [stop] or [destroy]. A new session joins it before
     * starting anything (#227): otherwise an off/on toggle lets the old teardown, still waiting out
     * module stops, reach `customDns.stop()` AFTER the new session's `customDns.start()`, silently
     * turning custom DNS off for the whole new session.
     */
    @Volatile private var teardownJob: Job? = null

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

    /**
     * The run that owns the wake lock and the wakeup alarm (#335). Both are shared, so a loop
     * from a stopped run that is still unwinding must not release the lock or cancel the alarm a
     * newer run holds. [start] and [stop] move it on; every wake operation in the loop checks it.
     */
    @Volatile private var session: Long = 0L

    /** Elapsed realtime of the previous pass through a network pause (#335); 0 when none. */
    private var pauseLastPassAtElapsedMs: Long = 0L

    /** How long the loop waited after that pass, to tell an awake wait from a suspended CPU. */
    private var pauseLastWaitMs: Long = 0L

    /** Whether that pass found the device asleep. */
    private var pauseLastPassAsleep: Boolean = false

    /** Tracks consecutive failure count per module class name. */
    private val failureCounts = ConcurrentHashMap<String, Int>()

    /** Tracks when a circuit-broken module can next be retried (epoch ms). */
    private val circuitBreakerUntil = ConcurrentHashMap<String, Long>()

    // --- Cached constraint state (updated via BroadcastReceivers) ---
    private val cachedBatteryLevel = AtomicInteger(100)
    private val cachedIsCharging = AtomicBoolean(false)

    /** Whether Android's Battery Saver is on (issue #313). A flow so a paused loop can wake the
     *  moment it turns off, instead of on its next re-check. */
    private val cachedPowerSave = MutableStateFlow(false)

    /**
     * Transport class of the active network, kept fresh by [networkCallback]. Replaces the
     * old boolean `cachedOnWifi`: per-network intensity (issue #62) needs to distinguish
     * Wi-Fi from mobile data from no-network, not just "on Wi-Fi or not".
     */
    private val cachedTransport = java.util.concurrent.atomic.AtomicReference(NetworkTransport.NONE)

    /**
     * Whether Android is blocking this app's access to the default network (#335): Doze, app
     * standby or an OEM sleep mode can cut an app off while the network itself stays up, which
     * the transport alone does not show. Actions would only fail, so the engine treats it like
     * no network.
     */
    private val cachedNetworkBlocked = AtomicBoolean(false)

    /** Bumped on every network change so a network-paused loop can wake at once (#335). */
    private val networkChanges = MutableStateFlow(0L)

    /**
     * Bumped each time the device is plugged in or unplugged, so a wait can react to the charging
     * boost at once (#316) instead of on its next re-check. Battery level updates do not bump it.
     */
    private val chargingChanges = MutableStateFlow(0L)

    private val _rateConditions = MutableStateFlow(RateConditions())

    /**
     * Charging, network and Battery Saver state as the receivers last saw it (#316), for the UI
     * to show the charging boost with [IntensityPolicy]. Only meaningful while the engine runs:
     * the receivers are unregistered when it stops.
     */
    val rateConditions: StateFlow<RateConditions> = _rateConditions.asStateFlow()

    /** Record a charging state, waking any wait that watches for a change (#316). */
    private fun setCharging(charging: Boolean) {
        if (cachedIsCharging.getAndSet(charging) != charging) chargingChanges.value++
    }

    /** Republish [rateConditions] from the cached state. Cheap; called from every receiver. */
    private fun refreshRateConditions() {
        _rateConditions.value = RateConditions(
            charging = cachedIsCharging.get(),
            unmetered = cachedTransport.get() == NetworkTransport.WIFI && !cachedNetworkBlocked.get(),
            batterySaver = cachedPowerSave.value,
        )
    }

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
        powerSave: Boolean? = null,
        networkBlocked: Boolean? = null,
    ) {
        transport?.let { cachedTransport.set(it); networkChanges.value++ }
        networkBlocked?.let { cachedNetworkBlocked.set(it); networkChanges.value++ }
        batteryLevel?.let { cachedBatteryLevel.set(it) }
        charging?.let { setCharging(it) }
        powerSave?.let { cachedPowerSave.value = it }
        refreshRateConditions()
    }

    /** Today's successful action count, incremented on each action. Reset on day rollover. */
    private val todayActionCount = AtomicInteger(0)
    @Volatile
    private var actionCountDayStart = localDayStartMs(clock.currentTimeMillis())

    /**
     * Sliding window of successful action timestamps (epoch ms) for the hourly ceiling
     * ([HOURLY_CEILING_FACTOR] times the rate). Rebuilt from the action log when a run starts, and
     * pruned of entries older than [RATE_LIMIT_WINDOW_MS] on each check.
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
            // This broadcast arrives within a second or so of plugging in or unplugging (it is
            // what ACTION_POWER_CONNECTED/DISCONNECTED accompany), so the charging boost (#316)
            // needs no receiver of its own.
            setCharging(intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
            refreshRateConditions()
        }
    }

    /** ACTION_POWER_SAVE_MODE_CHANGED carries no extras; re-read the mode on each change. */
    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            cachedPowerSave.value = isPowerSaveModeNow()
            refreshRateConditions()
        }
    }

    private fun isPowerSaveModeNow(): Boolean =
        runCatching { context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true }
            .getOrDefault(false)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            cachedTransport.set(classifyTransport(caps, lookupUnderlyingCaps()))
            networkChanges.value++
            refreshRateConditions()
        }

        // API 29+. On older releases it is never called and the flag stays false.
        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            cachedNetworkBlocked.set(blocked)
            networkChanges.value++
            refreshRateConditions()
        }

        override fun onLost(network: Network) {
            cachedTransport.set(NetworkTransport.NONE)
            cachedNetworkBlocked.set(false)
            networkChanges.value++
            refreshRateConditions()
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

    /**
     * Every module. Dispatch picks from this uniformly at random (see [runLoop]), so the order
     * here carries no dispatch weight despite the historical name; it is also the stop order,
     * where order does not matter either.
     */
    private val allModules: List<Module> get() = listOf(
        searchModule, cookieModule, dnsModule,
        fingerprintModule, locationModule, adModule, appSignalModule
    )

    /** The modules that load pages in the phantom WebView pool. */
    private val browsingModules: List<Module> get() = listOf(searchModule, cookieModule, adModule, appSignalModule)

    /** Whether the last dispatch skipped the browsing modules for strict custom DNS (#333); logged on change only. */
    private var browsingWasBlocked = false


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
            scope = CoroutineScope(SupervisorJob() + loopDispatcher + loopExceptionHandler)
        }
        engineStartElapsedMs = clock.elapsedRealtime()
        pauseEnteredAtElapsedMs = 0L
        lastPauseState = EngineState.ACTIVE
        hasResigned = false
        hasSignaledActive = false
        pauseLastPassAtElapsedMs = 0L
        pauseLastPassAsleep = false
        val runSession = ++session
        registerConstraintReceivers()
        startTrackerWatch()
        engineJob = scope.launch {
            try {
                runEngineSession(runSession)
            } catch (ce: CancellationException) {
                throw ce // normal stop()/destroy() cancellation — must propagate
            } catch (t: Throwable) {
                // #197: an uncaught exception from the loop machinery (profile.getProfile,
                // checkConstraints, dispatcher.selectCategory, the pacer, the
                // rate-limit block, sleepRespectingConstraints) would otherwise escape this
                // SupervisorJob child, reach the app's default uncaught handler (FauxxApp), and
                // crash the whole process — leaving isRunning=true so EngineReconcileWorker could
                // not recover. Catch it, mark STOPPED, and resign so recovery is scheduled.
                handleLoopFailure(t)
            }
        }
    }

    /**
     * The engine session body: sync targeting flags, seed counters, start modules, then enter the
     * run loop. Extracted from [start] so the launch can wrap it in the #197 crash guard. Runs on
     * the engine [scope] / [loopDispatcher].
     */
    private suspend fun runEngineSession(runSession: Long) {
        teardownJob?.join()
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
        startPacing(savedProfile)
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

        // Custom DNS before any module browses (#227). A failure here never stops the engine from
        // starting: by default the system resolver stays in use, and in strict mode (#333) the
        // pool's browsing gate keeps modules from browsing until the WebView is routed.
        try {
            customDns.start()
        } catch (ce: CancellationException) {
            throw ce // stop() during start: do not go on to start modules
        } catch (e: Exception) {
            Timber.w(e, "Custom DNS failed to start")
        }

        allModules.filter { it.isEnabled() }.forEach { module ->
            try {
                module.start()
            } catch (e: Exception) {
                val name = module::class.simpleName ?: "Unknown"
                Timber.e(e, "Module $name failed to start, circuit-breaking")
                circuitBreakerUntil[name] = clock.currentTimeMillis() + MAX_BACKOFF_MS
            }
        }
        runLoop(runSession)
    }

    /**
     * Rebuild the pacing from the action log as a run starts (#322): the hourly ceiling's window gets
     * the past hour's successful actions and the pacer replays the day's into its budget, so stopping
     * and starting the engine cannot hand out a fresh allowance. If the log cannot be read, both
     * start empty.
     */
    private suspend fun startPacing(savedProfile: PoisonProfile) {
        val now = clock.currentTimeMillis()
        val logged = try {
            actionLogDao.successTimestampsSince(now - BUDGET_REPLAY_MS)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Timber.w(e, "Could not read the action log to rebuild the action budget")
            emptyList()
        }
        recentActionTimestamps.clear()
        logged.filter { it > now - RATE_LIMIT_WINDOW_MS }.sorted().forEach { recentActionTimestamps.add(it) }
        val rate = (effectiveIntensity(savedProfile) ?: savedProfile.intensity).actionsPerHour
        pacer.startRun(now, rate, savedProfile.allowedHoursStart, savedProfile.allowedHoursEnd, logged)
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
        trackerJob?.cancel()
        trackerJob = null
        session++ // the cancelled loop no longer owns the lock or the alarm
        releaseWake()
        _engineState.value = EngineState.STOPPED
        onLongPause = null
        onActive = null
        unregisterConstraintReceivers()
        val modules = allModules
        // Chained: a new session joins only the latest teardown, so each one first waits for the
        // one before it. Otherwise a quick off/on/off/on lets an older, slower teardown stop the
        // new session's modules and custom DNS after that session has started them.
        val previous = teardownJob
        teardownJob = scope.launch {
            previous?.join()
            withTimeoutOrNull(MODULE_STOP_TIMEOUT_MS) {
                modules.forEach { runCatching { it.stop() } }
            } ?: Timber.w("Module stop timed out after ${MODULE_STOP_TIMEOUT_MS}ms")
            runCatching { customDns.stop() }
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
        trackerJob?.cancel()
        trackerJob = null
        session++
        releaseWake()
        _engineState.value = EngineState.STOPPED
        onLongPause = null
        onActive = null
        unregisterConstraintReceivers()
        val modules = allModules
        val previous = teardownJob
        // The teardown runs on the scope being destroyed, which it cancels when done. A start()
        // arriving meanwhile gets a fresh scope at once; reusing the doomed one would let this
        // teardown's cancel() silently kill the new session.
        val doomed = scope
        scope = CoroutineScope(SupervisorJob() + loopDispatcher + loopExceptionHandler)
        // Fire-and-forget, bounded by timeout to avoid leaks; chained like stop()'s.
        teardownJob = doomed.launch {
            previous?.join()
            withTimeoutOrNull(MODULE_STOP_TIMEOUT_MS) {
                modules.forEach { runCatching { it.stop() } }
            }
            runCatching { customDns.stop() }
            doomed.cancel()
            Timber.i("PoisonEngine destroyed")
        }
    }

    /** The #341 tracker checks run beside the loop, not in it, so a failure there never stops it. */
    private fun startTrackerWatch() {
        trackerJob?.cancel()
        trackerJob = scope.launch {
            try {
                trackerWatch.watch(engineState)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Timber.w(e, "Tracker block check stopped")
            }
        }
    }

    private fun registerConstraintReceivers() {
        // Seed battery level + charging state from sticky broadcast. This is also how a start while
        // already plugged in gets the charging boost (#316) from its first pass.
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent != null) {
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) cachedBatteryLevel.set(level * 100 / scale)
            setCharging(batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
        }
        // Seed network transport state
        cachedTransport.set(checkTransportNow())
        cachedNetworkBlocked.set(false)

        // Register ongoing receivers. Battery still uses a broadcast; connectivity uses
        // NetworkCallback (CONNECTIVITY_ACTION was deprecated in API 28).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
            context.registerReceiver(
                powerSaveReceiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            context.registerReceiver(powerSaveReceiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        }
        // Seeded AFTER registering: the change broadcast is not sticky, so a change landing between
        // a seed and the registration would otherwise be lost until the next one (#313).
        cachedPowerSave.value = isPowerSaveModeNow()
        refreshRateConditions()
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
        runCatching { context.unregisterReceiver(powerSaveReceiver) }
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)
                .unregisterNetworkCallback(networkCallback)
        }
        runCatching { usageObserver.stop() }
    }

    private suspend fun runLoop(s: Long) {
        // The charging boost last logged (#316), so the log says once when it starts and stops.
        var loggedBoost: IntensityLevel? = null
        while (scope.isActive) {
            val currentProfile = profile.getProfile()
            // Read before the constraint check, so a network change landing while this pass
            // checks and logs still ends the network-pause wait below (#335).
            val networkSeen = networkChanges.value

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
            // What kind of pause this is, judged once from the same network state the check
            // saw: the transport can change while the pass logs and reads power state (#335).
            val networkSettingsPause = constraintState != null && isNetworkSettingsPause(constraintState)
            val networkPause = constraintState != null && isNetworkPause(constraintState)
            if (constraintState != null) {
                if (lastPauseState != constraintState) {
                    // Start the cumulative pause clock only on the ACTIVE→pause edge. Keeping
                    // it running across pause→pause transitions (e.g. a wifi↔battery flicker)
                    // means the resign threshold measures total time paused, not time in the
                    // current state — otherwise a flicker resets the clock every few seconds
                    // and the engine never resigns (#158).
                    // A Battery Saver pause counts as running for this clock: it never resigns, and
                    // if its hours counted, the first brief network or battery blip after it would
                    // resign at once instead of after 30 minutes (#313). A blocked-network pause
                    // never resigns either, so the same goes for it (#335).
                    if (lastPauseState == EngineState.ACTIVE ||
                        lastPauseState == EngineState.PAUSED_BATTERY_SAVER ||
                        lastPauseState == EngineState.PAUSED_NETWORK_BLOCKED
                    ) {
                        pauseEnteredAtElapsedMs = clock.elapsedRealtime()
                    }
                    lastPauseState = constraintState
                    // Log the reason once per transition, not on every retry tick (#158).
                    logPauseReason(constraintState, currentProfile)
                }
                _engineState.value = constraintState
                // Nothing to do while paused; let the CPU sleep (#335).
                releaseLock(s)

                // A network pause that comes from the device sleeping is not a reason to give up
                // the service (#335). Doze, app standby and OEM sleep modes cut background network
                // access, or drop Wi-Fi so the default network becomes mobile data or a metered
                // hotspot, while the screen is off, and restore it in maintenance windows or when
                // the user returns. Resigning there hands recovery to a tap-to-resume notification,
                // which is how the engine stayed dead until the user picked the phone up. So for
                // every network pause the 30-minute resign clock counts only time paused while
                // awake: it restarts on a pass that finds the device asleep, on the first awake
                // pass after one, and after any gap between passes far longer than the wait
                // between them, since no pass runs while the CPU is suspended.
                val idleInducedNetworkPause = networkSettingsPause && isDeviceAsleep()
                val passAt = clock.elapsedRealtime()
                if (networkSettingsPause) {
                    val suspendedSinceLastPass = pauseLastPassAtElapsedMs != 0L &&
                        passAt - pauseLastPassAtElapsedMs > pauseLastWaitMs + SUSPEND_GAP_SLACK_MS
                    if (idleInducedNetworkPause || pauseLastPassAsleep || suspendedSinceLastPass) {
                        pauseEnteredAtElapsedMs = passAt
                    }
                }
                pauseLastPassAtElapsedMs = passAt
                pauseLastPassAsleep = idleInducedNetworkPause

                if (!hasResigned) {
                    val pauseElapsedMs = clock.elapsedRealtime() - pauseEnteredAtElapsedMs
                    val totalRuntimeMs = clock.elapsedRealtime() - engineStartElapsedMs
                    val decision = decidePauseAction(
                        constraintState, currentProfile, pauseElapsedMs, totalRuntimeMs,
                        idleInducedNetworkPause = idleInducedNetworkPause,
                    )
                    if (decision is PauseDecision.Resign) {
                        resignAndRecord(constraintState, decision.resumeSpec)
                        return
                    }
                }

                if (constraintState == EngineState.PAUSED_BATTERY_SAVER) {
                    pauseLastWaitMs = BATTERY_SAVER_RECHECK_MS
                    // Wake the moment Battery Saver turns off rather than on the next tick (#313).
                    withTimeoutOrNull(BATTERY_SAVER_RECHECK_MS) { cachedPowerSave.first { !it } }
                        // No change seen: re-read it anyway, in case a broadcast was missed (the
                        // system does not resend it) or an OEM build never sends one.
                        ?: run { cachedPowerSave.value = isPowerSaveModeNow() }
                } else if (networkPause) {
                    pauseLastWaitMs = constraintRetryMs
                    // Wake the moment the network changes rather than on the next tick (#335). A
                    // dozing device delivers the change when it opens a maintenance window; a
                    // timer alone could sleep straight through that window.
                    withTimeoutOrNull(constraintRetryMs) { networkChanges.first { it != networkSeen } }
                } else {
                    pauseLastWaitMs = constraintRetryMs
                    delay(constraintRetryMs)
                }
                continue
            }

            // Reset pause tracking on a successful constraint check
            lastPauseState = EngineState.ACTIVE
            pauseEnteredAtElapsedMs = 0L
            pauseLastPassAtElapsedMs = 0L
            pauseLastPassAsleep = false

            // checkConstraints re-evaluated the transport freshly and passed; this is the
            // loop-top SNAPSHOT, which a mid-iteration transport flip can have left null.
            // The elvis re-enters the loop so the pause branch can handle that flip; when
            // non-null-but-stale (flip between tiers), the wrong tier governs for at most
            // this one iteration before the loop-top recompute corrects it.
            val activeIntensity = effectiveIntensity ?: continue

            // Say once when the charging boost starts or stops shaping the rate (#316), so an
            // exported log explains a change of pace.
            val boost = currentChargingBoost(currentProfile)
            if (boost != loggedBoost) {
                if (boost != null) {
                    Timber.i("Charging boost on: ${currentProfile.intensity} -> $boost while charging on unmetered Wi-Fi")
                } else {
                    Timber.i("Charging boost off: back to ${activeIntensity.name}")
                }
                loggedBoost = boost
            }

            // First time we clear the constraint gate this run (with a usable network),
            // tell the FGS the engine has committed to running so it can retire any stale
            // pending resume (#156). Fires after the gate, never at start, so a still-needed
            // resume is never cancelled before the engine actually takes over from it.
            if (!hasSignaledActive) {
                hasSignaledActive = true
                runCatching { onActive?.invoke() }
                    .onFailure { Timber.e(it, "onActive handler failed") }
            }

            // Hourly ceiling: prune stale timestamps and check that the past hour holds fewer than
            // HOURLY_CEILING_FACTOR times the rate. The average is the pacer's budget; this bounds
            // a single hour. The window is deliberately network-agnostic: after a HIGH-on-WiFi
            // hour, dropping to mobile LOW keeps the engine paused until the window drains below
            // the mobile ceiling: the whole point of a lower mobile tier is not to burn mobile data.
            val rateLimitNow = clock.currentTimeMillis()
            val cutoff = rateLimitNow - RATE_LIMIT_WINDOW_MS
            while (recentActionTimestamps.peek()?.let { it < cutoff } == true) {
                recentActionTimestamps.poll()
            }
            val hourlyCeiling = activeIntensity.actionsPerHour * HOURLY_CEILING_FACTOR
            if (recentActionTimestamps.size >= hourlyCeiling) {
                _engineState.value = EngineState.PAUSED_RATE_LIMIT
                Timber.d("Hourly ceiling reached: ${recentActionTimestamps.size}/$hourlyCeiling actions in the past hour")
                // Sleep until the oldest action leaves the window, through the alarm-backed sleep:
                // one wakeup instead of a re-check every 15 s, which with the screen off would
                // wake the phone up to 240 times an hour while the window drains (#335). A change
                // of rate (a new intensity, a move between Wi-Fi and mobile, or plugging in with a
                // charging boost, #316) ends it early.
                val oldest = recentActionTimestamps.peek() ?: rateLimitNow
                val untilDrained = (oldest + RATE_LIMIT_WINDOW_MS - rateLimitNow)
                    .coerceAtLeast(RATE_LIMIT_PAUSE_MS)
                val cap = activeIntensity.actionsPerHour
                sleepRespectingConstraints(
                    untilDrained, s, endOnNetworkChangeFrom = networkSeen, watchCharging = true,
                ) {
                    effectiveIntensity(profile.getProfile())?.actionsPerHour != cap
                }
                continue
            }

            // No action starts so close to the end of the allowed hours that it could still be
            // running when they end (#322). Wait out the rest of the window; the next pass then
            // finds it closed and pauses as usual.
            val untilClose = AllowedHours.msUntilClose(
                rateLimitNow, currentProfile.allowedHoursStart, currentProfile.allowedHoursEnd,
            )
            if (untilClose != null && untilClose < CLOSE_GUARD_MS) {
                _engineState.value = EngineState.ACTIVE
                sleepRespectingConstraints(untilClose + CLOSE_GUARD_SLACK_MS, s)
                continue
            }

            // The session budget (#322): between sessions, or when a run starts with nothing saved,
            // wait until an action is paid for. A change of rate ends the wait early so it is
            // measured again at the new rate.
            val untilAllowed = pacer.msUntilAllowed(rateLimitNow, activeIntensity.actionsPerHour)
            if (untilAllowed > 0) {
                _engineState.value = EngineState.ACTIVE
                val rate = activeIntensity.actionsPerHour
                sleepRespectingConstraints(
                    untilAllowed, s, endOnNetworkChangeFrom = networkSeen, watchCharging = true,
                ) {
                    effectiveIntensity(profile.getProfile())?.actionsPerHour != rate
                }
                continue
            }

            _engineState.value = EngineState.ACTIVE

            // Pick next category: the current burst's topic, or a fresh draw from the weights.
            val category = pacer.nextCategory { dispatcher.selectCategory() }

            // Pick an enabled module that isn't circuit-broken
            val now = clock.currentTimeMillis()
            // Strict custom DNS with the WebView not routed (#333): browsing is off until routing
            // is back, which the router retries on its own. A quiet state, not a failure per tick:
            // the browsing modules are left out of dispatch, and the change is logged once.
            val browsingBlocked = customDns.browsingBlocked()
            if (browsingBlocked != browsingWasBlocked) {
                browsingWasBlocked = browsingBlocked
                if (browsingBlocked) {
                    Timber.i("Browsing paused: strict custom DNS cannot route the WebView yet")
                } else {
                    Timber.i("Browsing resumed: strict custom DNS routes the WebView again")
                }
            }
            val skipped = if (browsingBlocked) browsingModules else emptyList()
            val availableModules = allModules.filter { module ->
                val name = module::class.simpleName ?: return@filter false
                module !in skipped && module.isEnabled() && !module.isHeldBack() &&
                    (circuitBreakerUntil[name] ?: 0L) <= now
            }
            if (availableModules.isEmpty()) {
                // When the first enabled module may retry. A permanent break (Long.MAX_VALUE, a
                // failed blocklist) never comes due, and a disabled module's backoff is irrelevant.
                val nextRetryAt = allModules
                    .filter { it.isEnabled() }
                    .mapNotNull { circuitBreakerUntil[it::class.simpleName ?: return@mapNotNull null] }
                    .filter { it in (now + 1) until Long.MAX_VALUE }
                    .minOrNull()
                if (nextRetryAt != null) {
                    // Every enabled module is backing off: wake when the first may retry (#335).
                    sleepRespectingConstraints(
                        (nextRetryAt - now).coerceIn(constraintRetryMs, MAX_BACKOFF_MS), s
                    )
                } else {
                    // Nothing that can run: no reason to wake the phone. Settings changes come
                    // from the app, which has the device awake.
                    releaseLock(s)
                    delay(constraintRetryMs)
                }
                continue
            }

            val module = pickModule(availableModules)
            val moduleName = module::class.simpleName ?: "Unknown"

            // Re-check enable state to avoid acting on a stale snapshot
            if (!module.isEnabled()) continue

            // Hold the CPU for the action and the bookkeeping after it (#335). Released when the
            // loop next waits; the timeout only bounds a module that hangs.
            acquireLock(s)

            // Time the action: the pacer sizes its reading pauses around how long actions take.
            val execStart = clock.currentTimeMillis()
            val logEntry = try {
                module.onAction(category).also {
                    // Success — reset failure counter
                    failureCounts.remove(moduleName)
                }
            } catch (ce: CancellationException) {
                // stop() during the action. Not a module failure: rethrow, or this cancelled loop
                // would go on to count a failure and wait, touching the lock and alarm that a
                // newly started run may already own (#335).
                throw ce
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
                sleepRespectingConstraints(FAILURE_RETRY_DELAY_MS, s)
                continue
            }
            try {
                actionLogDao.insert(logEntry)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Timber.e(e, "Failed to insert action log entry")
            }
            if (logEntry.success) {
                recordTodayAction(clock.currentTimeMillis())
                recentActionTimestamps.add(clock.currentTimeMillis())
            }

            // The wait before the next action (#322): a think pause inside a burst, a reading pause
            // between bursts, or a break after the session. It runs from the end of this action,
            // as a person's pause does, instead of having the action's run time taken off it,
            // which made the cadence regular (#382). The pacer keys topic changes off what the
            // module actually emitted (logEntry.category): a module may redirect the action (E8:
            // AppSignal's persona-interest swap), and cross-niche pacing only protects against
            // bot signals if it tracks the visible stream.
            val execElapsed = clock.currentTimeMillis() - execStart
            val delayMs = pacer.afterAction(
                actionsPerHour = activeIntensity.actionsPerHour,
                executed = logEntry.category,
                success = logEntry.success,
                execMs = execElapsed,
                allowedStart = currentProfile.allowedHoursStart,
                allowedEnd = currentProfile.allowedHoursEnd,
            )
            Timber.d("Action: $moduleName/$category exec=${execElapsed}ms next in ${delayMs}ms")
            // Plugging in mid-wait with a charging boost set redraws the rest of the wait at the
            // boosted rate (#316), so a LOW wait of many minutes does not hold the boost back.
            sleepRespectingConstraints(delayMs, s, redrawOnBoostFrom = activeIntensity.actionsPerHour)
        }
    }

    /**
     * The module for the next action. Evenly among [available], except that the pacer decides how
     * likely a search is: it puts at most one search in a burst, at its start (#322), since a person
     * searches and then reads rather than searching again every few seconds.
     */
    private fun pickModule(available: List<Module>): Module {
        if (searchModule !in available || available.size == 1) return available.random(random)
        val others = available.filter { it !== searchModule }
        return if (random.nextDouble() < pacer.searchChance(available.size)) searchModule else others.random(random)
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
    private suspend fun sleepRespectingConstraints(
        totalMs: Long,
        s: Long,
        /**
         * When set, a network change after this [networkChanges] value ends the sleep at once.
         * A change of network can change the allowed rate, and with the CPU suspended the
         * [stopEarly] check between chunks would not run until the drain alarm (#335).
         */
        endOnNetworkChangeFrom: Long? = null,
        /**
         * When true, plugging in or unplugging is checked against [stopEarly] at once (#316)
         * rather than at the end of the current chunk, which with the CPU suspended would be the
         * deadline.
         */
        watchCharging: Boolean = false,
        /**
         * The rate (actions/hour) this wait was drawn at. When set, plugging in mid-wait so that
         * the charging boost raises the rate redraws the rest of the wait at the new rate (#316).
         * The pacer draws the same kind of wait afresh at the new rate: a break, for one, ends
         * sooner because the budget then saves up faster. A wait is never lengthened: the
         * redraw only ever moves the deadline earlier, and unplugging leaves it alone (the next
         * wait is drawn at the lower rate).
         */
        redrawOnBoostFrom: Int? = null,
        stopEarly: (() -> Boolean)? = null,
    ) {
        // No wait: keep holding the lock straight into the next action. Releasing here, with no
        // alarm set, would let the CPU suspend mid-loop at HIGH intensity, where the gap is often
        // shorter than the action (#335).
        if (totalMs <= 0) return
        // Nothing to do until the next action; let the CPU sleep (#335).
        releaseLock(s)
        // Coroutine delays run on a clock that stops while the CPU is suspended, so with the
        // screen off a delay alone never ends. An alarm on elapsed realtime, which keeps counting
        // through sleep, wakes the CPU when the wait is due (#335). The wait ends when EITHER the
        // delays add up to [totalMs] (the device stayed awake: exactly the old pacing) OR elapsed
        // realtime reaches the deadline (the device slept through some of it).
        //
        // Every pass suspends, so the loop can never spin: elapsed realtime is only consulted
        // AFTER a wait. A wakeup that ends a wait before the deadline means the alarm fired early,
        // so it is set again and the wait goes on; past [MAX_EARLY_WAKES] the rest of this sleep
        // stops trusting the wake source and uses plain delays.
        var deadline = clock.elapsedRealtime() + totalMs
        var target = totalMs
        var drawnAt = redrawOnBoostFrom
        var chargingSeen = if (watchCharging || redrawOnBoostFrom != null) chargingChanges.value else null
        scheduleWakeup(s, deadline)
        try {
            var slept = 0L
            var earlyWakes = 0
            while (slept < target) {
                val realRemaining = (deadline - clock.elapsedRealtime()).coerceAtLeast(1L)
                val chunk = minOf(target - slept, realRemaining, SLEEP_CONSTRAINT_RECHECK_MS)
                val outcome = awaitChunk(
                    chunk, trustWakeup = earlyWakes < MAX_EARLY_WAKES, endOnNetworkChangeFrom, chargingSeen,
                )
                if (outcome == WaitOutcome.NETWORK_CHANGED) return
                if (outcome == WaitOutcome.CHARGING_CHANGED) {
                    // Plugged in or unplugged (#316). Not time slept: the deadline still bounds the
                    // wait, and the next chunk is sized from it.
                    chargingSeen = chargingChanges.value
                    val current = profile.getProfile()
                    if (checkConstraints(current) != null) return
                    if (stopEarly?.invoke() == true) return
                    val from = drawnAt
                    val rate = effectiveIntensity(current)?.actionsPerHour
                    if (from != null && rate != null && rate > from) {
                        val redrawn = pacer.redrawDelayMs(rate)
                        val now = clock.elapsedRealtime()
                        if (now + redrawn < deadline) {
                            deadline = now + redrawn
                            target = slept + redrawn
                            scheduleWakeup(s, deadline)
                            Timber.d("Charging boost: wait redrawn at $rate/h, next action in ${redrawn}ms")
                        }
                        drawnAt = rate
                    }
                    continue
                }
                val woken = outcome == WaitOutcome.WOKEN
                // A wakeup is not time slept; elapsed realtime decides instead.
                if (!woken) slept += chunk
                if (clock.elapsedRealtime() >= deadline) return
                if (woken) {
                    earlyWakes++
                    scheduleWakeup(s, deadline) // the alarm that fired is spent
                    releaseHandover(s) // back to waiting: the alarm's hold is no longer needed
                }
                if (slept < target) {
                    if (checkConstraints(profile.getProfile()) != null) return
                    if (stopEarly?.invoke() == true) return
                }
            }
        } finally {
            if (s == session) runCatching { wakeup.cancel() }
        }
    }

    private enum class WaitOutcome { ELAPSED, WOKEN, NETWORK_CHANGED, CHARGING_CHANGED }

    /**
     * Wait one chunk: for the wakeup (or a plain delay once wakeups are no longer trusted), and,
     * when [networkSeen] is set, for a network change after it, and, when [chargingSeen] is set,
     * for plugging in or unplugging after it (#316), whichever comes first.
     */
    private suspend fun awaitChunk(
        chunk: Long,
        trustWakeup: Boolean,
        networkSeen: Long?,
        chargingSeen: Long? = null,
    ): WaitOutcome {
        suspend fun timed(): WaitOutcome =
            if (trustWakeup) {
                if (wakeup.await(chunk)) WaitOutcome.WOKEN else WaitOutcome.ELAPSED
            } else {
                delay(chunk)
                WaitOutcome.ELAPSED
            }
        if (networkSeen == null && chargingSeen == null) return timed()
        if (networkSeen != null && networkChanges.value != networkSeen) return WaitOutcome.NETWORK_CHANGED
        if (chargingSeen != null && chargingChanges.value != chargingSeen) return WaitOutcome.CHARGING_CHANGED
        return coroutineScope {
            val timer = async { timed() }
            val network = networkSeen?.let { seen ->
                async {
                    networkChanges.first { it != seen }
                    WaitOutcome.NETWORK_CHANGED
                }
            }
            val charging = chargingSeen?.let { seen ->
                async {
                    chargingChanges.first { it != seen }
                    WaitOutcome.CHARGING_CHANGED
                }
            }
            select<WaitOutcome> {
                timer.onAwait { it }
                network?.onAwait { it }
                charging?.onAwait { it }
            }.also {
                timer.cancel()
                network?.cancel()
                charging?.cancel()
            }
        }
    }

    /** Take the CPU for an action, if [s] is still the current run. */
    private fun acquireLock(s: Long) {
        if (s != session) return
        wakeLock.acquire(ACTION_WAKE_LOCK_TIMEOUT_MS)
        // The engine holds the CPU itself now; the wakeup's short hold can go (#335).
        wakeup.releaseHandover()
    }

    /** Let the CPU sleep, unless a newer run owns the lock now. */
    private fun releaseLock(s: Long) {
        if (s != session) return
        wakeLock.release()
        wakeup.releaseHandover()
    }

    /** Drop the wakeup's short hold on the CPU, unless a newer run owns the wakeup now. */
    private fun releaseHandover(s: Long) {
        if (s == session) wakeup.releaseHandover()
    }

    /** Set the next-action wakeup, unless a newer run owns the alarm now. */
    private fun scheduleWakeup(s: Long, atElapsedRealtimeMs: Long) {
        if (s == session) wakeup.schedule(atElapsedRealtimeMs)
    }

    /** Let the CPU sleep and drop any pending wakeup. Safe to call more than once. */
    private fun releaseWake() {
        runCatching { wakeup.cancel() }
        runCatching { wakeLock.release() }
    }

    /** A pause because the current network may not be used: none at all, or mobile data or a
     *  metered Wi-Fi with the mobile tier Off. These resign after 30 awake minutes. */
    private fun isNetworkSettingsPause(state: EngineState): Boolean =
        state == EngineState.PAUSED_WIFI || state == EngineState.PAUSED_METERED_WIFI

    /** A pause that ends on a network change, so the loop waits on the network callback. */
    private fun isNetworkPause(state: EngineState): Boolean =
        isNetworkSettingsPause(state) || state == EngineState.PAUSED_NETWORK_BLOCKED

    /**
     * Whether the device is asleep in the sense that cuts background network: in Doze, or with the
     * screen off. A failed read counts as awake, which keeps the old resign behavior.
     */
    private fun isDeviceAsleep(): Boolean {
        val pm = runCatching { context.getSystemService(PowerManager::class.java) }.getOrNull() ?: return false
        val idle = runCatching { pm.isDeviceIdleMode }.getOrDefault(false)
        val interactive = runCatching { pm.isInteractive }.getOrDefault(true)
        return idle || !interactive
    }

    /** The power facts that decide whether background work runs, for pause and resign logs. */
    private fun powerSnapshot(): String {
        val pm = runCatching { context.getSystemService(PowerManager::class.java) }.getOrNull()
        fun read(f: (PowerManager) -> Boolean): String =
            pm?.let { runCatching { f(it).toString() }.getOrNull() } ?: "?"
        return "deviceIdle=${read { it.isDeviceIdleMode }}, interactive=${read { it.isInteractive }}, " +
            "ignoringBatteryOptimizations=${read { it.isIgnoringBatteryOptimizations(context.packageName) }}, " +
            "powerSave=${read { it.isPowerSaveMode }}, networkBlocked=${cachedNetworkBlocked.get()}"
    }

    /**
     * The action rate for the current network transport and charging state, or null when the
     * engine must pause: no network at all, or on mobile data with [PoisonProfile.mobileIntensity]
     * set to Off (issue #62). See [IntensityPolicy.effectiveIntensity], which also applies the
     * charging boost (#316).
     */
    private fun effectiveIntensity(p: PoisonProfile): IntensityLevel? = IntensityPolicy.effectiveIntensity(
        profile = p,
        transport = cachedTransport.get(),
        networkBlocked = cachedNetworkBlocked.get(),
        isCharging = cachedIsCharging.get(),
        batterySaver = cachedPowerSave.value,
    )

    /** The charging boost in effect right now (#316), or null. */
    private fun currentChargingBoost(p: PoisonProfile): IntensityLevel? =
        if (cachedNetworkBlocked.get() || cachedTransport.get() != NetworkTransport.WIFI) null
        else IntensityPolicy.chargingBoost(
            wifiIntensity = p.intensity,
            chargingIntensity = p.chargingIntensity,
            isCharging = cachedIsCharging.get(),
            isUnmetered = true,
            batterySaver = cachedPowerSave.value,
        )

    /**
     * Returns null if all constraints pass, or the specific [EngineState] pause reason.
     */
    private fun checkConstraints(currentProfile: PoisonProfile): EngineState? {
        // No logging here — this runs every retry tick (and again per 60s sleep chunk).
        // The reason is logged once per transition by [logPauseReason] from runLoop (#158).
        // Checked first: Android blocking the app is not "no usable network", and the UI and
        // resign rules for it differ (#335).
        if (cachedNetworkBlocked.get() && cachedTransport.get() != NetworkTransport.NONE) {
            return EngineState.PAUSED_NETWORK_BLOCKED
        }
        if (effectiveIntensity(currentProfile) == null) {
            // Same underlying cause (no budget for this network), but name the metered-WiFi
            // case separately so the UI doesn't claim we're "waiting for a usable network"
            // while the user is plainly connected to WiFi (issue #288).
            return if (cachedTransport.get() == NetworkTransport.METERED_WIFI) {
                EngineState.PAUSED_METERED_WIFI
            } else {
                EngineState.PAUSED_WIFI
            }
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
        // Checked last on purpose (#313): this pause never resigns, so any pause that does
        // (quiet hours, a long network or battery-level pause) must win and release the FGS.
        if (currentProfile.pauseOnBatterySaver && cachedPowerSave.value) {
            return EngineState.PAUSED_BATTERY_SAVER
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
        // Info, not debug, with the power state (#335): these lines are what a user's exported log
        // has to explain "nothing happened overnight" with.
        val power = powerSnapshot()
        when (state) {
            EngineState.PAUSED_WIFI ->
                Timber.i("Paused: no usable network (transport=${cachedTransport.get()}, mobile=${profile.mobileIntensity}, $power)")
            EngineState.PAUSED_METERED_WIFI -> {
                Timber.i("Paused: Wi-Fi is metered and mobile intensity is Off (mobile=${profile.mobileIntensity}, $power)")
                // The resume constraint for this state is UNMETERED, which on a permanently
                // metered home network may never fire — so this pause could otherwise last
                // forever in silence. Say it once, here, on the state transition (#288).
                // Wrapped here rather than inside the notifier: keeping that function a flat
                // body is what lets CodeQL track the PendingIntent's component (CWE-927).
                runCatching { com.fauxx.service.postMeteredWifiNotice(context) }
                    .onFailure { Timber.w(it, "Failed to post the metered-Wi-Fi notice") }
            }
            EngineState.PAUSED_NETWORK_BLOCKED ->
                Timber.i("Paused: Android is blocking this app's network (transport=${cachedTransport.get()}, $power)")
            EngineState.PAUSED_BATTERY -> Timber.i("Paused: battery below threshold ($power)")
            EngineState.PAUSED_BATTERY_SAVER -> Timber.i("Paused: Battery Saver is on ($power)")
            EngineState.PAUSED_QUIET_HOURS ->
                Timber.i("Paused: outside allowed hours (${profile.allowedHoursStart}-${profile.allowedHoursEnd}, $power)")
            else -> { /* ACTIVE / PAUSED_RATE_LIMIT / STOPPED log via their own paths */ }
        }
    }

    /**
     * Pure decision function for the battery branch of [checkConstraints]. Extracted so
     * the bypass-while-charging behavior (issue #20) can be exercised without registering
     * BroadcastReceivers in tests.
     *
     * The two thresholds are independent (#216): a charging threshold of 0 means "never pause for
     * battery while plugged in", which is how the retired ignore-while-charging toggle migrates.
     */
    internal fun shouldPauseForBattery(
        batteryLevel: Int,
        thresholdBattery: Int,
        thresholdCharging: Int,
        isCharging: Boolean
    ): Boolean {
        val threshold = if (isCharging) thresholdCharging else thresholdBattery
        return batteryLevel < threshold
    }

    /** Returns true if the current local hour falls within the profile's allowed window.
     *  Delegates to [AllowedHours], the same predicate [com.fauxx.engine.scheduling.SessionScheduler] uses, so the
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
        releaseWake()
        val sessionDurationMs = clock.elapsedRealtime() - engineStartElapsedMs
        val ctx = reason ?: state?.toString() ?: "unknown"
        Timber.i("Engine resigning ($ctx) after ${sessionDurationMs}ms; resume spec: $spec (${powerSnapshot()})")
        onLongPause?.invoke(spec)
    }

    /**
     * Recover from an uncaught exception thrown by the engine loop machinery (issue #197).
     * Without this, the exception escapes [engineJob] (a [SupervisorJob] child has no parent to
     * absorb it), reaches the app's default uncaught-exception handler ([com.fauxx.FauxxApp]),
     * and crashes the whole process — and because [com.fauxx.service.PhantomForegroundService]
     * `isRunning` stays true, [com.fauxx.service.EngineReconcileWorker] could not recover it.
     *
     * Instead: mark the engine [EngineState.STOPPED] and route through the existing resign path so
     * the FGS schedules a resume and tears itself down cleanly (clearing isRunning). The full
     * flavor's exact-alarm resume auto-restarts shortly so a transient fault self-heals; the delay
     * keeps a deterministic fault from hot-looping. Idempotent: a no-op if already resigned.
     */
    private fun handleLoopFailure(t: Throwable) {
        if (t is CancellationException) return
        Timber.e(t, "Engine loop terminated by an uncaught exception (#197); tearing down for recovery")
        _engineState.value = EngineState.STOPPED
        if (hasResigned) return
        resignAndRecord(
            state = EngineState.STOPPED,
            spec = ResumeSpec.AtTime(clock.currentTimeMillis() + LOOP_FAILURE_RESUME_DELAY_MS),
            reason = "loop failure: ${t.javaClass.simpleName}",
        )
    }

    /**
     * Decide whether the engine should keep delay-looping in the FGS or resign
     * (stop the FGS and schedule a resume notification).
     *
     * Rules:
     * - [EngineState.PAUSED_QUIET_HOURS]: always resign — quiet hours can be 8+ hours long,
     *   so we release the FGS slot rather than spin idle.
     * - [EngineState.PAUSED_WIFI] / [EngineState.PAUSED_METERED_WIFI] /
     *   [EngineState.PAUSED_BATTERY]: resign once the pause has
     *   lasted [LONG_PAUSE_THRESHOLD_MS]. Short blips (commute, brief WiFi drop) keep the
     *   FGS up, but sustained pauses surrender it.
     * - [EngineState.PAUSED_BATTERY_SAVER]: never resign (#313). No WorkManager constraint means
     *   "Battery Saver is off" and the system does not deliver its change broadcast to a stopped
     *   app, so a resigned engine could only come back through a tap-to-resume notification.
     *   Staying up costs one wake per [BATTERY_SAVER_RECHECK_MS] and resumes the moment Battery
     *   Saver ends.
     * - [idleInducedNetworkPause]: never resign on a [EngineState.PAUSED_WIFI] or
     *   [EngineState.PAUSED_METERED_WIFI] pause while the device is asleep (#335). Sleep cuts the
     *   network, or drops Wi-Fi so the default network becomes mobile data or a metered hotspot,
     *   which a user with mobile Off sees as either pause. A resigned engine could only come back
     *   through a tap-to-resume notification, so it stayed off until the user picked up the
     *   phone. Staying up costs nothing while asleep: the loop waits on the network callback, not
     *   on an alarm. Thirty awake minutes in either pause still resign, as before.
     * - [EngineState.PAUSED_NETWORK_BLOCKED]: never resign (#335). The network is up, so a
     *   network constraint would be met at once and the engine would resign, restart and block
     *   again; the loop resumes on the network callback instead.
     */
    @VisibleForTesting
    internal fun decidePauseAction(
        state: EngineState,
        currentProfile: PoisonProfile,
        pauseElapsedMs: Long,
        @Suppress("UNUSED_PARAMETER") totalRuntimeMs: Long,
        nowMs: Long = clock.currentTimeMillis(),
        idleInducedNetworkPause: Boolean = false,
    ): PauseDecision {
        if (idleInducedNetworkPause && isNetworkSettingsPause(state)) {
            return PauseDecision.Continue
        }
        return when (state) {
            EngineState.PAUSED_QUIET_HOURS ->
                PauseDecision.Resign(ResumeSpec.AtTime(nextAllowedHoursStartMs(currentProfile, nowMs)))
            // Both share a resume rule. PAUSED_METERED_WIFI only ever occurs with mobile
            // Off (otherwise the mobile budget would have applied and the engine would be
            // running), so it always resolves to the UNMETERED constraint below — exactly
            // the "resume when a real unmetered network appears" behavior issue #288 wants.
            EngineState.PAUSED_WIFI, EngineState.PAUSED_METERED_WIFI ->
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
            EngineState.PAUSED_BATTERY_SAVER -> PauseDecision.Continue
            EngineState.PAUSED_NETWORK_BLOCKED -> PauseDecision.Continue
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
         *  - UNMETERED WiFi or ethernet → [NetworkTransport.WIFI] (unmetered bucket)
         *  - METERED WiFi or ethernet → [NetworkTransport.METERED_WIFI] (issue #288): a phone
         *    hotspot, or a network the user marked metered, spends a data allowance just like
         *    cellular, so it is billed against the mobile budget instead of the WiFi one
         *  - VPN whose underlying networks include an unmetered non-VPN WiFi →
         *    [NetworkTransport.WIFI]; if every WiFi underneath is metered →
         *    [NetworkTransport.METERED_WIFI]
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
                return if (isUnmetered(activeCaps)) NetworkTransport.WIFI
                else NetworkTransport.METERED_WIFI
            }
            if (activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                // Same unmetered bucket as the direct branch: WiFi or ethernet underneath.
                val wifiUnderneath = underlyingCaps().filter {
                    (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                        !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                }
                return when {
                    wifiUnderneath.any { isUnmetered(it) } -> NetworkTransport.WIFI
                    // Wi-Fi underneath, but every candidate is metered (issue #288): name it
                    // METERED_WIFI so the pause reason is truthful, and bill it as mobile.
                    wifiUnderneath.isNotEmpty() -> NetworkTransport.METERED_WIFI
                    else -> NetworkTransport.CELLULAR
                }
            }
            return NetworkTransport.CELLULAR
        }

        /**
         * True when [caps] carries `NET_CAPABILITY_NOT_METERED`. Android sets this on
         * ordinary home/office WiFi and CLEARS it for tethered phone hotspots (detected
         * automatically) and for networks the user marked metered in Wi-Fi settings.
         * Absence is treated as metered, matching the "when in doubt, bill it as mobile
         * data" doctrine the VPN branch already follows (issue #288).
         */
        private fun isUnmetered(caps: NetworkCapabilities): Boolean =
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        /**
         * Legacy boolean view of [classifyTransport], kept because "is this WiFi for the
         * engine's purposes" remains a meaningful question (issue #59 test surface).
         * Note this means the UNMETERED bucket specifically: a metered WiFi network
         * classifies as [NetworkTransport.METERED_WIFI] and returns false here (issue #288).
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
 * CELLULAR and METERED_WIFI both run [PoisonProfile.mobileIntensity] (null = pause, and the
 * default), NONE always pauses.
 */
internal enum class NetworkTransport {
    /** Unmetered Wi-Fi or ethernet — the full [PoisonProfile.intensity] budget applies. */
    WIFI,
    /**
     * Wi-Fi or ethernet the system reports as METERED (issue #288): a tethered phone
     * hotspot, or a network the user marked metered in Android's Wi-Fi settings. It spends
     * a data allowance exactly like cellular does, so it is billed against
     * [PoisonProfile.mobileIntensity] rather than the Wi-Fi budget. Kept separate from
     * [CELLULAR] purely so logs and the UI can name the real cause.
     */
    METERED_WIFI,
    CELLULAR,
    NONE
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
        prefs[com.fauxx.di.PreferenceKeys.CHARGING_INTENSITY] = p.chargingIntensity?.name ?: CHARGING_INTENSITY_OFF
        prefs[com.fauxx.di.PreferenceKeys.BATTERY_THRESHOLD_BATTERY] = p.batteryThresholdBattery
        prefs[com.fauxx.di.PreferenceKeys.BATTERY_THRESHOLD_CHARGING] = p.batteryThresholdCharging
        prefs[com.fauxx.di.PreferenceKeys.EXCLUDED_SEARCH_ENGINES] = p.excludedSearchEngines
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
        prefs[com.fauxx.di.PreferenceKeys.LOAD_IMAGES] = p.loadImages
        prefs[com.fauxx.di.PreferenceKeys.PAUSE_ON_BATTERY_SAVER] = p.pauseOnBatterySaver
        prefs[com.fauxx.di.PreferenceKeys.DNS_MODE] = p.dnsMode.name
        prefs[com.fauxx.di.PreferenceKeys.DOH_PROVIDER] = p.dohProvider
        prefs[com.fauxx.di.PreferenceKeys.DOH_CUSTOM_URL] = p.dohCustomUrl
        prefs[com.fauxx.di.PreferenceKeys.DOH_CUSTOM_SERVER_IP] = p.dohCustomServerIp
        prefs[com.fauxx.di.PreferenceKeys.DOH_SKIP_CERTIFICATE_CHECK] = p.dohSkipCertificateCheck
        prefs[com.fauxx.di.PreferenceKeys.DOH_PINNED_KEY] = p.dohPinnedKey
        prefs[com.fauxx.di.PreferenceKeys.PLAIN_DNS_SERVER] = p.plainDnsServer
        prefs[com.fauxx.di.PreferenceKeys.ROUTE_DNS_NOISE] = p.routeDnsNoise
        prefs[com.fauxx.di.PreferenceKeys.PREFERRED_CUSTOM_DNS_MODE] = p.preferredCustomDnsMode.name
        prefs[com.fauxx.di.PreferenceKeys.DNS_STRICT] = p.dnsStrict
        // #201: the custom UA is retired; clear any value a previous version stored so it
        // does not linger in DataStore.
        prefs.remove(com.fauxx.di.PreferenceKeys.CUSTOM_USER_AGENT)
    }

    /**
     * On-battery threshold for a profile written before #216 split the single slider in two.
     * The old [PreferenceKeys.BATTERY_THRESHOLD] meant "pause below this level", which is exactly
     * the on-battery threshold, so it carries over unchanged. Falls back to the 20% default only
     * when the user never had a stored value at all.
     */
    private fun legacyBatteryThreshold(
        prefs: androidx.datastore.preferences.core.Preferences
    ): Int = prefs[com.fauxx.di.PreferenceKeys.BATTERY_THRESHOLD] ?: DEFAULT_BATTERY_THRESHOLD

    /**
     * While-charging threshold for a pre-#216 profile. The old behavior was a single threshold plus
     * an "ignore it while charging" toggle, so a user who had that toggle ON meant "never pause for
     * battery while plugged in" — which is a charging threshold of 0. Everyone else keeps the same
     * threshold they had on both sides, matching the old single-slider behavior.
     *
     * Without this, updating silently reset a customised threshold to 20% and dropped the
     * ignore-while-charging preference entirely.
     */
    private fun legacyChargingThreshold(
        prefs: androidx.datastore.preferences.core.Preferences
    ): Int =
        if (prefs[com.fauxx.di.PreferenceKeys.IGNORE_BATTERY_THRESHOLD_WHILE_CHARGING] == true) {
            0
        } else {
            prefs[com.fauxx.di.PreferenceKeys.BATTERY_THRESHOLD] ?: DEFAULT_BATTERY_THRESHOLD
        }

    private fun prefsToProfile(prefs: androidx.datastore.preferences.core.Preferences): PoisonProfile {
        val intensity = readIntensity(prefs)
        return PoisonProfile(
            enabled = prefs[com.fauxx.di.PreferenceKeys.ENABLED] ?: false,
            intensity = intensity,
            mobileIntensity = readMobileIntensity(prefs, intensity),
            chargingIntensity = readChargingIntensity(prefs),
            batteryThresholdBattery =
                prefs[com.fauxx.di.PreferenceKeys.BATTERY_THRESHOLD_BATTERY] ?: legacyBatteryThreshold(prefs),
            batteryThresholdCharging =
                prefs[com.fauxx.di.PreferenceKeys.BATTERY_THRESHOLD_CHARGING]
                    ?: legacyChargingThreshold(prefs),
            excludedSearchEngines =
                prefs[com.fauxx.di.PreferenceKeys.EXCLUDED_SEARCH_ENGINES].orEmpty(),
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
            loadImages = prefs[com.fauxx.di.PreferenceKeys.LOAD_IMAGES] ?: false,
            pauseOnBatterySaver = prefs[com.fauxx.di.PreferenceKeys.PAUSE_ON_BATTERY_SAVER] ?: false,
            dnsMode = runCatching {
                com.fauxx.data.model.DnsMode.valueOf(
                    prefs[com.fauxx.di.PreferenceKeys.DNS_MODE] ?: com.fauxx.data.model.DnsMode.SYSTEM.name
                )
            }.getOrDefault(com.fauxx.data.model.DnsMode.SYSTEM),
            dohProvider = prefs[com.fauxx.di.PreferenceKeys.DOH_PROVIDER] ?: "quad9",
            dohCustomUrl = prefs[com.fauxx.di.PreferenceKeys.DOH_CUSTOM_URL] ?: "",
            dohCustomServerIp = prefs[com.fauxx.di.PreferenceKeys.DOH_CUSTOM_SERVER_IP] ?: "",
            dohSkipCertificateCheck = prefs[com.fauxx.di.PreferenceKeys.DOH_SKIP_CERTIFICATE_CHECK] ?: false,
            dohPinnedKey = prefs[com.fauxx.di.PreferenceKeys.DOH_PINNED_KEY] ?: "",
            plainDnsServer = prefs[com.fauxx.di.PreferenceKeys.PLAIN_DNS_SERVER] ?: "",
            routeDnsNoise = prefs[com.fauxx.di.PreferenceKeys.ROUTE_DNS_NOISE] ?: false,
            preferredCustomDnsMode = runCatching {
                com.fauxx.data.model.DnsMode.valueOf(
                    prefs[com.fauxx.di.PreferenceKeys.PREFERRED_CUSTOM_DNS_MODE] ?: com.fauxx.data.model.DnsMode.DOH.name
                )
            }.getOrDefault(com.fauxx.data.model.DnsMode.DOH),
            dnsStrict = prefs[com.fauxx.di.PreferenceKeys.DNS_STRICT] ?: false,
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

    /**
     * Charging boost (#316). Absent (a profile from before the setting existed), the "OFF"
     * sentinel, or a corrupt value all read as null: no boost, the pre-#316 behavior.
     */
    private fun readChargingIntensity(
        prefs: androidx.datastore.preferences.core.Preferences
    ): com.fauxx.data.model.IntensityLevel? {
        val raw = prefs[com.fauxx.di.PreferenceKeys.CHARGING_INTENSITY] ?: return null
        if (raw == CHARGING_INTENSITY_OFF) return null
        return runCatching { com.fauxx.data.model.IntensityLevel.valueOf(raw) }.getOrNull()
    }

    private companion object {
        /** Stored sentinel for "paused on mobile data" in the MOBILE_INTENSITY pref. */
        const val MOBILE_INTENSITY_OFF = "OFF"

        /** Stored sentinel for "no charging boost" in the CHARGING_INTENSITY pref (#316). */
        const val CHARGING_INTENSITY_OFF = "OFF"
    }
}
