package com.fauxx

import com.fauxx.data.crawllist.CrawlListManager
import com.fauxx.data.crawllist.DomainBlocklist
import com.fauxx.data.db.ActionLogDao
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.model.ActionType
import com.fauxx.data.location.CityCoord
import com.fauxx.data.location.CityDatabase
import com.fauxx.data.model.IntensityLevel
import com.fauxx.data.model.PoisonProfile
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.data.querybank.QueryBankManager
import com.fauxx.engine.EngineState
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
import com.fauxx.support.FakeClock
import com.fauxx.targeting.TargetingEngine
import com.fauxx.util.Clock
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Per-hour rate-limit test for [PoisonEngine] `runLoop`.
 *
 * The engine tracks successful actions in a sliding [java.util.concurrent.ConcurrentLinkedQueue]
 * window of [RATE_LIMIT_WINDOW_MS] (1h). Once the number of in-window timestamps reaches the
 * profile's `intensity.actionsPerHour` cap, the loop must flip to
 * [EngineState.PAUSED_RATE_LIMIT] and stop dispatching until enough of the window slides out.
 *
 * Only the Dns module is enabled, so it is the sole selectable module and the action count is
 * unambiguous. LOW intensity gives a cap of 12 actions/hour, and the constraint window covers
 * noon (allowed 7-23, mobileIntensity=LOW, batteryThreshold=0) so no constraint pause masks the
 * rate-limit pause. The scheduler returns a fixed 1s inter-action delay, so virtual time
 * advanced ~1s at a time dispatches roughly one action per iteration until the cap is hit.
 *
 * The Dns module's onAction MUST return a success=true entry — only successful actions append
 * a timestamp to the window (PoisonEngine.runLoop), so a failing module would never trip the
 * limiter. The engine is given a fixed [Random] (nextBits()/nextFloat() = 0) so module
 * selection is deterministic (the single enabled module). Time control mirrors
 * PoisonEngineCircuitBreakerTest; extracting a shared EngineTestHarness is a follow-up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PoisonEngineRateLimitTest {

    // LOW intensity (cap = 12 actions/hour), no wifi/battery/quiet-hours gating; only Dns enabled.
    private val baseProfile = PoisonProfile(
        enabled = true,
        intensity = IntensityLevel.LOW,
        mobileIntensity = IntensityLevel.LOW,
        batteryThreshold = 0,
        allowedHoursStart = 7,
        allowedHoursEnd = 23,
        searchPoisonEnabled = false,
        adPollutionEnabled = false,
        locationSpoofEnabled = false,
        fingerprintEnabled = false,
        cookieSaturationEnabled = false,
        appSignalEnabled = false,
        dnsNoiseEnabled = true,
        layer1Enabled = false,
        layer2Enabled = false,
        layer3Enabled = false
    )

    private lateinit var engine: PoisonEngine
    private lateinit var dnsModule: DnsNoiseModule
    private lateinit var actionLogDao: ActionLogDao

    // nextFloat()=0 -> zero backoff jitter. nextBits()=0 keeps availableModules.random(this)
    // deterministic (the single enabled module).
    private val engineRandom = object : kotlin.random.Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextFloat(): Float = 0f
    }

    private val okEntry = ActionLogEntity(
        actionType = ActionType.DNS_LOOKUP,
        category = CategoryPool.GAMING,
        detail = "ok",
        success = true
    )

    @After
    fun tearDown() {
        if (::engine.isInitialized) engine.destroy()
    }

    @Test
    fun `engine pauses with rate-limit state once per-hour cap is reached`() = runTest {
        engine = buildEngine(FakeClock(noonEpochMs()), baseProfile, StandardTestDispatcher(testScheduler))
        // MANDATORY success — only successful actions append a window timestamp.
        coEvery { dnsModule.onAction(any()) } returns okEntry

        engine.start()
        // Scheduler returns 1s per action, so ~1 success accrues per virtual second. With a
        // LOW cap of 12, ~20s of virtual+fake time pushes the window past the cap and the loop
        // flips to PAUSED_RATE_LIMIT (then idles in RATE_LIMIT_PAUSE_MS chunks).
        advanceVirtualTime(testScheduler, by = 20_000)

        assertEquals(EngineState.PAUSED_RATE_LIMIT, engine.engineState.value)
        // Dispatch is bounded near the cap (not the ~20 iterations of virtual time): the loop
        // stops calling onAction once the window is full.
        coVerify(atMost = 13) { dnsModule.onAction(any()) }

        engine.stop()
    }

    @Test
    fun `window slides and dispatch resumes after the rate-limit hour elapses`() = runTest {
        val fakeClock = FakeClock(noonEpochMs())
        engine = buildEngine(fakeClock, baseProfile, StandardTestDispatcher(testScheduler))
        var calls = 0
        // MANDATORY success on every call, with a counter so we can prove dispatch resumes
        // after the window slides (recentActionTimestamps has no test accessor).
        coEvery { dnsModule.onAction(any()) } answers {
            calls++
            okEntry
        }

        engine.start()
        // Fill the window to the cap and confirm the limiter engaged.
        advanceVirtualTime(testScheduler, by = 20_000)
        assertEquals(EngineState.PAUSED_RATE_LIMIT, engine.engineState.value)
        val callsAtCap = calls

        // Slide the window: jump the wall clock past RATE_LIMIT_WINDOW_MS (1h) in one step
        // (rather than stepping ~3660 1s iterations, which is both slow and memory-heavy on
        // the shared fork), then advance virtual time enough for the 15s rate-limit pause to
        // expire and the loop to re-evaluate. The prune empties the now-stale queue and
        // dispatch resumes. We assert resumption (calls increased) rather than the exact
        // end-state, because with the window empty the engine immediately starts re-filling
        // and the instantaneous state at any later point is timing-sensitive.
        fakeClock.nowMs += 61L * 60 * 1000
        advanceVirtualTime(testScheduler, by = 40_000)

        // More actions fired after the slide than at the moment the cap was first hit: this
        // is the load-bearing proof that the window slid and dispatch resumed.
        assertTrue(
            "expected dispatch to resume after the window slid (callsAtCap=$callsAtCap, calls=$calls)",
            calls > callsAtCap
        )
        // The module was dispatched at least once more than the cap (post-slide successes).
        coVerify(atLeast = 13) { dnsModule.onAction(any()) }

        engine.stop()
    }

    private suspend fun advanceVirtualTime(
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        by: Long
    ) {
        val step = 1_000L
        var remaining = by
        while (remaining > 0) {
            val chunk = minOf(step, remaining)
            (clock as? FakeClock)?.let { it.nowMs += chunk }
            scheduler.advanceTimeBy(chunk)
            scheduler.runCurrent()
            remaining -= chunk
        }
        delay(1)
        scheduler.runCurrent()
    }

    private lateinit var clock: Clock

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
        fakeClock: FakeClock,
        profile: PoisonProfile,
        loopDispatcher: CoroutineDispatcher
    ): PoisonEngine {
        clock = fakeClock
        val profileRepo: PoisonProfileRepository = mockk {
            every { getProfile() } returns profile
        }
        val actionDispatcher: ActionDispatcher = mockk {
            coEvery { selectCategory() } returns CategoryPool.GAMING
        }
        val scheduler: PoissonScheduler = mockk {
            every { nextDelayMs(any(), any(), any(), any(), any()) } returns 1000L
        }
        actionLogDao = mockk(relaxed = true)
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

        // Only Dns is enabled, so it is the sole selectable module. Its onAction is
        // configured per-test (always succeeds — required to append window timestamps).
        dnsModule = mockk(relaxed = true) {
            every { isEnabled() } returns true
            coEvery { onAction(any()) } returns okEntry
        }
        val searchModule: SearchPoisonModule = mockk(relaxed = true) { every { isEnabled() } returns false }
        val cookieModule: CookieSaturationModule = mockk(relaxed = true) { every { isEnabled() } returns false }
        val adModule: Module = mockk(relaxed = true) { every { isEnabled() } returns false }
        val appSignalModule: AppSignalModule = mockk(relaxed = true) { every { isEnabled() } returns false }
        val fingerprintModule: FingerprintModule = mockk(relaxed = true) { every { isEnabled() } returns false }
        val locationModule: Module = mockk(relaxed = true) { every { isEnabled() } returns false }

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
            context, profileRepo, targetingEngine, actionDispatcher, scheduler, actionLogDao,
            blocklist, queryBankManager, crawlListManager, cityDatabase,
            searchModule,
            adModule = adModule,
            locationModule = locationModule,
            fingerprintModule = fingerprintModule,
            cookieModule = cookieModule,
            appSignalModule = appSignalModule,
            dnsModule = dnsModule,
            clock = fakeClock,
            loopDispatcher = loopDispatcher,
            random = engineRandom
        )
    }
}
