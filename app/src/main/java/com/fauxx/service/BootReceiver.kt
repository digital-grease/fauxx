package com.fauxx.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fauxx.di.PreferenceKeys
import com.fauxx.di.fauxxDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Handles post-reboot / post-update resumption of the [PhantomForegroundService].
 *
 * Android 14+ (targetSdk 36) forbids starting the foreground service from any
 * BOOT_COMPLETED context chain — including WorkManager expedited work triggered by boot.
 * So instead of auto-restarting the FGS, we post a "tap to resume protection" notification.
 * Tapping it opens [com.fauxx.ui.MainActivity] with `EXTRA_RESUME_ENGINE`, and the engine
 * starts from user interaction — always an allowed FGS-start context.
 *
 * Posting the notification requires BOTH:
 *  - [PreferenceKeys.ENABLED] was true pre-reboot, AND
 *  - [PreferenceKeys.RESUME_ON_BOOT] is true (user-controlled toggle in Settings).
 *
 * Notification posting is delegated to [postResumeNotification].
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Timber.i("Boot/update received, checking if engine should resume")

                // Read both flags from DataStore in a single read with a timeout to avoid
                // exceeding the BroadcastReceiver's ~10s execution limit.
                val (wasEnabled, resumeOnBoot) = runBlocking {
                    withTimeoutOrNull(5_000L) {
                        val prefs = context.fauxxDataStore.data.first()
                        (prefs[PreferenceKeys.ENABLED] ?: false) to
                            (prefs[PreferenceKeys.RESUME_ON_BOOT] ?: true)
                    } ?: (false to true)
                }

                when {
                    !wasEnabled -> Timber.i("Engine was disabled pre-reboot; no action")
                    !resumeOnBoot -> Timber.i("Resume-on-boot disabled by user; no action")
                    else -> {
                        Timber.i("Engine was enabled pre-reboot; posting resume notification")
                        postResumeNotification(context)
                    }
                }
            }
        }
    }
}
