package com.fauxx.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fauxx.di.PreferenceKeys
import com.fauxx.di.fauxxDataStore
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * Posts the tap-to-resume notification when fired by [ResumeScheduler].
 *
 * Triggered by:
 * - Quiet-hours end (initial-delay scheduled at start of next allowed window)
 * - Constraint satisfaction (network/battery, after prolonged wifi-only or battery pause)
 *
 * Honours [PreferenceKeys.ENABLED] so a user who disabled the engine while paused
 * doesn't get nagged. Plain [CoroutineWorker] — no Hilt deps needed; falls through
 * the [androidx.hilt.work.HiltWorkerFactory] to WorkManager's default factory.
 */
class ResumeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val enabled = try {
            applicationContext.fauxxDataStore.data.first()[PreferenceKeys.ENABLED] ?: false
        } catch (e: Exception) {
            Timber.w(e, "ResumeWorker: failed to read ENABLED flag")
            false
        }
        if (!enabled) {
            Timber.i("ResumeWorker: engine disabled by user; no notification posted")
            return Result.success()
        }
        Timber.i("ResumeWorker: posting tap-to-resume notification")
        postResumeNotification(applicationContext)
        return Result.success()
    }
}
