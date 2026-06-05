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
 * Honours [PreferenceKeys.ENABLED] (like [ResumeWorker]) so a user who disabled the engine while
 * it was paused is not force-resumed, and falls back to the notification if the FGS start is
 * denied. On a successful start, [PhantomForegroundService] cancels any pending fallback work.
 */
class AlarmResumeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESUME) return
        val appContext = context.applicationContext
        // Reading DataStore + starting the service is async; keep the broadcast alive across it.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val enabled = try {
                    appContext.fauxxDataStore.data.first()[PreferenceKeys.ENABLED] ?: false
                } catch (e: Exception) {
                    Timber.w(e, "AlarmResumeReceiver: failed to read ENABLED flag")
                    false
                }
                if (!enabled) {
                    Timber.i("AlarmResumeReceiver: engine disabled by user; not auto-resuming")
                    return@launch
                }
                try {
                    ContextCompat.startForegroundService(
                        appContext,
                        PhantomForegroundService.startIntent(appContext)
                    )
                    Timber.i("AlarmResumeReceiver: auto-resumed the foreground service")
                } catch (e: Exception) {
                    // FGS start unexpectedly denied — fall back to the tap-to-resume notification.
                    Timber.w(e, "AlarmResumeReceiver: FGS start denied; posting resume notification")
                    postResumeNotification(appContext)
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
