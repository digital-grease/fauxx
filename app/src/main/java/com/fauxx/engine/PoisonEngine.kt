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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PoisonEngine"

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engineJob: Job? = null

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
        scope.launch {
            allModules.forEach { runCatching { it.stop() } }
        }
        Log.i(TAG, "PoisonEngine stopped")
    }

    private suspend fun runLoop() {
        val currentProfile = profile.getProfile()

        while (scope.isActive) {
            // Constraint checks
            if (!checkConstraints(currentProfile)) {
                delay(60_000L) // Check again in 1 minute
                continue
            }

            // Pick next category
            val category = dispatcher.selectCategory()

            // Pick an enabled module
            val enabledModules = allModules.filter { it.isEnabled() }
            if (enabledModules.isEmpty()) {
                delay(60_000L)
                continue
            }

            val module = enabledModules.random()

            // Write-ahead log
            val logEntry = try {
                module.onAction(category)
            } catch (e: Exception) {
                Log.e(TAG, "Module ${module::class.simpleName} failed for $category", e)
                delay(5_000L)
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
 * Repository providing the current [PoisonProfile] from EncryptedSharedPreferences.
 */
@Singleton
class PoisonProfileRepository @Inject constructor(
    private val prefs: android.content.SharedPreferences
) {
    fun getProfile(): PoisonProfile = PoisonProfile(
        enabled = prefs.getBoolean("enabled", false),
        intensity = com.fauxx.data.model.IntensityLevel.valueOf(
            prefs.getString("intensity", com.fauxx.data.model.IntensityLevel.MEDIUM.name)
                ?: com.fauxx.data.model.IntensityLevel.MEDIUM.name
        ),
        wifiOnly = prefs.getBoolean("wifi_only", true),
        batteryThreshold = prefs.getInt("battery_threshold", 20),
        allowedHoursStart = prefs.getInt("allowed_hours_start", 7),
        allowedHoursEnd = prefs.getInt("allowed_hours_end", 23),
        searchPoisonEnabled = prefs.getBoolean("module_search", true),
        adPollutionEnabled = prefs.getBoolean("module_ad", true),
        locationSpoofEnabled = prefs.getBoolean("module_location", false),
        fingerprintEnabled = prefs.getBoolean("module_fingerprint", true),
        cookieSaturationEnabled = prefs.getBoolean("module_cookie", true),
        appSignalEnabled = prefs.getBoolean("module_appsignal", false),
        dnsNoiseEnabled = prefs.getBoolean("module_dns", true),
        layer1Enabled = prefs.getBoolean("layer1_enabled", false),
        layer2Enabled = prefs.getBoolean("layer2_enabled", false),
        layer3Enabled = prefs.getBoolean("layer3_enabled", true)
    )

    fun saveProfile(p: PoisonProfile) = prefs.edit().apply {
        putBoolean("enabled", p.enabled)
        putString("intensity", p.intensity.name)
        putBoolean("wifi_only", p.wifiOnly)
        putInt("battery_threshold", p.batteryThreshold)
        putInt("allowed_hours_start", p.allowedHoursStart)
        putInt("allowed_hours_end", p.allowedHoursEnd)
        putBoolean("module_search", p.searchPoisonEnabled)
        putBoolean("module_ad", p.adPollutionEnabled)
        putBoolean("module_location", p.locationSpoofEnabled)
        putBoolean("module_fingerprint", p.fingerprintEnabled)
        putBoolean("module_cookie", p.cookieSaturationEnabled)
        putBoolean("module_appsignal", p.appSignalEnabled)
        putBoolean("module_dns", p.dnsNoiseEnabled)
        putBoolean("layer1_enabled", p.layer1Enabled)
        putBoolean("layer2_enabled", p.layer2Enabled)
        putBoolean("layer3_enabled", p.layer3Enabled)
    }.apply()
}
