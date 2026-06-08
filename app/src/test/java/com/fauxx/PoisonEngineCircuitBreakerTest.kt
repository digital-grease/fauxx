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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Circuit-breaker resilience test for [PoisonEngine] `runLoop`.
 *
 * A module that throws on every action must be tripped after
 * MAX_CONSECUTIVE_FAILURES (5) consecutive failures and then left alone for the
 * backoff window, so a persistently-failing module cannot spin the loop. After the
 * backoff expires and the module starts succeeding, the failure counter resets and
 * dispatch resumes.
 *
 * Only the Dns module is enabled, so it is the sole selectable module and the
 * failure/backoff accounting is unambiguous. The engine is given a fixed [Random]
 * whose nextFloat() is 0, so the backoff jitter is 0 and the backoff is exactly
 * INITIAL_BACKOFF_MS (30s) — making the recovery timing deterministic. Time control
 * mirrors PoisonEngineLoopTest/PoisonEngineFailClosedTest; extracting a shared
 * EngineTestHarness is a follow-up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PoisonEngineCircuitBreakerTest {

    // LOW intensity, no wifi/battery/quiet-hours gating; only Dns enabled.
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

    // nextFloat()=0 -> zero backoff jitter (deterministic 30s backoff). nextBits()=0 keeps
    // availableModules.random(this) deterministic (the single enabled module).
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
    fun `module trips after five consecutive failures and stops being dispatched`() = runTest {
        engine = buildEngine(FakeClock(noonEpochMs()), baseProfile, StandardTestDispatcher(testScheduler))
        coEvery { dnsModule.onAction(any()) } throws RuntimeException("always fails")

        engine.start()
        // Failures fire ~5s apart (FAILURE_RETRY_DELAY_MS); the 5th trips the breaker at
        // ~20s, which stays open for 30s (no jitter). Advancing to 40s lands inside the
        // open window: exactly five dispatches, then silence.
        advanceVirtualTime(testScheduler, by = 40_000)

        coVerify(exactly = 5) { dnsModule.onAction(any()) }

        engine.stop()
    }

    @Test
    fun `failure counter resets and dispatch resumes after backoff when module recovers`() = runTest {
        engine = buildEngine(FakeClock(noonEpochMs()), baseProfile, StandardTestDispatcher(testScheduler))
        var calls = 0
        // Throw for the first five calls (trip the breaker), then succeed.
        coEvery { dnsModule.onAction(any()) } answers {
            calls++
            if (calls <= 5) throw RuntimeException("transient") else okEntry
        }

        engine.start()
        // Past the 30s backoff (opens ~20s, expires ~50s) and the 60s constraint-recheck
        // that follows the trip, so the module is re-selected, succeeds, and the counter
        // resets. 100s comfortably covers the recheck at ~85s plus a few successes.
        advanceVirtualTime(testScheduler, by = 100_000)

        // Fired again after the trip (6th+ call) — recovery happened.
        coVerify(atLeast = 6) { dnsModule.onAction(any()) }
        // A post-recovery success was written to the log, proving the success path ran
        // (and with it the failure-counter reset).
        coVerify(atLeast = 1) { actionLogDao.insert(any()) }

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
        // configured per-test (throw vs throw-then-succeed).
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
