package com.fauxx.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fauxx.R
import com.fauxx.ui.MainActivity
import timber.log.Timber

/**
 * Shared helper that posts the "Tap to resume protection" notification.
 *
 * Used by:
 * - [BootReceiver] after device reboot / app update
 * - [ResumeWorker] after the engine was voluntarily stopped during a long constraint
 *   pause (quiet hours, prolonged wifi-only/battery pause, or the 5h FGS-runtime safety
 *   net). Tapping the notification opens [MainActivity], which reconciles state from the
 *   persisted ENABLED flag and re-starts [PhantomForegroundService] from user
 *   interaction — an always-allowed FGS-start context on Android 14+.
 *
 * Centralised here so the channel ID, notification ID, copy, and PendingIntent flags
 * stay consistent across all entry points.
 */
internal const val RESUME_CHANNEL_ID = "fauxx_resume"
internal const val RESUME_NOTIFICATION_ID = 42

/**
 * Post the tap-to-resume notification. No-op when notifications are not permitted
 * (e.g., POST_NOTIFICATIONS denied on Android 13+).
 */
fun postResumeNotification(context: Context) {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channel = NotificationChannel(
        RESUME_CHANNEL_ID,
        "Resume protection",
        NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
        description = "Prompts you to resume Fauxx after pause or restart"
        setShowBadge(true)
    }
    nm.createNotificationChannel(channel)

    val tapIntent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(MainActivity.EXTRA_RESUME_ENGINE, true)
    }
    val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    val pendingIntent = PendingIntent.getActivity(context, 0, tapIntent, pendingFlags)

    val notification = NotificationCompat.Builder(context, RESUME_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Fauxx")
        .setContentText("Tap to resume protection")
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .build()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !NotificationManagerCompat.from(context).areNotificationsEnabled()
    ) {
        Timber.w("POST_NOTIFICATIONS not granted; skipping resume notification")
        return
    }
    try {
        NotificationManagerCompat.from(context)
            .notify(RESUME_NOTIFICATION_ID, notification)
    } catch (e: SecurityException) {
        Timber.w(e, "Failed to post resume notification (SecurityException)")
    }
}
