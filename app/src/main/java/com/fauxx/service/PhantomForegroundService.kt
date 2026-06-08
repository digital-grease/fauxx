package com.fauxx.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import timber.log.Timber
import com.fauxx.R
import com.fauxx.engine.EngineState
import com.fauxx.engine.PoisonEngine
import com.fauxx.engine.modules.MockLocationProviderCleaner
import com.fauxx.engine.webview.PhantomWebViewPool
import com.fauxx.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val CHANNEL_ID = "fauxx_engine"
private const val NOTIFICATION_ID = 1
private const val NOTIFICATION_UPDATE_INTERVAL_MS = 60_000L

/**
 * Persistent foreground service that hosts the [PoisonEngine] and keeps it alive in the
 * background. Shows a status notification updated every 60 seconds with:
 * - Active/paused status
 * - Number of actions executed today
 * - Current intensity level
 */
@AndroidEntryPoint
class PhantomForegroundService : Service() {

    @Inject lateinit var poisonEngine: PoisonEngine
    @Inject lateinit var webViewPool: PhantomWebViewPool
    @Inject lateinit var resumeScheduler: ResumeScheduler
    @Inject lateinit var mockLocationProviderCleaner: MockLocationProviderCleaner

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Separate scope for teardown work. Outlives [scope] so cleanup coroutines keep
     * running after the main engine scope is cancelled in onDestroy.
     */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var notificationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_START -> {
                Timber.i("Starting Phantom service")
                // NOTE: a stale pending resume is NOT cancelled here. It is cancelled only
                // once the engine clears its constraint gate (setOnActive below), so a
                // process death during engine init can't wipe a still-needed resume before
                // it fires (#156). If the engine resigns instead, it reschedules — which
                // replaces the stale entry anyway.
                // startForeground() throws ForegroundServiceStartNotAllowedException (a subclass of
                // IllegalStateException, API 31+) when the FGS-start is blocked — e.g., from a
                // BOOT_COMPLETED context chain on Android 14+, or when the flavor-declared FGS
                // type is not permitted in the current launch context. We catch that here so a
                // denial never kills the process, and post the tap-to-resume notification so the
                // user can recover (BootReceiver only covers boot/update; #156).
                try {
                    startForeground(NOTIFICATION_ID, buildNotification("Initializing…", 0))
                    isRunning = true
                } catch (e: IllegalStateException) {
                    Timber.e(e, "startForeground denied (${e.javaClass.simpleName}); posting resume notification and stopping")
                    runCatching { postResumeNotification(this) }
                    stopSelf()
                    return START_NOT_STICKY
                } catch (e: SecurityException) {
                    Timber.e(e, "startForeground denied (SecurityException); posting resume notification and stopping")
                    runCatching { postResumeNotification(this) }
                    stopSelf()
                    return START_NOT_STICKY
                }
                try {
                    // Wire handlers before starting so a same-tick resign or active pass
                    // (e.g., engine started during quiet hours) sees the callback.
                    poisonEngine.setOnLongPause { spec -> onEngineResigned(spec) }
                    poisonEngine.setOnActive {
                        runCatching { resumeScheduler.cancel() }
                            .onFailure { Timber.e(it, "Failed to cancel stale resume on engine active") }
                    }
                    poisonEngine.start()
                    startNotificationUpdates()
                } catch (e: Exception) {
                    Timber.e(e, "Engine failed to start, stopping service")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                Timber.i("Stopping Phantom service")
                // The user is disabling the engine, so any pending resume is now stale.
                runCatching { resumeScheduler.cancel() }
                    .onFailure { Timber.e(it, "Error cancelling resume on stop") }
                notificationJob?.cancel()
                notificationJob = null
                // poisonEngine.stop() is non-blocking (module teardown runs async on the
                // engine's own scope). Tearing down here on the main thread is safe.
                runCatching { poisonEngine.stop() }
                    .onFailure { Timber.e(it, "Error stopping engine") }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    /**
     * Swiping Fauxx out of recents can kill the service without a reliable [onDestroy] on some
     * OEMs, orphaning the system mock-location provider so the device keeps reporting the last
     * spoofed fix (finding #6 / issue #66). Remove it synchronously here — this runs on the main
     * thread before the process goes away, unlike the engine's async module teardown.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // The engine is stopped here, so the watchdog must see "not running" — otherwise a
        // swipe-away that survives as a foregrounded-but-engineless service (isRunning still
        // true) would hide the stopped engine from EngineReconcileWorker (#156).
        isRunning = false
        runCatching { mockLocationProviderCleaner.clearOrphanedProvider() }
            .onFailure { Timber.e(it, "Error clearing mock-location provider on task removal") }
        runCatching { poisonEngine.stop() }
            .onFailure { Timber.e(it, "Error stopping engine on task removal") }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        // Remove the mock-location provider synchronously and unconditionally first: the engine's
        // module teardown below runs async on its own scope and can lose the race to process death,
        // which is exactly how the provider gets orphaned (finding #6).
        runCatching { mockLocationProviderCleaner.clearOrphanedProvider() }
            .onFailure { Timber.e(it, "Error clearing mock-location provider on destroy") }
        // Both teardown calls are now non-blocking:
        // - poisonEngine.destroy() dispatches module stop() onto its own IO scope.
        // - webViewPool.destroy() is a suspend fun that dispatches to Main; we launch it
        //   on cleanupScope so the current (main) thread is never blocked waiting for it.
        // Previous implementation used runBlocking { withContext(Dispatchers.Main) { ... } }
        // on the main thread, which deadlocked and produced 5s input-dispatch ANRs.
        runCatching { poisonEngine.destroy() }
            .onFailure { Timber.e(it, "Error destroying engine") }
        cleanupScope.launch(NonCancellable) {
            runCatching { webViewPool.destroy() }
                .onFailure { Timber.e(it, "Error destroying WebView pool") }
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Invoked by [PoisonEngine] from its run loop when it decides the service should
     * stop and reappear later via tap-to-resume. Schedules the resume notification,
     * then tears the foreground service down. Safe to call from any thread —
     * [stopForeground] / [stopSelf] dispatch internally.
     */
    @androidx.annotation.VisibleForTesting
    internal fun onEngineResigned(spec: ResumeSpec) {
        Timber.i("Engine resigned; scheduling resume ($spec) and stopping FGS")
        runCatching { resumeScheduler.schedule(spec) }
            .onFailure { Timber.e(it, "Failed to schedule resume notification") }
        notificationJob?.cancel()
        notificationJob = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
            // stopForeground rarely throws; ignore so stopSelf still runs.
        }
        stopSelf()
    }

    private fun startNotificationUpdates() {
        notificationJob = scope.launch {
            while (isActive) {
                updateNotification()
                delay(NOTIFICATION_UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun updateNotification() {
        val count = poisonEngine.getTodayActionCount()
        val status = when (poisonEngine.engineState.value) {
            EngineState.ACTIVE -> "Generating diverse browsing activity — $count actions today"
            EngineState.PAUSED_WIFI -> "Paused — Wi-Fi only mode, waiting for Wi-Fi"
            EngineState.PAUSED_BATTERY -> "Paused — battery low"
            EngineState.PAUSED_RATE_LIMIT -> "Paused — hourly limit reached"
            EngineState.PAUSED_QUIET_HOURS -> "Paused — outside active hours"
            EngineState.STOPPED -> "Stopped"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(status, count))
    }

    private fun buildNotification(status: String, actionsToday: Int): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PhantomForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Fauxx")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(tapIntent)
            .addAction(R.drawable.ic_notification, "Open", openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Privacy Protection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notifications for Fauxx background browsing diversification"
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.fauxx.START"
        const val ACTION_STOP = "com.fauxx.STOP"

        /**
         * True while the service is foregrounded (set after a successful startForeground,
         * cleared in onDestroy). Read by [EngineReconcileWorker] to tell "engine should be
         * running but isn't" from "already running". Process-scoped: a fresh process (after
         * a kill) correctly reads false (#156). WorkManager runs in the same process as the
         * service, so the read is consistent.
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * Reset the running flag. The flag is a process-global companion field, so in the
         * shared unit-test JVM fork one test starting the service would otherwise leak
         * `isRunning = true` into the next test. Tests that read it call this in setup.
         */
        @androidx.annotation.VisibleForTesting
        internal fun setRunningForTest(value: Boolean) {
            isRunning = value
        }

        fun startIntent(context: Context) =
            Intent(context, PhantomForegroundService::class.java).apply { action = ACTION_START }

        fun stopIntent(context: Context) =
            Intent(context, PhantomForegroundService::class.java).apply { action = ACTION_STOP }
    }
}
