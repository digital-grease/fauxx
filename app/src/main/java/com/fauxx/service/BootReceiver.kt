package com.fauxx.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.fauxx.di.PreferenceKeys
import com.fauxx.di.fauxxDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Restarts the [PhantomForegroundService] after device reboot, if the engine was previously
 * active. Triggered by BOOT_COMPLETED and MY_PACKAGE_REPLACED broadcasts.
 *
 * Uses WorkManager expedited work instead of directly starting the foreground service,
 * because Android 12+ disallows starting dataSync FGS from BOOT_COMPLETED receivers.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Timber.i("Boot/update received, checking if service should restart")

                // Read the enabled flag from DataStore with a timeout to avoid
                // exceeding the BroadcastReceiver's ~10s execution limit.
                val wasEnabled = runBlocking {
                    withTimeoutOrNull(5_000L) {
                        val prefs = context.fauxxDataStore.data.first()
                        prefs[PreferenceKeys.ENABLED] ?: false
                    } ?: false
                }

                if (wasEnabled) {
                    Timber.i("Scheduling service restart via WorkManager")
                    val request = OneTimeWorkRequestBuilder<ServiceRestartWorker>()
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .build()
                    WorkManager.getInstance(context).enqueue(request)
                }
            }
        }
    }
}
