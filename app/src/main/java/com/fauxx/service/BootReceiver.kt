package com.fauxx.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.fauxx.di.PreferenceKeys
import com.fauxx.di.fauxxDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

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

                // Read the enabled flag from DataStore. runBlocking is acceptable here
                // because BroadcastReceiver.onReceive() has a short-lived synchronous scope.
                val wasEnabled = runBlocking {
                    val prefs = context.fauxxDataStore.data.first()
                    prefs[PreferenceKeys.ENABLED] ?: false
                }

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
