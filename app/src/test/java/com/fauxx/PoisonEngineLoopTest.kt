package com.fauxx

import com.fauxx.data.crawllist.CrawlListManager
import com.fauxx.data.crawllist.DomainBlocklist
import com.fauxx.data.db.ActionLogDao
import com.fauxx.data.location.CityCoord
import com.fauxx.data.location.CityDatabase
import com.fauxx.data.model.IntensityLevel
import com.fauxx.data.model.PoisonProfile
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.data.querybank.QueryBankManager
import com.fauxx.engine.FgsBudgetTracker
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
import com.fauxx.service.ResumeSpec
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration test for [PoisonEngine.start] / `runLoop` under virtual time.
 *
 * Asserts the *invariant the original FGS-timeout crash violated*:
 * the engine must invoke [PoisonEngine.setOnLongPause] (resigning the foreground
 * service) within a bounded number of simulated milliseconds when stuck in a
 * long-duration constraint pause. The pre-fix `runLoop` would spin `delay()`
 * forever in this state, never invoking the callback — that's the wire this
 * test pins down.
 *
 * Time control: a fake [Clock] advances alongside the [runTest] scheduler so
 * `clock.elapsedRealtime()` and coroutine-delay-driven virtual time stay aligned.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PoisonEngineLoopTest {

    /**
     * Clock backed by a mutable field so test code can advance it in sync with the
     * `runTest` scheduler. Both wall-clock and elapsed are slaved to the same value
     * — fine for these tests since they only care about relative deltas.
     */
    private class FakeClock(var nowMs: Long) : Clock {
        override fun currentTimeMillis(): Long = nowMs
        override fun elapsedRealtime(): Long = nowMs
    }

    private val baseProfile = PoisonProfile(
        enabled = true,
        intensity = IntensityLevel.LOW,
        wifiOnly = false,
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

    private lateinit var engine: PoisonEngine

    @After
    fun tearDown() {
        if (::engine.isInitialized) engine.destroy()
    }

    @Test
    fun `runLoop resigns when engine starts in quiet hours`() = runTest {
        // 3:00 AM local — outside the 7-23 allowed window, so the very first
        // checkConstraints() call returns PAUSED_QUIET_HOURS.
        val clock = FakeClock(threeAmEpochMs())
        val dispatcher = StandardTestDispatcher(testScheduler)
        engine = buildEngine(clock, profile = baseProfile, loopDispatcher = dispatcher)

        var resignedWith: ResumeSpec? = null
        engine.setOnLongPause { spec -> resignedWith = spec }
        engine.start()

        // Pump the scheduler so the launched coroutine runs.
        advanceVirtualTime(clock, scheduler = testScheduler, by = 100)

        assertNotNull("engine should have resigned during quiet hours", resignedWith)
        assertTrue(
            "quiet-hours resignation must use AtTime spec",
            resignedWith is ResumeSpec.AtTime
        )
    }

    @Test
    fun `runLoop continues looping during a brief wifi pause and only resigns after 30 minutes`() = runTest {
        val clock = FakeClock(noonEpochMs())
        val dispatcher = StandardTestDispatcher(testScheduler)
        val wifiProfile = baseProfile.copy(wifiOnly = true)
        engine = buildEngine(clock, profile = wifiProfile, loopDispatcher = dispatcher)

        var resignedWith: ResumeSpec? = null
        engine.setOnLongPause { spec -> resignedWith = spec }
        engine.start()

        // 10 minutes of paused looping — well under the 30-min threshold.
        advanceVirtualTime(clock, scheduler = testScheduler, by = 10 * 60 * 1000)
        assertNull("must not resign before 30-min wifi-pause threshold", resignedWith)

        // Cross the 30-min threshold.
        advanceVirtualTime(clock, scheduler = testScheduler, by = 25 * 60 * 1000)

        assertNotNull(
            "engine must resign after sustained wifi pause exceeds threshold",
            resignedWith
        )
        assertTrue(
            "wifi-pause resignation must use a network-constraint spec",
            resignedWith is ResumeSpec.WhenConstraintMet
        )
        val whenConstraint = resignedWith as ResumeSpec.WhenConstraintMet
        assertEquals(androidx.work.NetworkType.UNMETERED, whenConstraint.network)
    }

    @Test
    fun `runLoop resigns at FGS 5h hard limit even when active`() = runTest {
        val clock = FakeClock(noonEpochMs())
        val dispatcher = StandardTestDispatcher(testScheduler)
        val alwaysAllowed = baseProfile.copy(allowedHoursStart = 0, allowedHoursEnd = 24)
        engine = buildEngine(clock, profile = alwaysAllowed, moduleFails = true, loopDispatcher = dispatcher)

        var resignedWith: ResumeSpec? = null
        engine.setOnLongPause { spec -> resignedWith = spec }
        engine.start()

        advanceVirtualTime(clock, scheduler = testScheduler, by = 5L * 60 * 60 * 1000 + 60_000)

        assertNotNull(
            "engine must resign at the 5h FGS hard limit regardless of state",
            resignedWith
        )
    }

    /**
     * Step time forward in small increments so `runTest`'s scheduler can fire
     * each `delay()` resumption. The fake clock advances together with the
     * scheduler so elapsedRealtime() and the coroutine virtual time agree.
     */
    private suspend fun advanceVirtualTime(
        clock: FakeClock,
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        by: Long
    ) {
        // 1s increments — small enough that the engine's smallest delay (~3s
        // CONSTRAINT_CHECK_MIN_MS) still fires reliably; coarse enough that
        // crossing a 30-min boundary doesn't burn many iterations.
        val step = 1_000L
        var remaining = by
        while (remaining > 0) {
            val chunk = minOf(step, remaining)
            clock.nowMs += chunk
            scheduler.advanceTimeBy(chunk)
            scheduler.runCurrent()
            remaining -= chunk
        }
        // Tiny yield so any pending continuations after the last advance get a chance.
        delay(1)
        scheduler.runCurrent()
    }

    /**
     * Today's local 03:00 in epoch ms — used to start tests inside quiet hours.
     */
    private fun threeAmEpochMs(): Long {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 3)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    /**
     * Today's local 12:00 in epoch ms — middle of the default 7-23 active window.
     */
    private fun noonEpochMs(): Long {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 12)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun buildEngine(
        clock: Clock,
        profile: PoisonProfile,
        moduleFails: Boolean = false,
        loopDispatcher: CoroutineDispatcher
    ): PoisonEngine {
        val profileRepo: PoisonProfileRepository = mockk {
            every { getProfile() } returns profile
        }
        val dispatcher: ActionDispatcher = mockk {
            coEvery { selectCategory() } returns CategoryPool.GAMING
        }
        val scheduler: PoissonScheduler = mockk {
            every { nextDelayMs(any(), any(), any(), any(), any()) } returns 1000L
        }
        val actionLogDao: ActionLogDao = mockk(relaxed = true)
        val blocklist: DomainBlocklist = mockk {
            every { loadFailed } returns false
        }
        val queryBankManager: QueryBankManager = mockk {
            every { getQueries(any()) } returns listOf("test")
        }
        val crawlListManager: CrawlListManager = mockk {
            every { corpusSize() } returns 100
        }
        val cityDatabase: CityDatabase = mockk {
            every { cities } returns listOf(
                CityCoord("A", 0.0, 0.0, "X"),
                CityCoord("B", 1.0, 1.0, "X")
            )
        }
        val searchModule: SearchPoisonModule = mockk(relaxed = true) {
            every { isEnabled() } returns true
            if (moduleFails) {
                coEvery { onAction(any()) } throws RuntimeException("forced failure")
            }
        }
        val noopModule: Module = mockk(relaxed = true) {
            every { isEnabled() } returns false
        }
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
            budgetTracker = mockk<FgsBudgetTracker>(relaxed = true) {
                every { remainingBudgetMs() } returns FgsBudgetTracker.BUDGET_MS
                every { nextWindowResetMs() } returns clock.currentTimeMillis() + 60 * 60 * 1000L
            },
            loopDispatcher = loopDispatcher
        )
    }
}
