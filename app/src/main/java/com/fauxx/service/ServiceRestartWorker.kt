package com.fauxx.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.fauxx.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

private const val RESTART_CHANNEL_ID = "fauxx_restart"
private const val RESTART_NOTIFICATION_ID = 2

/**
 * WorkManager worker that starts [PhantomForegroundService] after boot.
 *
 * Android 12+ disallows starting `dataSync` foreground services directly from
 * `BOOT_COMPLETED` receivers. This worker uses [getForegroundInfo] so that
 * WorkManager can promote it to a foreground-capable context via expedited work,
 * which then has permission to launch the real foreground service.
 */
@HiltWorker
class ServiceRestartWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            Timber.i("ServiceRestartWorker: starting PhantomForegroundService")
            ContextCompat.startForegroundService(
                applicationContext,
                PhantomForegroundService.startIntent(applicationContext)
            )
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "ServiceRestartWorker: failed to start service")
            Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            RESTART_CHANNEL_ID,
            "Service Restart",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(applicationContext, RESTART_CHANNEL_ID)
            .setContentTitle("Fauxx")
            .setContentText("Restarting protection…")
            .setSmallIcon(R.drawable.ic_notification)
            .setSilent(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(RESTART_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(RESTART_NOTIFICATION_ID, notification)
        }
    }
}
