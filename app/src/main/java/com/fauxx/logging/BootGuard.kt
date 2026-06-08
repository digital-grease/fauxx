package com.fauxx.logging

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects splash-hang loops where the UI thread blocks before the app finishes starting,
 * and signals "safe mode" so callers can disable the engine and let the user re-enable
 * once the underlying provider issue clears (typically a WebView constructor hang on
 * broken device + WebView-provider combinations, e.g., Pixels on Android 16, LineageOS +
 * microG).
 *
 * Originally added to defend against the in-app Layer 2 scraper firing at cold boot
 * (issue #55); that scrape path was retired in v0.3.0 (issue #52), but BootGuard stays
 * as defense in depth for the remaining AdPollution / Cookie / DiverseBrowsing WebView
 * initialization paths that can still hang Main during engine startup.
 *
 * Mechanism:
 * - [recordBootStart] increments a counter in a dedicated SharedPreferences file (separate
 *   from DataStore so this guard does not depend on DataStore being healthy). Call it at
 *   the top of [com.fauxx.ui.MainActivity.onCreate], before anything that can hang Main.
 *   INTERACTIVE starts only: background process starts (BootReceiver, WorkManager
 *   workers, AlarmResumeReceiver) must never feed the counter, because no Activity
 *   follows them, [recordBootSuccess] can never run, and healthy installs would
 *   accumulate "failures" — a reboot plus one idle day used to safe-mode the app
 *   (issue #157). Repeat calls in the same process (activity recreation) are ignored.
 * - [recordBootSuccess] resets the counter to zero. Schedule it via the main-thread
 *   handler with a few seconds of delay; if the main thread is hung, the callback never
 *   fires and the counter survives to the next boot.
 * - [isInSafeMode] returns true once the counter reaches [SAFE_MODE_THRESHOLD]. Callers
 *   should treat that as a signal to skip auto-starting the engine / write
 *   `LAYER2_ENABLED=false` and `ENABLED=false`.
 *
 * Uses synchronous `commit()` because a write that loses to a crash defeats the purpose.
 * Writes are tiny (a single int) so commit cost is negligible.
 */
@Singleton
class BootGuard @Inject constructor(@ApplicationContext context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Process-level guard: activity recreation (rotation, config change) re-runs
     * MainActivity.onCreate in the same process, and three quick rotations inside the
     * success-delay window must not read as a hang loop.
     */
    @Volatile
    private var startRecordedThisProcess = false

    /**
     * Count an interactive process start.
     *
     * @return true when this call actually counted (first call in this process); false on
     *   repeat calls (activity recreation), so callers can skip once-per-process work
     *   like arming the safe-mode recovery notice.
     */
    fun recordBootStart(): Boolean {
        if (startRecordedThisProcess) return false
        startRecordedThisProcess = true
        val next = prefs.getInt(KEY_BOOT_COUNTER, 0) + 1
        prefs.edit().putInt(KEY_BOOT_COUNTER, next).commit()
        return true
    }

    fun recordBootSuccess() {
        prefs.edit().putInt(KEY_BOOT_COUNTER, 0).commit()
    }

    fun isInSafeMode(): Boolean =
        prefs.getInt(KEY_BOOT_COUNTER, 0) >= SAFE_MODE_THRESHOLD

    /**
     * Set once when [isInSafeMode] first becomes true, so the UI can show a one-time
     * "Layer 2 was disabled because of repeated startup failures" notice that survives
     * the counter reset which happens once the app boots successfully.
     */
    fun markRecoveryTriggered() {
        prefs.edit().putBoolean(KEY_RECOVERY_PENDING, true).commit()
    }

    fun consumePendingRecoveryNotice(): Boolean {
        val pending = prefs.getBoolean(KEY_RECOVERY_PENDING, false)
        if (pending) {
            prefs.edit().putBoolean(KEY_RECOVERY_PENDING, false).commit()
        }
        return pending
    }

    companion object {
        const val PREFS_NAME = "fauxx_boot_guard"
        const val KEY_BOOT_COUNTER = "boot_counter"
        const val KEY_RECOVERY_PENDING = "recovery_pending"

        /**
         * Two consecutive interactive starts without [recordBootSuccess] in between trip
         * safe mode. Because the counter is incremented on arrival, a single process
         * death inside the 4-second success window (ANR, OEM kill, a swipe-away that
         * takes the process with it) arms the counter, and the NEXT interactive start
         * lands in safe mode regardless of its own health. That is the intended hang-loop
         * signature, and also the residual false-positive case (unchanged from the
         * original design). The cost is bounded: one notice toast, two toggles to
         * re-enable, and the counter self-resets on the next clean start.
         */
        const val SAFE_MODE_THRESHOLD = 2

        /** Reset delay applied to [recordBootSuccess] in MainActivity. */
        const val BOOT_SUCCESS_DELAY_MS = 4_000L
    }
}
