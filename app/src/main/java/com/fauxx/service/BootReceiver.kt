package com.fauxx.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fauxx.R
import com.fauxx.di.PreferenceKeys
import com.fauxx.di.fauxxDataStore
import com.fauxx.ui.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

private const val RESUME_CHANNEL_ID = "fauxx_resume"
private const val RESUME_NOTIFICATION_ID = 42

/**
 * Handles post-reboot / post-update resumption of the [PhantomForegroundService].
 *
 * Android 14+ (targetSdk 36) forbids starting a `dataSync` foreground service from any
 * BOOT_COMPLETED context chain — including WorkManager expedited work triggered by boot.
 * So instead of auto-restarting the FGS, we post a "tap to resume protection" notification.
 * Tapping it opens [MainActivity] with [MainActivity.EXTRA_RESUME_ENGINE], and the engine
 * starts from user interaction — always an allowed FGS-start context.
 *
 * Posting the notification requires BOTH:
 *  - [PreferenceKeys.ENABLED] was true pre-reboot, AND
 *  - [PreferenceKeys.RESUME_ON_BOOT] is true (user-controlled toggle in Settings).
 *
 * If either is false, this receiver does nothing.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Timber.i("Boot/update received, checking if engine should resume")

                // Read both flags from DataStore in a single read with a timeout to avoid
                // exceeding the BroadcastReceiver's ~10s execution limit.
                val (wasEnabled, resumeOnBoot) = runBlocking {
                    withTimeoutOrNull(5_000L) {
                        val prefs = context.fauxxDataStore.data.first()
                        (prefs[PreferenceKeys.ENABLED] ?: false) to
                            (prefs[PreferenceKeys.RESUME_ON_BOOT] ?: true)
                    } ?: (false to true)
                }

                when {
                    !wasEnabled -> Timber.i("Engine was disabled pre-reboot; no action")
                    !resumeOnBoot -> Timber.i("Resume-on-boot disabled by user; no action")
                    else -> {
                        Timber.i("Engine was enabled pre-reboot; posting resume notification")
                        postResumeNotification(context)
                    }
                }
            }
        }
    }

    private fun postResumeNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            RESUME_CHANNEL_ID,
            "Resume protection",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Prompts you to resume Fauxx after device reboot"
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

        // Android 13+ requires POST_NOTIFICATIONS runtime permission; it's commonly denied
        // right after install (user never opened the app yet). NotificationManagerCompat's
        // areNotificationsEnabled() gives a cheap pre-check, and notify() itself may throw
        // SecurityException on older OEM builds, so wrap it.
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
}
