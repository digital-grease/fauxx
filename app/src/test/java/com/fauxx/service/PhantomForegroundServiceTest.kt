package com.fauxx.service

import androidx.work.NetworkType
import com.fauxx.engine.PoisonEngine
import com.fauxx.engine.modules.MockLocationProviderCleaner
import com.fauxx.engine.webview.PhantomWebViewPool
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
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
 *      Android 14+ dataSync FGS runtime budget.
 *
 * Both halves of #1 and #2 are needed: scheduling without stopping leaves the FGS up
 * (the original bug); stopping without scheduling means the user gets no notification
 * when conditions are met. This test covers the seam between the engine and the
 * platform that no unit test of [PoisonEngine] alone could exercise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PhantomForegroundServiceTest {

    @Test
    fun `onEngineResigned schedules the resume spec and stops the service`() {
        val service = Robolectric.buildService(PhantomForegroundService::class.java).get()
        val resumeScheduler: ResumeScheduler = mockk(relaxed = true)
        service.poisonEngine = mockk(relaxed = true)
        service.webViewPool = mockk(relaxed = true)
        service.resumeScheduler = resumeScheduler

        val spec = ResumeSpec.WhenConstraintMet(network = NetworkType.UNMETERED)
        service.onEngineResigned(spec)

        verify(exactly = 1) { resumeScheduler.schedule(spec) }
        val shadow = Shadows.shadowOf(service)
        assertTrue("service must have called stopSelf", shadow.isStoppedBySelf)
    }

    @Test
    fun `ACTION_START wires the engine callback to onEngineResigned and cancels stale resume`() {
        val service = Robolectric.buildService(PhantomForegroundService::class.java).get()
        val engine: PoisonEngine = mockk(relaxed = true)
        val resumeScheduler: ResumeScheduler = mockk(relaxed = true) {
            every { cancel() } just Runs
            every { schedule(any()) } just Runs
        }
        service.poisonEngine = engine
        service.webViewPool = mockk<PhantomWebViewPool>(relaxed = true)
        service.resumeScheduler = resumeScheduler

        // Capture the callback the service registers with the engine.
        val captured = slot<(ResumeSpec) -> Unit>()
        every { engine.setOnLongPause(capture(captured)) } just Runs

        val startIntent = PhantomForegroundService.startIntent(service)
        service.onStartCommand(startIntent, 0, 1)

        // Cancellation of stale resume happens before startForeground (so a fresh
        // launch never leaves a redundant notification queued for later).
        verify(exactly = 1) { resumeScheduler.cancel() }
        verify(exactly = 1) { engine.setOnLongPause(any()) }
        verify(exactly = 1) { engine.start() }

        // Now exercise the captured callback — this is the actual wire being tested:
        // does the engine's resignation signal reach the service's resign handler?
        assertNotNull("engine callback must have been captured", captured.captured)
        val spec = ResumeSpec.AtTime(System.currentTimeMillis() + 60_000)
        captured.captured(spec)

        verify(exactly = 1) { resumeScheduler.schedule(spec) }
        val shadow = Shadows.shadowOf(service)
        assertTrue("invoking the engine callback must stop the service", shadow.isStoppedBySelf)
    }

    @Test
    fun `ACTION_STOP path runs without invoking the resume scheduler`() {
        val service = Robolectric.buildService(PhantomForegroundService::class.java).get()
        val resumeScheduler: ResumeScheduler = mockk(relaxed = true)
        service.poisonEngine = mockk(relaxed = true)
        service.webViewPool = mockk(relaxed = true)
        service.resumeScheduler = resumeScheduler

        // Start first so notificationJob is non-null and stopForeground is meaningful.
        service.onStartCommand(PhantomForegroundService.startIntent(service), 0, 1)
        // Then issue the user-initiated stop.
        val stopIntent = PhantomForegroundService.stopIntent(service)
        service.onStartCommand(stopIntent, 0, 2)

        // A user-initiated stop must not enqueue a tap-to-resume notification — the
        // user explicitly turned the engine off; nagging them later would be wrong.
        verify(exactly = 0) { resumeScheduler.schedule(any()) }
        // It's fine for cancel() to be called (ACTION_START calls it; the test
        // double-counts that). Just assert it isn't a schedule.
        assertEquals(Unit, Unit) // explicit pass after the negative verify
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
        service.mockLocationProviderCleaner = cleaner

        service.onDestroy()

        verify(exactly = 1) { cleaner.clearOrphanedProvider() }
    }
}
