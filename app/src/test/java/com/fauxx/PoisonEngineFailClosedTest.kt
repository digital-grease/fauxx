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
 * Fail-closed safety test for [PoisonEngine.start] / `runLoop`.
 *
 * When [DomainBlocklist.loadFailed] is true, the four URL-loading modules
 * (Search / Cookie / Ad / AppSignal) must be permanently circuit-broken
 * (`circuitBreakerUntil = Long.MAX_VALUE`) before the loop ever dispatches, so
 * they never load arbitrary URLs without a working safety blocklist. A non-URL
 * module (Dns) must keep firing, proving the engine is still live (not a global
 * stop), and [PoisonEngine.healthWarnings] must surface the load-failure warning.
 *
 * Time control mirrors PoisonEngineLoopTest: a [FakeClock] advances in lockstep
 * with the runTest scheduler so `elapsedRealtime()` and coroutine virtual time
 * agree. State is read directly off StateFlow `.value` (this module has no
 * Turbine dependency).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PoisonEngineFailClosedTest {

    // Active window 7-23, no wifi/battery/quiet-hours gating, so the loop reaches
    // module dispatch. Only Search (a URL module) and Dns (non-URL) are enabled;
    // Cookie/Ad/AppSignal stay off in the profile but are still listed among the
    // modules that start() must circuit-break on blocklist failure.
    private val baseProfile = PoisonProfile(
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
        dnsNoiseEnabled = true,
        layer1Enabled = false,
        layer2Enabled = false,
        layer3Enabled = false
    )

    private lateinit var engine: PoisonEngine

    // Module handles captured by buildEngine so the test body can verify dispatch.
    private lateinit var searchModule: SearchPoisonModule
    private lateinit var cookieModule: CookieSaturationModule
    private lateinit var adModule: Module
    private lateinit var appSignalModule: AppSignalModule
    private lateinit var dnsModule: DnsNoiseModule
    private lateinit var fingerprintModule: FingerprintModule

    @After
    fun tearDown() {
        if (::engine.isInitialized) engine.destroy()
    }

    @Test
    fun `blocklist load failure circuit-breaks all URL modules while dns keeps firing`() = runTest {
        val clock = FakeClock(noonEpochMs())
        val dispatcher = StandardTestDispatcher(testScheduler)
        engine = buildEngine(
            clock = clock,
            profile = baseProfile,
            blocklistLoadFailed = true,
            loopDispatcher = dispatcher
        )

        engine.start()

        // Pump well past several scheduled iterations (scheduler.nextDelayMs is
        // stubbed at 1s) so the loop selects a module many times over.
        advanceVirtualTime(clock, scheduler = testScheduler, by = 30_000)

        // Fail-closed: none of the four URL modules may ever have onAction dispatched.
        coVerify(exactly = 0) { searchModule.onAction(any()) }
        coVerify(exactly = 0) { cookieModule.onAction(any()) }
        coVerify(exactly = 0) { adModule.onAction(any()) }
        coVerify(exactly = 0) { appSignalModule.onAction(any()) }

        // Liveness: the non-URL Dns module must still fire, proving the engine is
        // running and only the URL modules were gated (not a global stop).
        coVerify(atLeast = 1) { dnsModule.onAction(any()) }

        // Fingerprint stays disabled in this profile, so it must not fire either.
        coVerify(exactly = 0) { fingerprintModule.onAction(any()) }

        // runLoop is infinite by design; cancel it so runTest's end-of-body drain
        // terminates instead of spinning the loop forever.
        engine.stop()
    }

    @Test
    fun `blocklist load failure surfaces the safety-blocklist health warning`() = runTest {
        val clock = FakeClock(noonEpochMs())
        val dispatcher = StandardTestDispatcher(testScheduler)
        engine = buildEngine(
            clock = clock,
            profile = baseProfile,
            blocklistLoadFailed = true,
            loopDispatcher = dispatcher
        )

        engine.start()
        // checkAssetHealth() runs once near the top of the launched start coroutine;
        // a short pump is enough to publish the warnings list.
        advanceVirtualTime(clock, scheduler = testScheduler, by = 1_000)

        val warnings = engine.healthWarnings.value
        assertTrue(
            "healthWarnings must contain the blocklist-load-failure warning, got: $warnings",
            warnings.any { it.contains("blocklist failed to load", ignoreCase = true) }
        )

        engine.stop()
    }

    /**
     * Step time forward in 1s increments so the runTest scheduler fires each
     * `delay()` resumption. The fake clock advances together with the scheduler so
     * elapsedRealtime() and coroutine virtual time agree. Copied from
     * PoisonEngineLoopTest; extracting a shared EngineTestHarness is a follow-up.
     */
    private suspend fun advanceVirtualTime(
        clock: FakeClock,
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        by: Long
    ) {
        val step = 1_000L
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

    /** Today's local 12:00 in epoch ms; middle of the default 7-23 active window. */
    private fun noonEpochMs(): Long {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 12)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    /**
     * Minimal engine builder parameterized for fail-closed assertions. Mirrors
     * PoisonEngineLoopTest.buildEngine; the only material differences are
     * [blocklistLoadFailed] and that the real (relaxed) module mocks are stored on
     * the enclosing test so onAction dispatch can be verified per-module.
     * Extracting a shared EngineTestHarness is a follow-up.
     */
    private fun buildEngine(
        clock: Clock,
        profile: PoisonProfile,
        blocklistLoadFailed: Boolean,
        loopDispatcher: CoroutineDispatcher
    ): PoisonEngine {
        val profileRepo: PoisonProfileRepository = mockk {
            every { getProfile() } returns profile
        }
        val actionDispatcher: ActionDispatcher = mockk {
            coEvery { selectCategory() } returns CategoryPool.GAMING
        }
        val scheduler: PoissonScheduler = mockk {
            every { nextDelayMs(any(), any(), any(), any(), any()) } returns 1000L
        }
        val actionLogDao: ActionLogDao = mockk(relaxed = true)
        val blocklist: DomainBlocklist = mockk {
            every { loadFailed } returns blocklistLoadFailed
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

        // A benign real log entry so the loop's write-ahead path and success
        // accounting run when a module's onAction is invoked. Real instance (not a
        // mock) to avoid mocking a final data class.
        val okEntry = ActionLogEntity(
            actionType = ActionType.DNS_LOOKUP,
            category = CategoryPool.GAMING,
            detail = "test",
            success = true
        )

        // URL module: enabled in the profile, but must be circuit-broken by start()
        // when the blocklist failed to load. relaxed so unstubbed members no-op.
        searchModule = mockk(relaxed = true) {
            every { isEnabled() } returns true
            coEvery { onAction(any()) } returns okEntry
        }
        // The other three URL modules are disabled in the profile, but start() still
        // lists them for permanent circuit-breaking. Mark enabled to prove the
        // breaker (not the enable flag) is what gates them.
        cookieModule = mockk(relaxed = true) {
            every { isEnabled() } returns true
            coEvery { onAction(any()) } returns okEntry
        }
        adModule = mockk<Module>(relaxed = true) {
            every { isEnabled() } returns true
            coEvery { onAction(any()) } returns okEntry
        }
        appSignalModule = mockk(relaxed = true) {
            every { isEnabled() } returns true
            coEvery { onAction(any()) } returns okEntry
        }

        // Non-URL module: never circuit-broken on blocklist failure, must keep firing.
        dnsModule = mockk(relaxed = true) {
            every { isEnabled() } returns true
            coEvery { onAction(any()) } returns okEntry
        }
        // Non-URL but disabled in this profile: must not fire.
        fingerprintModule = mockk(relaxed = true) {
            every { isEnabled() } returns false
        }
        val locationModule: Module = mockk(relaxed = true) {
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
            context, profileRepo, targetingEngine, actionDispatcher, scheduler, actionLogDao,
            blocklist, queryBankManager, crawlListManager, cityDatabase,
            searchModule,
            adModule = adModule,
            locationModule = locationModule,
            fingerprintModule = fingerprintModule,
            cookieModule = cookieModule,
            appSignalModule = appSignalModule,
            dnsModule = dnsModule,
            clock = clock,
            loopDispatcher = loopDispatcher
        )
    }
}
