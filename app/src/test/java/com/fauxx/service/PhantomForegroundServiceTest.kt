package com.fauxx.service

import androidx.work.NetworkType
import com.fauxx.engine.PoisonEngine
import com.fauxx.engine.modules.MockLocationProviderCleaner
import com.fauxx.engine.webview.PhantomWebViewPool
import com.fauxx.logging.EncryptedFileTree
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Lifecycle wiring test for [PhantomForegroundService].
 *
 * Locks in the contract that when [PoisonEngine] signals a long pause via the callback
 * registered through [PoisonEngine.setOnLongPause], the service:
 *   1. Forwards the [ResumeSpec] to [ResumeScheduler.schedule] so a tap-to-resume
 *      notification is queued for the right wake-up condition.
 *   2. Stops itself (foreground state torn down, service stopped), freeing the
 *      Android 14+ foreground-service slot.
 *
 * Both halves of #1 and #2 are needed: scheduling without stopping leaves the FGS up
 * (the original bug); stopping without scheduling means the user gets no notification
 * when conditions are met. This test covers the seam between the engine and the
 * platform that no unit test of [PoisonEngine] alone could exercise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PhantomForegroundServiceTest {

    @After
    fun tearDown() {
        // Starting the service flips the process-global isRunning flag; reset it so it
        // can't leak into other tests in the shared JVM fork (e.g. EngineReconcileWorkerTest).
        PhantomForegroundService.setRunningForTest(false)
    }

    @Test
    fun `onEngineResigned schedules the resume spec and stops the service`() {
        val service = Robolectric.buildService(PhantomForegroundService::class.java).get()
        val resumeScheduler: ResumeScheduler = mockk(relaxed = true)
        val encryptedFileTree: EncryptedFileTree = mockk(relaxed = true)
        service.poisonEngine = mockk(relaxed = true)
        service.webViewPool = mockk(relaxed = true)
        service.resumeScheduler = resumeScheduler
        service.encryptedFileTree = encryptedFileTree

        val spec = ResumeSpec.WhenConstraintMet(network = NetworkType.UNMETERED)
        service.onEngineResigned(spec)

        verify(exactly = 1) { resumeScheduler.schedule(spec) }
        // Logs are flushed before the FGS tears down so a post-resign kill can't drop them (#158).
        verify(exactly = 1) { encryptedFileTree.flush() }
        val shadow = Shadows.shadowOf(service)
        assertTrue("service must have called stopSelf", shadow.isStoppedBySelf)
    }

    @Test
    fun `ACTION_START wires resign and active callbacks and only the active signal cancels the stale resume`() {
        val service = Robolectric.buildService(PhantomForegroundService::class.java).get()
        val engine: PoisonEngine = mockk(relaxed = true)
        val resumeScheduler: ResumeScheduler = mockk(relaxed = true) {
            every { cancel() } just Runs
            every { schedule(any()) } just Runs
        }
        service.poisonEngine = engine
        service.webViewPool = mockk<PhantomWebViewPool>(relaxed = true)
        service.resumeScheduler = resumeScheduler
        service.encryptedFileTree = mockk(relaxed = true)

        // Capture both callbacks the service registers with the engine.
        val resignCb = slot<(ResumeSpec) -> Unit>()
        val activeCb = slot<() -> Unit>()
        every { engine.setOnLongPause(capture(resignCb)) } just Runs
        every { engine.setOnActive(capture(activeCb)) } just Runs

        val startIntent = PhantomForegroundService.startIntent(service)
        service.onStartCommand(startIntent, 0, 1)

        // The stale resume is NOT cancelled on start anymore (#156): cancelling before the
        // engine confirms it is running could wipe a still-needed resume if the process is
        // killed during engine init.
        verify(exactly = 0) { resumeScheduler.cancel() }
        verify(exactly = 1) { engine.setOnLongPause(any()) }
        verify(exactly = 1) { engine.setOnActive(any()) }
        verify(exactly = 1) { engine.start() }

        // The active callback is what retires the stale resume — once the engine has
        // committed to running.
        assertNotNull("active callback must have been captured", activeCb.captured)
        activeCb.captured()
        verify(exactly = 1) { resumeScheduler.cancel() }

        // The resign callback still schedules a fresh resume and stops the service.
        assertNotNull("resign callback must have been captured", resignCb.captured)
        val spec = ResumeSpec.AtTime(System.currentTimeMillis() + 60_000)
        resignCb.captured(spec)
        verify(exactly = 1) { resumeScheduler.schedule(spec) }
        val shadow = Shadows.shadowOf(service)
        assertTrue("invoking the resign callback must stop the service", shadow.isStoppedBySelf)
    }

    @Test
    fun `ACTION_STOP cancels the pending resume and never schedules a new one`() {
        val service = Robolectric.buildService(PhantomForegroundService::class.java).get()
        val resumeScheduler: ResumeScheduler = mockk(relaxed = true)
        service.poisonEngine = mockk(relaxed = true)
        service.webViewPool = mockk(relaxed = true)
        service.resumeScheduler = resumeScheduler
        service.encryptedFileTree = mockk(relaxed = true)

        // Start first so notificationJob is non-null and stopForeground is meaningful. The
        // relaxed engine mock never fires onActive, so start() alone cancels nothing.
        service.onStartCommand(PhantomForegroundService.startIntent(service), 0, 1)
        // Then issue the user-initiated stop.
        val stopIntent = PhantomForegroundService.stopIntent(service)
        service.onStartCommand(stopIntent, 0, 2)

        // Disabling the engine retires any pending resume (so a dropped-but-armed resume
        // can't fire later) and must never queue a new tap-to-resume nag.
        verify(exactly = 1) { resumeScheduler.cancel() }
        verify(exactly = 0) { resumeScheduler.schedule(any()) }
    }

    @Test
    fun `onTaskRemoved sweeps the mock-location provider and stops the engine`() {
        // Swipe-away can kill the service without onDestroy on some OEMs, orphaning the system
        // mock-location provider (finding #6 / issue #66). The service must remove it eagerly.
        val service = Robolectric.buildService(PhantomForegroundService::class.java).get()
        val cleaner: MockLocationProviderCleaner = mockk(relaxed = true)
        val engine: PoisonEngine = mockk(relaxed = true)
        service.poisonEngine = engine
        service.webViewPool = mockk(relaxed = true)
        service.resumeScheduler = mockk(relaxed = true)
        service.encryptedFileTree = mockk(relaxed = true)
        service.mockLocationProviderCleaner = cleaner

        service.onTaskRemoved(null)

        verify(exactly = 1) { cleaner.clearOrphanedProvider() }
        verify(exactly = 1) { engine.stop() }
    }

    @Test
    fun `onDestroy sweeps the mock-location provider`() {
        // onDestroy's engine teardown is async and can lose the race to process death, so the
        // provider removal must also run synchronously here.
        val service = Robolectric.buildService(PhantomForegroundService::class.java).get()
        val cleaner: MockLocationProviderCleaner = mockk(relaxed = true)
        service.poisonEngine = mockk(relaxed = true)
        service.webViewPool = mockk(relaxed = true)
        service.resumeScheduler = mockk(relaxed = true)
        service.encryptedFileTree = mockk(relaxed = true)
        service.mockLocationProviderCleaner = cleaner

        service.onDestroy()

        verify(exactly = 1) { cleaner.clearOrphanedProvider() }
    }
}
