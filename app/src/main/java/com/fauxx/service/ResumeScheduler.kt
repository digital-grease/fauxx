package com.fauxx.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Specification for when a [ResumeWorker] should fire and post the tap-to-resume
 * notification. Produced by [com.fauxx.engine.PoisonEngine] when it decides to stop
 * the FGS during a long pause, consumed by [PhantomForegroundService] which calls
 * [ResumeScheduler] before tearing itself down.
 */
sealed class ResumeSpec {
    /** Fire at a specific wall-clock time (epoch ms). Used for quiet-hours resume. */
    data class AtTime(val epochMs: Long) : ResumeSpec()

    /** Fire when constraints are met. Used for wifi/battery pauses. */
    data class WhenConstraintMet(
        val network: NetworkType? = null,
        val batteryNotLow: Boolean = false
    ) : ResumeSpec()
}

private const val WORK_NAME = "fauxx_resume"

/**
 * Schedules a [ResumeWorker] to fire under a given [ResumeSpec].
 *
 * Single uniquely-named WorkManager entry (`fauxx_resume`) with [ExistingWorkPolicy.REPLACE]
 * — only one pending resume notification exists at a time. If the user opens the app and
 * resumes manually before the scheduler fires, [cancel] should be called from the
 * service start path so a stale notification doesn't surface later.
 */
@Singleton
class ResumeScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun schedule(spec: ResumeSpec) {
        val builder = OneTimeWorkRequestBuilder<ResumeWorker>()

        when (spec) {
            is ResumeSpec.AtTime -> {
                val delayMs = (spec.epochMs - System.currentTimeMillis()).coerceAtLeast(0L)
                builder.setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                Timber.d("ResumeScheduler: scheduling at ${spec.epochMs} (in ${delayMs / 1000}s)")
            }
            is ResumeSpec.WhenConstraintMet -> {
                val constraintsBuilder = Constraints.Builder()
                spec.network?.let { constraintsBuilder.setRequiredNetworkType(it) }
                if (spec.batteryNotLow) constraintsBuilder.setRequiresBatteryNotLow(true)
                builder.setConstraints(constraintsBuilder.build())
                Timber.d("ResumeScheduler: scheduling when constraint met (network=${spec.network}, batteryNotLow=${spec.batteryNotLow})")
            }
        }

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            builder.build()
        )
    }

    /** Cancel any pending resume work. Call when the service is being started or the user disables the engine. */
    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        Timber.d("ResumeScheduler: cancelled")
    }
}
