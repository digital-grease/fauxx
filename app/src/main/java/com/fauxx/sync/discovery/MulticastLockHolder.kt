package com.fauxx.sync.discovery

import android.content.Context
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds a single Wi-Fi [WifiManager.MulticastLock] while advertising/browsing (E13 #178). Android
 * drops inbound multicast by default, so without a held lock NsdManager advertises fine but
 * discovers nothing. Requires `CHANGE_WIFI_MULTICAST_STATE`. A pure Android tax with no desktop
 * analog.
 */
@Singleton
class MulticastLockHolder @Inject constructor(
    @ApplicationContext context: Context
) {
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var lock: WifiManager.MulticastLock? = null

    /** Acquire the (single, non-reference-counted) lock if not already held. */
    @Synchronized
    fun acquire() {
        if (lock?.isHeld == true) return
        lock = try {
            wifiManager.createMulticastLock(LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Timber.w(e, "LAN sync: could not acquire multicast lock")
            null
        }
    }

    /** Release the lock, symmetric with [acquire]. Safe to call when not held. */
    @Synchronized
    fun release() {
        try {
            if (lock?.isHeld == true) lock?.release()
        } catch (e: Exception) {
            Timber.w(e, "LAN sync: could not release multicast lock")
        } finally {
            lock = null
        }
    }

    private companion object {
        const val LOCK_TAG = "fauxx-sync"
    }
}
