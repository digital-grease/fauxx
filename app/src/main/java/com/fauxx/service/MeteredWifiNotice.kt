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
 * Explains a metered-Wi-Fi pause (issue #288).
 *
 * Treating metered Wi-Fi as mobile data creates one bad case: a user whose HOME network is
 * permanently flagged metered (capped rural, satellite, or a plan they marked themselves) and
 * who never set a mobile-data tier. The engine pauses with [com.fauxx.engine.EngineState.
 * PAUSED_METERED_WIFI] and resigns with an `UNMETERED` resume constraint that, on a network
 * that is never unmetered, may simply never fire. Nothing else would tell them: the dashboard
 * explainer only appears if they open the app, and the resume notification is posted by the
 * very worker that constraint is gating.
 *
 * So say it once, at the moment of the pause. Tapping opens the app, where the dashboard
 * offers the one-tap "use mobile data" opt-in that resolves it.
 *
 * Copy is hardcoded English to match the rest of the notification layer.
 */
internal const val METERED_WIFI_CHANNEL_ID = "fauxx_metered_wifi"
internal const val METERED_WIFI_NOTIFICATION_ID = 44

/** The explanation shown when the engine pauses on a metered network. */
internal fun meteredWifiNoticeText(): String =
    "Paused: this Wi-Fi is metered, so Fauxx is treating it like mobile data. " +
        "Tap to choose a mobile-data level if you want it to run here."

/**
 * Post the metered-Wi-Fi explanation. Safe to call on every pause transition: a stable
 * notification id plus `setOnlyAlertOnce` means a network that flaps between metered and
 * unmetered updates the existing notice silently instead of stacking or re-alerting.
 *
 * Deliberately a flat function body. Building the Intent inside a lambda (a `runCatching`
 * block, say) defeats CodeQL's implicit-PendingIntent dataflow, which stops tracking the
 * component through the closure and reports CWE-927 even though `setPackage` is set. The
 * caller wraps this instead, so the engine's pause path still cannot be taken down by an
 * unexpected NotificationManager failure.
 */
fun postMeteredWifiNotice(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !NotificationManagerCompat.from(context).areNotificationsEnabled()
    ) {
        Timber.w("POST_NOTIFICATIONS not granted; skipping metered-Wi-Fi notice")
        return
    }

    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.createNotificationChannel(
        NotificationChannel(
            METERED_WIFI_CHANNEL_ID,
            "Metered network notices",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Explains why Fauxx is paused on a metered Wi-Fi network"
            setShowBadge(false)
        }
    )

    val tapIntent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        // Explicit target package — defensive against implicit-PendingIntent flags
        // (CWE-927), matching ResumeNotifier.
        setPackage(context.packageName)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val pendingIntent = PendingIntent.getActivity(
        context, 2, tapIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val text = meteredWifiNoticeText()
    val notification = NotificationCompat.Builder(context, METERED_WIFI_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Fauxx")
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)
        .build()

    // Explicit SecurityException catch: lint's MissingPermission check only recognizes this
    // form, and POST_NOTIFICATIONS can be revoked between the check above and this call.
    try {
        NotificationManagerCompat.from(context)
            .notify(METERED_WIFI_NOTIFICATION_ID, notification)
    } catch (e: SecurityException) {
        Timber.w(e, "Failed to post the metered-Wi-Fi notice (SecurityException)")
    }
}
