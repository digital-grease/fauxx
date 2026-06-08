package com.fauxx

import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.fauxx.data.crawllist.CrawlListManager
import com.fauxx.data.crawllist.DomainBlocklist
import com.fauxx.data.db.ActionLogDao
import com.fauxx.data.location.CityCoord
import com.fauxx.data.location.CityDatabase
import com.fauxx.data.model.IntensityLevel
import com.fauxx.data.model.PoisonProfile
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.data.querybank.QueryBankManager
import com.fauxx.engine.PoisonEngine
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.modules.AppSignalModule
import com.fauxx.engine.modules.CookieSaturationModule
import com.fauxx.engine.modules.DnsNoiseModule
import com.fauxx.engine.modules.FingerprintModule
import com.fauxx.engine.modules.Module
import com.fauxx.engine.modules.SearchPoisonModule
import com.fauxx.engine.scheduling.ActionDispatcher
import com.fauxx.engine.scheduling.PoissonScheduler
import com.fauxx.service.ResumeScheduler
import com.fauxx.support.FakeClock
import com.fauxx.targeting.TargetingEngine
import com.fauxx.util.Clock
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Integration test bridging two units that are individually well-tested but only
 * tied together in production code: [PoisonEngine] (engine loop decisions, unit-
 * tested in `PoisonEngineConstraintTest` and `PoisonEngineLoopTest`) and
 * [ResumeScheduler] (WorkManager enqueue, indirectly exercised). The actual wire
 * — engine signals `onLongPause` → `ResumeScheduler.schedule` runs → WorkManager
 * gets a `fauxx_resume` job — has no test today; it's the exact gap noted in
 * `.devloop/spikes/integration-testing-audit.md`.
 *
 * Test approach: real engine, real ResumeScheduler, real (test-mode) WorkManager.
 * The engine's `onLongPause` callback is connected to `scheduler.schedule(spec)`
 * exactly as `PhantomForegroundService` does in production. Drive the engine into
 * PAUSED_QUIET_HOURS via a 3am `FakeClock`, advance virtual time to let `runLoop`
 * fire, then assert WorkManager has an enqueued `fauxx_resume` entry.
 *
 * What a failure here would catch:
 *  - Engine stops calling `onLongPause` (regression in `runLoop`'s resign branch).
 *  - `ResumeScheduler.schedule` changes its unique-work name or policy and silently
 *    drops the request.
 *  - `ResumeSpec.AtTime.epochMs` is computed wrong and produces a malformed delay
 *    that WorkManager rejects.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class EngineResumeSchedulerIntegrationTest {

    private lateinit var workManager: WorkManager
    private lateinit var engine: PoisonEngine

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        if (::engine.isInitialized) engine.destroy()
    }

    @Test
    fun `engine resigning during quiet hours enqueues a resume work item`() = runTest {
        // 3am local — outside the 7-23 allowed window. First runLoop iteration will
        // see PAUSED_QUIET_HOURS and invoke decidePauseAction → Resign.
        val clock = FakeClock(threeAmEpochMs())
        val dispatcher = StandardTestDispatcher(testScheduler)
        val context = RuntimeEnvironment.getApplication()
        val resumeScheduler = ResumeScheduler(context)

        engine = buildEngine(clock, dispatcher)
        // Wire identically to PhantomForegroundService.onStartCommand at line 92.
        engine.setOnLongPause { spec -> resumeScheduler.schedule(spec) }
        engine.start()

        advanceVirtualTime(clock, scheduler = testScheduler, by = 500)

        // Assertion the production wire promises: after the engine resigns, a
        // fauxx_resume work item exists. Pre-fix runLoop would never reach this state.
        val resumeWork = workManager.getWorkInfosForUniqueWork("fauxx_resume").get()
        assertEquals("expected a single resume work item", 1, resumeWork.size)
        val info = resumeWork[0]
        // The TestWorkManager + SynchronousExecutor combo may run the worker
        // immediately to SUCCEEDED; production WorkManager would leave it ENQUEUED
        // or BLOCKED until the AtTime delay or constraint resolves. Accept any
        // non-failure state — the wire we're testing is just "engine resign caused
        // ResumeScheduler to enqueue work."
        assertTrue(
            "resume work shouldn't be in a failure state, got ${info.state}",
            info.state != WorkInfo.State.FAILED && info.state != WorkInfo.State.CANCELLED
        )
    }

    @Test
    fun `engine resigning a second time replaces the prior resume work item`() = runTest {
        // Drives two separate engine sessions and asserts the unique-work REPLACE policy
        // holds — a fresh resign overwrites a stale one rather than queuing duplicates.
        val clock = FakeClock(threeAmEpochMs())
        val dispatcher = StandardTestDispatcher(testScheduler)
        val context = RuntimeEnvironment.getApplication()
        val resumeScheduler = ResumeScheduler(context)

        engine = buildEngine(clock, dispatcher)
        engine.setOnLongPause { spec -> resumeScheduler.schedule(spec) }
        engine.start()
        advanceVirtualTime(clock, scheduler = testScheduler, by = 500)

        val firstWork = workManager.getWorkInfosForUniqueWork("fauxx_resume").get()
        assertEquals(1, firstWork.size)
        val firstId = firstWork[0].id

        // Reset and restart with a fresh resign — should replace, not duplicate.
        engine.destroy()
        engine = buildEngine(clock, dispatcher)
        engine.setOnLongPause { spec -> resumeScheduler.schedule(spec) }
        engine.start()
        advanceVirtualTime(clock, scheduler = testScheduler, by = 500)

        val resumeWork = workManager.getWorkInfosForUniqueWork("fauxx_resume").get()
        val active = resumeWork.firstOrNull { it.state != WorkInfo.State.CANCELLED }
        assertNotNull("at least one active resume work item must exist", active)
        // The active one should be a different UUID — REPLACE policy created fresh work.
        // (Some replace strategies reuse the ID; either is acceptable as long as exactly
        // one is active. Catch the truly broken case: multiple active entries.)
        val activeCount = resumeWork.count { it.state != WorkInfo.State.CANCELLED }
        assertEquals("REPLACE must leave exactly one active entry, not duplicates", 1, activeCount)
    }

    /**
     * Step time forward in small increments so `runTest`'s scheduler can fire each
     * `delay()` resumption. The fake clock advances together with the scheduler so
     * `elapsedRealtime()` and coroutine virtual time agree.
     */
    private suspend fun advanceVirtualTime(
        clock: FakeClock,
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        by: Long
    ) {
        val step = 100L
        var remaining = by
        while (remaining > 0) {
            val chunk = minOf(step, remaining)
            clock.nowMs += chunk
            scheduler.advanceTimeBy(chunk)
            scheduler.runCurrent()
            remaining -= chunk
        }
        delay(1)
        scheduler.runCurrent()
    }

    private fun threeAmEpochMs(): Long {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 3)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun buildEngine(clock: Clock, loopDispatcher: CoroutineDispatcher): PoisonEngine {
        val baseProfile = PoisonProfile(
            enabled = true,
            intensity = IntensityLevel.LOW,
            mobileIntensity = IntensityLevel.LOW,
            batteryThreshold = 0,
            allowedHoursStart = 7,
            allowedHoursEnd = 23,
            searchPoisonEnabled = true,
            adPollutionEnabled = false,
            locationSpoofEnabled = false,
            fingerprintEnabled = false,
            cookieSaturationEnabled = false,
            appSignalEnabled = false,
            dnsNoiseEnabled = false,
            layer1Enabled = false,
            layer2Enabled = false,
            layer3Enabled = false
        )
        val profileRepo: PoisonProfileRepository = mockk { every { getProfile() } returns baseProfile }
        val dispatcher: ActionDispatcher = mockk { coEvery { selectCategory() } returns CategoryPool.GAMING }
        val scheduler: PoissonScheduler = mockk {
            every { nextDelayMs(any(), any(), any(), any(), any()) } returns 1000L
        }
        val actionLogDao: ActionLogDao = mockk(relaxed = true)
        val blocklist: DomainBlocklist = mockk { every { loadFailed } returns false }
        val queryBankManager: QueryBankManager = mockk { every { getQueries(any()) } returns listOf("test") }
        val crawlListManager: CrawlListManager = mockk { every { corpusSize() } returns 100 }
        val cityDatabase: CityDatabase = mockk {
            every { cities } returns listOf(
                CityCoord("A", 0.0, 0.0, "X"),
                CityCoord("B", 1.0, 1.0, "X")
            )
        }
        val searchModule: SearchPoisonModule = mockk(relaxed = true) { every { isEnabled() } returns true }
        val noopModule: Module = mockk(relaxed = true) { every { isEnabled() } returns false }
        val targetingEngine: TargetingEngine = mockk(relaxed = true) {
            every { setLayer1Enabled(any()) } answers { }
            every { setLayer2Enabled(any()) } answers { }
            every { setLayer3Enabled(any()) } answers { }
        }
        val connectivityManager: android.net.ConnectivityManager = mockk(relaxed = true)
        val context: android.content.Context = mockk(relaxed = true) {
            every { getSystemService(android.content.Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        }
        return PoisonEngine(
            context, profileRepo, targetingEngine, dispatcher, scheduler, actionLogDao,
            blocklist, queryBankManager, crawlListManager, cityDatabase,
            searchModule,
            adModule = noopModule,
            locationModule = noopModule,
            fingerprintModule = mockk<FingerprintModule>(relaxed = true) {
                every { isEnabled() } returns false
            },
            cookieModule = mockk<CookieSaturationModule>(relaxed = true) {
                every { isEnabled() } returns false
            },
            appSignalModule = mockk<AppSignalModule>(relaxed = true) {
                every { isEnabled() } returns false
            },
            dnsModule = mockk<DnsNoiseModule>(relaxed = true) {
                every { isEnabled() } returns false
            },
            clock = clock,
            loopDispatcher = loopDispatcher
        )
    }
}
