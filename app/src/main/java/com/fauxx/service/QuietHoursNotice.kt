package com.fauxx.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fauxx.R
import com.fauxx.engine.scheduling.AllowedHours
import timber.log.Timber

/**
 * Feedback for the "Start action tapped during quiet hours" dead end (issue #198).
 *
 * Tapping **Start** outside the configured active hours does exactly the right thing: the
 * engine starts, immediately resigns with `PAUSED_QUIET_HOURS`, and arms an exact alarm for
 * the start of the next active window. But the only thing the user sees is a
 * foreground-service notification that flashes for about a second and vanishes, so a
 * correct, deliberate action reads as a no-op and gets reported as a bug.
 *
 * This posts a short, self-dismissing notice naming the time protection will actually
 * resume. The functional behavior is untouched, this only explains it.
 *
 * Copy is hardcoded English to match the rest of the notification layer
 * ([postResumeNotification], [PhantomForegroundService.updateNotification]); notification
 * strings in this app are not localized.
 */
internal const val QUIET_HOURS_CHANNEL_ID = "fauxx_quiet_hours"
internal const val QUIET_HOURS_NOTIFICATION_ID = 43

/**
 * The notice to show when the user asks to start at [nowHour], or null when no notice is
 * warranted because the engine will genuinely run right now.
 *
 * Pure so the wording and the "stay silent inside the window" rule can be tested without a
 * NotificationManager.
 */
internal fun quietHoursStartNotice(nowHour: Int, start: Int, end: Int): String? {
    if (AllowedHours.isWithin(nowHour, start, end)) return null
    return "Started. Paused until your active hours begin at $start:00."
}

/**
 * Post [quietHoursStartNotice] if one is warranted. No-op inside the active window, and
 * no-op when notifications are not permitted (POST_NOTIFICATIONS denied on Android 13+).
 */
fun postQuietHoursStartNotice(context: Context, nowHour: Int, start: Int, end: Int) {
    val message = quietHoursStartNotice(nowHour, start, end) ?: return

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !NotificationManagerCompat.from(context).areNotificationsEnabled()
    ) {
        Timber.w("POST_NOTIFICATIONS not granted; skipping quiet-hours start notice")
        return
    }

    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    // LOW importance: this is a confirmation, not a prompt. It should appear in the shade
    // without a sound or heads-up interruption.
    nm.createNotificationChannel(
        NotificationChannel(
            QUIET_HOURS_CHANNEL_ID,
            "Quiet hours notices",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Explains why Fauxx is paused when you start it outside your active hours"
            setShowBadge(false)
        }
    )

    val notification = NotificationCompat.Builder(context, QUIET_HOURS_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Fauxx")
        .setContentText(message)
        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setAutoCancel(true)
        // Self-dismissing: the point is a moment of feedback, not a notification the user
        // has to clear. Android caps this at roughly its own maximum if it is too long.
        .setTimeoutAfter(QUIET_HOURS_NOTICE_TIMEOUT_MS)
        .build()

    try {
        NotificationManagerCompat.from(context).notify(QUIET_HOURS_NOTIFICATION_ID, notification)
    } catch (e: SecurityException) {
        Timber.w(e, "Failed to post quiet-hours start notice (SecurityException)")
    }
}

/** How long the notice lingers before Android removes it. */
private const val QUIET_HOURS_NOTICE_TIMEOUT_MS = 30_000L
