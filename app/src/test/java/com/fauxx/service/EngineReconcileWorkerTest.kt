package com.fauxx.service

import android.app.NotificationManager
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.WorkerFactory
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.fauxx.di.PreferenceKeys
import com.fauxx.di.fauxxDataStore
import com.fauxx.support.FakeClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests the engine-reconcile watchdog (#156): the periodic check that posts the tap-to-resume
 * notification when the engine should be running but isn't (e.g., an OEM dropped the resume
 * alarm). Two halves:
 *  - [shouldPromptResume]'s decision is verified exhaustively as a pure truth table.
 *  - The worker wiring (reads DataStore, honours active hours + a pending resume, posts the
 *    notification) is driven through Robolectric + test WorkManager like [ResumeLoopTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class EngineReconcileWorkerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        // The service's running flag is a process-global companion; another test in the
        // shared fork may have left it true. Start each case from "not running".
        PhantomForegroundService.setRunningForTest(false)
    }

    @After
    fun tearDown() {
        notificationManager().cancelAll()
    }

    // --- pure decision truth table -----------------------------------------------------

    @Test
    fun `shouldPromptResume is true only when enabled, stopped, in-hours, and nothing pending`() {
        for (enabled in listOf(false, true)) {
            for (running in listOf(false, true)) {
                for (within in listOf(false, true)) {
                    for (pending in listOf(false, true)) {
                        val expected = enabled && !running && within && !pending
                        assertEquals(
                            "enabled=$enabled running=$running within=$within pending=$pending",
                            expected,
                            shouldPromptResume(enabled, running, within, pending),
                        )
                    }
                }
            }
        }
    }

    // --- worker wiring -----------------------------------------------------------------

    @Test
    fun `worker posts tap-to-resume when the engine is enabled but not running`() = runBlocking {
        // ENABLED, always-active window (0-0), no pending resume, service not running (default).
        setProfile(enabled = true, allowedStart = 0, allowedEnd = 0)

        val result = buildWorker().doWork()

        assertTrue("worker must succeed", result is ListenableWorker.Result.Success)
        assertNotNull(
            "a stuck-but-enabled engine must get the tap-to-resume notification",
            shadowOf(notificationManager()).getNotification(RESUME_NOTIFICATION_ID),
        )
    }

    @Test
    fun `worker posts nothing when the engine is disabled`() = runBlocking {
        setProfile(enabled = false, allowedStart = 0, allowedEnd = 0)

        val result = buildWorker().doWork()

        assertTrue("worker must still succeed (nothing to do)", result is ListenableWorker.Result.Success)
        assertNull(
            "a user who turned the engine off must not be nagged",
            shadowOf(notificationManager()).getNotification(RESUME_NOTIFICATION_ID),
        )
    }

    @Test
    fun `worker posts nothing when the service is already running`() = runBlocking {
        setProfile(enabled = true, allowedStart = 0, allowedEnd = 0)
        PhantomForegroundService.setRunningForTest(true)

        val result = buildWorker().doWork()

        assertTrue("worker must succeed", result is ListenableWorker.Result.Success)
        assertNull(
            "no prompt while the engine is already running",
            shadowOf(notificationManager()).getNotification(RESUME_NOTIFICATION_ID),
        )
    }

    @Test
    fun `worker posts nothing when a constraint resume is already pending`() = runBlocking {
        setProfile(enabled = true, allowedStart = 0, allowedEnd = 0)
        // A wifi/battery resume is queued and will fire on its own — the watchdog must not
        // pile a second prompt on top of it. The constraint keeps it ENQUEUED/BLOCKED in test.
        ResumeScheduler(context).schedule(ResumeSpec.WhenConstraintMet(batteryNotLow = true))

        val result = buildWorker().doWork()

        assertTrue("worker must succeed", result is ListenableWorker.Result.Success)
        assertNull(
            "no prompt when a resume is already pending",
            shadowOf(notificationManager()).getNotification(RESUME_NOTIFICATION_ID),
        )
    }

    private fun buildWorker(): EngineReconcileWorker =
        TestListenableWorkerBuilder<EngineReconcileWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker =
                    EngineReconcileWorker(appContext, workerParameters, context.fauxxDataStore, FakeClock(0L))
            })
            .build()

    private fun setProfile(enabled: Boolean, allowedStart: Int, allowedEnd: Int) = runBlocking {
        context.fauxxDataStore.edit {
            it[PreferenceKeys.ENABLED] = enabled
            it[PreferenceKeys.ALLOWED_HOURS_START] = allowedStart
            it[PreferenceKeys.ALLOWED_HOURS_END] = allowedEnd
        }
    }

    private fun notificationManager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
