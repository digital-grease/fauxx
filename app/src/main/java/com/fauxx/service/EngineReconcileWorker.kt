package com.fauxx.service

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fauxx.data.model.PoisonProfile
import com.fauxx.di.PreferenceKeys
import com.fauxx.engine.scheduling.AllowedHours
import com.fauxx.util.Clock
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.Calendar

/**
 * Watchdog that recovers a silently-stopped engine (issue #156).
 *
 * The auto-resume path (an exact alarm for quiet hours, WorkManager for constraint pauses)
 * can be dropped without trace: an OEM that blocks AlarmManager wakeups for non-whitelisted
 * apps (the #121 reporter's device), a force-stop, or a process kill in the narrow window
 * before a resume is re-armed. When that happens the engine stays off while DataStore still
 * says `ENABLED=true`, and nothing on the device notices until the user opens the app or
 * reboots.
 *
 * This periodic worker is that missing watchdog. When the engine is enabled but not running,
 * we are inside the user's active hours, and no resume is already pending, it posts the
 * tap-to-resume notification so the user can restart protection with one tap. It deliberately
 * does NOT auto-start the FGS: a periodic worker is a user-absent context, which Android 14+
 * forbids from starting a `specialUse` foreground service — same reason [ResumeWorker] only
 * notifies. On alarm-blocking OEMs this notification is the only recovery short of Shizuku
 * (#151).
 *
 * Reads the profile directly from DataStore (the lightweight pattern [ResumeWorker] and
 * [RetentionWorker] use); no engine dependency, so it works in a fresh process after a kill.
 */
@HiltWorker
class EngineReconcileWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dataStore: DataStore<Preferences>,
    private val clock: Clock,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val prefs = dataStore.data.first()
            val enabled = prefs[PreferenceKeys.ENABLED] ?: false
            val start = prefs[PreferenceKeys.ALLOWED_HOURS_START] ?: PoisonProfile().allowedHoursStart
            val end = prefs[PreferenceKeys.ALLOWED_HOURS_END] ?: PoisonProfile().allowedHoursEnd

            val hour = Calendar.getInstance()
                .apply { timeInMillis = clock.currentTimeMillis() }
                .get(Calendar.HOUR_OF_DAY)
            val withinAllowedHours = AllowedHours.isWithin(hour, start, end)

            val running = PhantomForegroundService.isRunning
            val resumePending = resumeWorkPending()

            if (shouldPromptResume(enabled, running, withinAllowedHours, resumePending)) {
                Timber.i(
                    "EngineReconcileWorker: engine enabled but not running, within active hours, " +
                        "and no resume pending — posting tap-to-resume"
                )
                postResumeNotification(applicationContext)
            }
            Result.success()
        } catch (e: Exception) {
            // Never retry-storm on a transient read error; the next periodic run will reconcile.
            Timber.w(e, "EngineReconcileWorker failed")
            Result.success()
        }
    }

    /**
     * Whether a constraint-based resume (wifi/battery [ResumeSpec.WhenConstraintMet]) is already
     * queued. If so, the engine is legitimately paused inside active hours and the resume will
     * fire on its own — do not nag. Quiet-hours resumes use an exact alarm (not queryable here),
     * but those are gated out by the active-hours check instead.
     */
    private fun resumeWorkPending(): Boolean = try {
        WorkManager.getInstance(applicationContext)
            .getWorkInfosForUniqueWork(RESUME_WORK_NAME).get()
            .any {
                it.state == WorkInfo.State.ENQUEUED ||
                    it.state == WorkInfo.State.RUNNING ||
                    it.state == WorkInfo.State.BLOCKED
            }
    } catch (e: Exception) {
        // Can't tell — fail toward protection: treat as not pending so the watchdog still fires.
        Timber.w(e, "EngineReconcileWorker: could not query resume work state")
        false
    }

    companion object {
        const val WORK_NAME = "fauxx_engine_reconcile"
    }
}

/**
 * Pure decision for the reconcile watchdog: post the tap-to-resume notification only when the
 * engine is enabled, not currently running, inside the user's active hours, and no resume is
 * already pending. Extracted so the truth table is unit-testable without Android.
 */
internal fun shouldPromptResume(
    enabled: Boolean,
    serviceRunning: Boolean,
    withinAllowedHours: Boolean,
    resumeAlreadyPending: Boolean,
): Boolean = enabled && !serviceRunning && withinAllowedHours && !resumeAlreadyPending
