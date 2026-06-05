package com.fauxx.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.fauxx.di.PreferenceKeys
import com.fauxx.di.fauxxDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Auto-resumes [PhantomForegroundService] at a scheduled time (#126).
 *
 * [ResumeScheduler] schedules an exact alarm to this receiver for a time-based
 * ([ResumeSpec.AtTime]) quiet-hours resume. An exact-alarm broadcast is one of Android 14+'s
 * allowed foreground-service-start contexts, so this can start the `specialUse` FGS with no user
 * interaction — which WorkManager's user-absent [ResumeWorker] cannot, hence it only posts a
 * tap-to-resume notification. The exact-alarm permission ([android.Manifest.permission.USE_EXACT_ALARM])
 * is declared in the full flavor only; on the play flavor the scheduler falls back to the
 * notification path, so this receiver effectively only fires on full.
 *
 * Ordering matters for the FGS-start grant: the start is done SYNCHRONOUSLY in [onReceive], inside
 * the alarm broadcast, so the grant is unambiguously in scope (deferring it past an async read
 * could fall outside the grant window). The ENABLED check then runs afterwards — disabling the
 * engine does NOT cancel the pending alarm, so a user who turned the engine off while it was paused
 * must not be force-resumed; if disabled, the just-started service is stopped again (stopping needs
 * no FGS-start grant, so it can safely be async). If the start is denied, falls back to the
 * tap-to-resume notification.
 */
class AlarmResumeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESUME) return
        val appContext = context.applicationContext

        // Start synchronously, within the exact-alarm broadcast, so the Android 14+ alarm-driven
        // FGS-start grant is in scope.
        val started = try {
            ContextCompat.startForegroundService(appContext, PhantomForegroundService.startIntent(appContext))
            Timber.i("AlarmResumeReceiver: auto-resumed the foreground service")
            true
        } catch (e: Exception) {
            Timber.w(e, "AlarmResumeReceiver: FGS start denied; posting resume notification")
            postResumeNotification(appContext)
            false
        }
        if (!started) return

        // Then honour ENABLED: if the user disabled the engine while it was paused, stop the
        // service we just started. Runs async (stopping a running service needs no FGS-start grant).
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val enabled = try {
                    appContext.fauxxDataStore.data.first()[PreferenceKeys.ENABLED] ?: false
                } catch (e: Exception) {
                    // Fail open: prefer resuming protection over silently dropping it on a read error.
                    Timber.w(e, "AlarmResumeReceiver: failed to read ENABLED flag; leaving service running")
                    true
                }
                if (!enabled) {
                    Timber.i("AlarmResumeReceiver: engine disabled while paused; stopping the resumed service")
                    runCatching { appContext.startService(PhantomForegroundService.stopIntent(appContext)) }
                        .onFailure { Timber.w(it, "AlarmResumeReceiver: failed to stop the resumed service") }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_RESUME = "com.fauxx.ALARM_RESUME"
    }
}
