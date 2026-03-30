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
import com.fauxx.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
                startForeground(NOTIFICATION_ID, buildNotification("Initializing…", 0))
                try {
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
                notificationJob?.cancel()
                notificationJob = null
                poisonEngine.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        poisonEngine.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startNotificationUpdates() {
        notificationJob = scope.launch {
            while (true) {
                updateNotification()
                delay(NOTIFICATION_UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun updateNotification() {
        val count = poisonEngine.getTodayActionCount()
        val status = when (poisonEngine.engineState) {
            EngineState.ACTIVE -> "Active — $count actions today"
            EngineState.PAUSED_WIFI -> "Paused — waiting for WiFi"
            EngineState.PAUSED_BATTERY -> "Paused — battery low"
            EngineState.PAUSED_RATE_LIMIT -> "Paused — hourly limit reached"
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
            "Phantom Engine",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Background privacy protection activity"
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.fauxx.START"
        const val ACTION_STOP = "com.fauxx.STOP"

        fun startIntent(context: Context) =
            Intent(context, PhantomForegroundService::class.java).apply { action = ACTION_START }

        fun stopIntent(context: Context) =
            Intent(context, PhantomForegroundService::class.java).apply { action = ACTION_STOP }
    }
}
