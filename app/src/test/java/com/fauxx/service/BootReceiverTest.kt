package com.fauxx.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.edit
import com.fauxx.di.PreferenceKeys
import com.fauxx.di.fauxxDataStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Integration test for [BootReceiver] — covers the path with zero existing test
 * coverage flagged in `.devloop/spikes/integration-testing-audit.md`. Drives the
 * full receiver flow end-to-end:
 *
 *   DataStore primed → ACTION_BOOT_COMPLETED → DataStore read in runBlocking →
 *   conditional post via NotificationManagerCompat → notification appears.
 *
 * Pinned to API 32 (`@Config(sdk = [32])`) so the Android-13+ POST_NOTIFICATIONS
 * runtime permission check in [postResumeNotification] doesn't gate the test —
 * the receiver code itself is API-agnostic, only the helper's permission check
 * is gated. Pre-Tiramisu doesn't require the runtime permission, so the
 * notification posts without a Robolectric permission-grant dance.
 *
 * What a failure here would catch:
 *  - Receiver wiring breaks (missing intent filter, wrong action handling).
 *  - DataStore read inside `runBlocking { withTimeoutOrNull }` hangs or returns
 *    wrong defaults.
 *  - The conditional ("both flags must be true") gets inverted.
 *  - [postResumeNotification] regresses in channel-creation or pending-intent setup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = android.app.Application::class)
class BootReceiverTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancelAll()
        // Reset the data store between tests since RuntimeEnvironment is per-class.
        runBlocking {
            context.fauxxDataStore.edit { prefs ->
                prefs.remove(PreferenceKeys.ENABLED)
                prefs.remove(PreferenceKeys.RESUME_ON_BOOT)
            }
        }
    }

    @After
    fun tearDown() {
        notificationManager.cancelAll()
    }

    @Test
    fun `posts resume notification when engine was enabled and resume-on-boot is true`() {
        primeDataStore(enabled = true, resumeOnBoot = true)

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val active = notificationManager.activeNotifications
        assertEquals("expected exactly one notification posted", 1, active.size)
        assertEquals(
            "notification must use the resume channel",
            RESUME_CHANNEL_ID,
            active[0].notification.channelId
        )
    }

    @Test
    fun `does not post when engine was disabled pre-reboot`() {
        primeDataStore(enabled = false, resumeOnBoot = true)

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals(
            "no notification should post when engine was disabled",
            0,
            notificationManager.activeNotifications.size
        )
    }

    @Test
    fun `does not post when resume-on-boot is disabled even if engine was enabled`() {
        primeDataStore(enabled = true, resumeOnBoot = false)

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals(
            "user opted out of resume-on-boot — no notification",
            0,
            notificationManager.activeNotifications.size
        )
    }

    @Test
    fun `responds to MY_PACKAGE_REPLACED the same as BOOT_COMPLETED`() {
        primeDataStore(enabled = true, resumeOnBoot = true)

        BootReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        assertEquals(
            "app updates should also surface the resume notification",
            1,
            notificationManager.activeNotifications.size
        )
    }

    @Test
    fun `ignores unrelated broadcasts`() {
        primeDataStore(enabled = true, resumeOnBoot = true)

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BATTERY_LOW))

        assertEquals(
            "receiver must not act on broadcasts it didn't filter for",
            0,
            notificationManager.activeNotifications.size
        )
    }

    @Test
    fun `defaults when DataStore is empty match the safe fallback`() {
        // Don't prime anything — DataStore returns null for both keys. Receiver
        // falls back to (ENABLED=false, RESUME_ON_BOOT=true) per the runBlocking
        // expression in BootReceiver.kt:42. Result: no notification (since enabled
        // defaults to false).
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertEquals(
            "empty DataStore must not post on its own",
            0,
            notificationManager.activeNotifications.size
        )
    }

    private fun primeDataStore(enabled: Boolean, resumeOnBoot: Boolean) {
        runBlocking {
            context.fauxxDataStore.edit { prefs ->
                prefs[PreferenceKeys.ENABLED] = enabled
                prefs[PreferenceKeys.RESUME_ON_BOOT] = resumeOnBoot
            }
        }
    }
}
