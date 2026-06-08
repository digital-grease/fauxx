package com.fauxx.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.edit
import com.fauxx.di.PreferenceKeys
import com.fauxx.di.fauxxDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests the headless "Start" action receiver (#121). The v0.3.1 Start action just reopened the
 * app; this receiver starts the foreground service directly from the notification-action tap,
 * which is an allowed FGS-start context on Android 14+.
 *
 * Verifies: the broadcast starts PhantomForegroundService with ACTION_START and clears the
 * resume notification; it ignores unrelated actions; and it honours the ENABLED flag (a user who
 * disabled the engine just before tapping gets the service stopped again, mirroring
 * AlarmResumeReceiver). Pinned to API 32 so the helper's POST_NOTIFICATIONS gate is moot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = android.app.Application::class)
class StartEngineReceiverTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancelAll()
        runBlocking {
            context.fauxxDataStore.edit { it.remove(PreferenceKeys.ENABLED) }
        }
    }

    @After
    fun tearDown() {
        notificationManager.cancelAll()
    }

    @Test
    fun `start action starts the foreground service and clears the resume notification`() {
        primeEnabled(true)
        postResumeNotification(context)
        assertNotNull(
            "precondition: the resume notification is showing",
            shadowOf(notificationManager).getNotification(RESUME_NOTIFICATION_ID),
        )

        StartEngineReceiver().onReceive(context, Intent(StartEngineReceiver.ACTION_START_ENGINE))

        val started = shadowOf(context as android.app.Application).nextStartedService
        assertNotNull("the receiver must start the foreground service", started)
        assertEquals(
            "must target PhantomForegroundService",
            PhantomForegroundService::class.java.name,
            started.component?.className,
        )
        assertEquals(
            "must start it (not stop it)",
            PhantomForegroundService.ACTION_START,
            started.action,
        )
        assertNull(
            "the resume notification must be cleared once the service is starting",
            shadowOf(notificationManager).getNotification(RESUME_NOTIFICATION_ID),
        )
    }

    @Test
    fun `ignores unrelated broadcast actions`() {
        primeEnabled(true)

        StartEngineReceiver().onReceive(context, Intent("com.fauxx.SOMETHING_ELSE"))

        assertNull(
            "receiver must not start the service for an action it didn't filter for",
            shadowOf(context as android.app.Application).nextStartedService,
        )
    }

    @Test
    fun `an explicit start re-enables the engine and never stops it`() {
        // The user toggled the engine off while the notification lingered, then tapped Start.
        // Start must WIN: persist ENABLED=true and keep the service running, NOT stop it (which
        // would make the button a silent no-op). This deliberately differs from the autonomous
        // AlarmResumeReceiver.
        primeEnabled(false)

        StartEngineReceiver().onReceive(context, Intent(StartEngineReceiver.ACTION_START_ENGINE))

        // Poll for the async ENABLED=true write (goAsync coroutine on Dispatchers.Default).
        var enabled = false
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            enabled = runBlocking { context.fauxxDataStore.data.first()[PreferenceKeys.ENABLED] ?: false }
            if (enabled) break
            Thread.sleep(25)
        }
        assertEquals("an explicit Start tap must persist ENABLED=true", true, enabled)

        val actions = generateSequence { shadowOf(context as android.app.Application).nextStartedService }
            .map { it.action }.toList()
        assertEquals("must start the service", true, actions.contains(PhantomForegroundService.ACTION_START))
        assertEquals(
            "must NOT stop the service on an explicit Start",
            false,
            actions.contains(PhantomForegroundService.ACTION_STOP),
        )
    }

    @Test
    fun `leaves the notification up when the foreground-service start is denied`() {
        // The aggressive-OEM scenario: startForegroundService is denied. The receiver must not
        // crash and must leave the resume notification in place so the user can fall back to the
        // body tap (open the app).
        primeEnabled(true)
        postResumeNotification(context)
        val denyingContext = object : android.content.ContextWrapper(context) {
            override fun startForegroundService(service: Intent): android.content.ComponentName? {
                throw IllegalStateException("ForegroundServiceStartNotAllowedException (simulated)")
            }
            override fun getApplicationContext(): Context = this
        }

        StartEngineReceiver().onReceive(denyingContext, Intent(StartEngineReceiver.ACTION_START_ENGINE))

        assertNotNull(
            "a denied start must leave the resume notification up for the body-tap fallback",
            shadowOf(notificationManager).getNotification(RESUME_NOTIFICATION_ID),
        )
    }

    private fun primeEnabled(enabled: Boolean) {
        runBlocking {
            context.fauxxDataStore.edit { it[PreferenceKeys.ENABLED] = enabled }
        }
    }
}
