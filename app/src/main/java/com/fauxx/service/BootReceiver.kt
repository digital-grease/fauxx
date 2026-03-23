package com.fauxx.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

private const val TAG = "BootReceiver"

/**
 * Restarts the [PhantomForegroundService] after device reboot, if the engine was previously
 * active. Triggered by BOOT_COMPLETED and MY_PACKAGE_REPLACED broadcasts.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "Boot/update received, checking if service should restart")

                // Only restart if the engine was enabled before reboot
                val prefs = context.getSharedPreferences("fauxx_secure_prefs", Context.MODE_PRIVATE)
                val wasEnabled = prefs.getBoolean("enabled", false)

                if (wasEnabled) {
                    Log.i(TAG, "Restarting PhantomForegroundService after boot")
                    ContextCompat.startForegroundService(
                        context,
                        PhantomForegroundService.startIntent(context)
                    )
                }
            }
        }
    }
}
