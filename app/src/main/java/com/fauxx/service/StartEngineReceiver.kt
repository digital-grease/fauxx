package com.fauxx.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import com.fauxx.di.PreferenceKeys
import com.fauxx.di.fauxxDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Starts the engine headlessly when the user taps the "Start" action on the resume
 * notification (#121).
 *
 * Tapping a notification action is one of Android 14+'s allowed foreground-service-start
 * contexts ("the user performs an action on a UI element related to your app"), so this can
 * start the `specialUse` FGS WITHOUT opening the app — unlike the notification body tap, which
 * routes through [com.fauxx.ui.MainActivity]. This is the fix for the original report that the
 * v0.3.1 Start action merely reopened the app.
 *
 * The start runs SYNCHRONOUSLY in [onReceive] so the UI-interaction FGS-start grant is
 * unambiguously in scope (deferring it past an async read could fall outside the grant window),
 * mirroring [AlarmResumeReceiver]. It then persists ENABLED=true: the user explicitly asked to
 * start, so their intent wins even if the flag had been toggled off while this notification
 * lingered (otherwise the next reconcile/boot would silently undo the start). This deliberately
 * DIFFERS from [AlarmResumeReceiver], whose autonomous alarm must respect a user's ENABLED=false.
 * If the start is denied, the notification is left in place so the user can fall back to the body
 * tap (which opens the app).
 */
class StartEngineReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_START_ENGINE) return
        val appContext = context.applicationContext

        // Start synchronously, within the notification-action broadcast, so the Android 14+
        // UI-interaction FGS-start grant is in scope.
        val started = try {
            ContextCompat.startForegroundService(appContext, PhantomForegroundService.startIntent(appContext))
            Timber.i("StartEngineReceiver: started the foreground service from the notification action")
            true
        } catch (e: Exception) {
            Timber.w(e, "StartEngineReceiver: FGS start denied; leaving the resume notification up")
            false
        }
        if (!started) return

        // The user acted on the prompt and the service is starting — clear it.
        runCatching {
            (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(RESUME_NOTIFICATION_ID)
        }.onFailure { Timber.w(it, "StartEngineReceiver: failed to cancel the resume notification") }

        // Persist the user's explicit intent: ENABLED=true. Idempotent in the common case (the
        // flag is already true), but it also closes the race where the engine was toggled off
        // while this notification lingered — without it, the next reconcile/boot would stop or
        // skip the engine the user just asked to start. Async (DataStore write is suspend);
        // goAsync keeps the receiver alive for it. pending may be null under test harnesses that
        // invoke onReceive directly.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                runCatching {
                    appContext.fauxxDataStore.edit { it[PreferenceKeys.ENABLED] = true }
                }.onFailure { Timber.w(it, "StartEngineReceiver: failed to persist ENABLED=true") }
            } finally {
                pending?.finish()
            }
        }
    }

    companion object {
        const val ACTION_START_ENGINE = "com.fauxx.START_ENGINE"
    }
}
