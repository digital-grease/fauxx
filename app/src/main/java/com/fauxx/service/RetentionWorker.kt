package com.fauxx.service

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fauxx.data.db.ActionLogDao
import com.fauxx.di.PreferenceKeys
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * Periodic worker (issue #73) that prunes action-log entries older than the user's configured
 * retention window so the on-device audit log can't grow without bound.
 *
 * Reads [PreferenceKeys.LOG_RETENTION_DAYS] (default [DEFAULT_RETENTION_DAYS]) directly from
 * DataStore — the same lightweight pattern [ResumeWorker] uses for the ENABLED flag — and calls
 * [ActionLogDao.deleteOlderThan]. Scheduled daily from [com.fauxx.FauxxApp]; because the setting
 * is read each run, changing it takes effect on the next daily prune.
 */
@HiltWorker
class RetentionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val actionLogDao: ActionLogDao,
    private val dataStore: DataStore<Preferences>,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            // Clamp to the setting's documented range (1..90) so an out-of-band pref value can't
            // push the cutoff far into the past and silently disable pruning — the one thing this
            // worker exists to prevent.
            val days = (dataStore.data.first()[PreferenceKeys.LOG_RETENTION_DAYS] ?: DEFAULT_RETENTION_DAYS)
                .coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS)
            val cutoff = System.currentTimeMillis() - days * MILLIS_PER_DAY
            actionLogDao.deleteOlderThan(cutoff)
            Timber.i("RetentionWorker: pruned action-log entries older than $days day(s)")
            Result.success()
        } catch (e: Exception) {
            Timber.w(e, "RetentionWorker: prune failed; will retry")
            Result.retry()
        }
    }

    companion object {
        const val DEFAULT_RETENTION_DAYS = 7
        const val MIN_RETENTION_DAYS = 1
        const val MAX_RETENTION_DAYS = 90
        const val WORK_NAME = "fauxx_log_retention"
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}
