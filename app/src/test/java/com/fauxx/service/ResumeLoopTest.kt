package com.fauxx.service

import android.app.NotificationManager
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.fauxx.di.PreferenceKeys
import com.fauxx.di.fauxxDataStore
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
 * Closes the resume loop's previously-untested half. [EngineResumeSchedulerIntegrationTest]
 * proves the engine's long-pause signal reaches [ResumeScheduler] and enqueues the `fauxx_resume`
 * WorkManager job; this proves the rest of the wire:
 *   - [ResumeScheduler] enqueues exactly one uniquely-named job and [ResumeScheduler.cancel]
 *     removes it (so a manual resume before the worker fires doesn't leave a stale notification).
 *   - [ResumeWorker] posts the tap-to-resume notification when the engine is enabled, and posts
 *     nothing when the user disabled it while paused — the "don't nag a user who turned it off"
 *     contract, which is the single decision that keeps the whole loop from being a nuisance.
 *
 * The worker is driven directly via [TestListenableWorkerBuilder] (doWork runs synchronously in
 * the test coroutine) rather than through WorkManager's executor, whose CoroutineWorker bridge
 * hops to Dispatchers.Default and would race a state assertion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ResumeLoopTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @After
    fun tearDown() {
        notificationManager().cancelAll()
    }

    @Test
    fun `scheduler enqueues one resume job and cancel removes it`() {
        val scheduler = ResumeScheduler(context)
        val workManager = WorkManager.getInstance(context)

        scheduler.schedule(ResumeSpec.AtTime(epochMs = System.currentTimeMillis() + 60_000))
        val enqueued = workManager.getWorkInfosForUniqueWork(RESUME_WORK_NAME).get()
        assertEquals("schedule must enqueue exactly one resume job", 1, enqueued.size)
        assertTrue(
            "the job must be pending, not already failed/cancelled, got ${enqueued[0].state}",
            enqueued[0].state == WorkInfo.State.ENQUEUED || enqueued[0].state == WorkInfo.State.BLOCKED,
        )

        scheduler.cancel()
        val afterCancel = workManager.getWorkInfosForUniqueWork(RESUME_WORK_NAME).get()
        assertTrue(
            "cancel must cancel the pending resume job",
            afterCancel.all { it.state == WorkInfo.State.CANCELLED },
        )
    }

    @Test
    fun `worker posts the resume notification when the engine is enabled`() = runBlocking {
        setEngineEnabled(true)

        val result = TestListenableWorkerBuilder<ResumeWorker>(context).build().doWork()

        assertTrue("worker must succeed", result is ListenableWorker.Result.Success)
        assertNotNull(
            "an enabled engine must get the tap-to-resume notification",
            shadowOf(notificationManager()).getNotification(RESUME_NOTIFICATION_ID),
        )
    }

    @Test
    fun `worker posts nothing when the user disabled the engine while paused`() = runBlocking {
        setEngineEnabled(false)

        val result = TestListenableWorkerBuilder<ResumeWorker>(context).build().doWork()

        assertTrue("worker must still succeed (nothing to do)", result is ListenableWorker.Result.Success)
        assertNull(
            "a user who turned the engine off must not be nagged to resume",
            shadowOf(notificationManager()).getNotification(RESUME_NOTIFICATION_ID),
        )
    }

    private fun setEngineEnabled(enabled: Boolean) = runBlocking {
        context.fauxxDataStore.edit { it[PreferenceKeys.ENABLED] = enabled }
    }

    private fun notificationManager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private companion object {
        const val RESUME_WORK_NAME = "fauxx_resume"
    }
}
