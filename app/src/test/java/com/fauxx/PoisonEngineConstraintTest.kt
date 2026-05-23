package com.fauxx

import com.fauxx.data.crawllist.CrawlListManager
import com.fauxx.data.crawllist.DomainBlocklist
import com.fauxx.data.db.ActionLogDao
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.location.CityDatabase
import com.fauxx.data.location.CityCoord
import com.fauxx.data.model.IntensityLevel
import com.fauxx.data.model.PoisonProfile
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.data.querybank.QueryBankManager
import androidx.work.NetworkType
import com.fauxx.engine.EngineState
import com.fauxx.engine.PauseDecision
import com.fauxx.engine.PoisonEngine
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.service.ResumeSpec
import com.fauxx.engine.modules.AppSignalModule
import com.fauxx.engine.modules.CookieSaturationModule
import com.fauxx.engine.modules.DnsNoiseModule
import com.fauxx.engine.modules.FingerprintModule
import com.fauxx.engine.modules.Module
import com.fauxx.engine.modules.SearchPoisonModule
import com.fauxx.engine.scheduling.ActionDispatcher
import com.fauxx.engine.scheduling.PoissonScheduler
import com.fauxx.targeting.TargetingEngine
import com.fauxx.util.Clock
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PoisonEngineConstraintTest {

    private val profile = PoisonProfile(
        enabled = true,
        intensity = IntensityLevel.LOW,
        wifiOnly = false,
        batteryThreshold = 20,
        allowedHoursStart = 0,
        allowedHoursEnd = 24,
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

    private val profileRepo: PoisonProfileRepository = mockk {
        every { getProfile() } returns profile
    }
    private val dispatcher: ActionDispatcher = mockk {
        coEvery { selectCategory() } returns CategoryPool.GAMING
    }
    private val scheduler: PoissonScheduler = mockk {
        every { nextDelayMs(any(), any(), any(), any(), any()) } returns 100L
    }
    private val actionLogDao: ActionLogDao = mockk(relaxed = true)

    private val searchModule: SearchPoisonModule = mockk(relaxed = true)
    private val adModule: Module = mockk(relaxed = true)
    private val locationModule: Module = mockk(relaxed = true)
    private val fingerprintModule: FingerprintModule = mockk(relaxed = true)
    private val cookieModule: CookieSaturationModule = mockk(relaxed = true)
    private val appSignalModule: AppSignalModule = mockk(relaxed = true)
    private val dnsModule: DnsNoiseModule = mockk(relaxed = true)
    private val blocklist: DomainBlocklist = mockk {
        every { loadFailed } returns false
    }
    private val queryBankManager: QueryBankManager = mockk {
        every { getQueries(any()) } returns listOf("test query")
    }
    private val crawlListManager: CrawlListManager = mockk {
        every { corpusSize() } returns 100
    }
    private val cityDatabase: CityDatabase = mockk {
        every { cities } returns listOf(
            CityCoord("Test City", 0.0, 0.0, "TEST"),
            CityCoord("Test City 2", 1.0, 1.0, "TEST")
        )
    }

    private lateinit var engine: PoisonEngine

    @After
    fun tearDown() {
        if (::engine.isInitialized) {
            engine.destroy()
        }
    }

    @Test
    fun `engine calls destroy without crashing`() {
        engine = createEngine()
        engine.start()
        engine.destroy()
        // No exception = pass. Verifies destroy cancels scope cleanly.
    }

    @Test
    fun `isWithinAllowedHours accepts normal window`() {
        engine = createEngine()
        val p = profile.copy(allowedHoursStart = 7, allowedHoursEnd = 23)
        assertTrue(engine.isWithinAllowedHours(p, nowHour = 7))
        assertTrue(engine.isWithinAllowedHours(p, nowHour = 15))
        assertTrue(engine.isWithinAllowedHours(p, nowHour = 22))
        assertFalse(engine.isWithinAllowedHours(p, nowHour = 23))
        assertFalse(engine.isWithinAllowedHours(p, nowHour = 3))
        assertFalse(engine.isWithinAllowedHours(p, nowHour = 6))
    }

    @Test
    fun `isWithinAllowedHours handles midnight wrap`() {
        engine = createEngine()
        val p = profile.copy(allowedHoursStart = 22, allowedHoursEnd = 6)
        assertTrue(engine.isWithinAllowedHours(p, nowHour = 22))
        assertTrue(engine.isWithinAllowedHours(p, nowHour = 23))
        assertTrue(engine.isWithinAllowedHours(p, nowHour = 0))
        assertTrue(engine.isWithinAllowedHours(p, nowHour = 5))
        assertFalse(engine.isWithinAllowedHours(p, nowHour = 6))
        assertFalse(engine.isWithinAllowedHours(p, nowHour = 12))
        assertFalse(engine.isWithinAllowedHours(p, nowHour = 21))
    }

    @Test
    fun `isWithinAllowedHours treats equal start and end as always allowed`() {
        engine = createEngine()
        val p = profile.copy(allowedHoursStart = 12, allowedHoursEnd = 12)
        assertTrue(engine.isWithinAllowedHours(p, nowHour = 0))
        assertTrue(engine.isWithinAllowedHours(p, nowHour = 12))
        assertTrue(engine.isWithinAllowedHours(p, nowHour = 23))
    }

    // --- shouldPauseForBattery (issue #20: ignore threshold while charging) ---

    @Test
    fun `shouldPauseForBattery returns false when battery is at or above threshold`() {
        engine = createEngine()
        assertFalse(engine.shouldPauseForBattery(50, 20, isCharging = false, ignoreThresholdWhileCharging = false))
        assertFalse(engine.shouldPauseForBattery(20, 20, isCharging = false, ignoreThresholdWhileCharging = false))
    }

    @Test
    fun `shouldPauseForBattery returns true when below threshold and not charging`() {
        engine = createEngine()
        assertTrue(engine.shouldPauseForBattery(10, 20, isCharging = false, ignoreThresholdWhileCharging = true))
        assertTrue(engine.shouldPauseForBattery(10, 20, isCharging = false, ignoreThresholdWhileCharging = false))
    }

    @Test
    fun `shouldPauseForBattery bypasses threshold when charging and toggle on`() {
        engine = createEngine()
        // Issue #20: plugged in + opted in → keep running even below the user's threshold.
        assertFalse(engine.shouldPauseForBattery(5, 20, isCharging = true, ignoreThresholdWhileCharging = true))
        assertFalse(engine.shouldPauseForBattery(0, 100, isCharging = true, ignoreThresholdWhileCharging = true))
    }

    @Test
    fun `shouldPauseForBattery still pauses when charging but toggle off`() {
        engine = createEngine()
        // Default behavior preserved for users who haven't opted in.
        assertTrue(engine.shouldPauseForBattery(10, 20, isCharging = true, ignoreThresholdWhileCharging = false))
    }

    // --- decidePauseAction tests ---

    private val ms30Min = 30L * 60 * 1000
    private val nowMs = 1_700_000_000_000L // arbitrary fixed epoch

    @Test
    fun `decidePauseAction resigns immediately on quiet hours`() {
        engine = createEngine()
        val p = profile.copy(allowedHoursStart = 7, allowedHoursEnd = 23)
        val decision = engine.decidePauseAction(
            state = EngineState.PAUSED_QUIET_HOURS,
            currentProfile = p,
            pauseElapsedMs = 0,
            totalRuntimeMs = 1_000,
            nowMs = nowMs
        )
        assertTrue("Expected Resign, got $decision", decision is PauseDecision.Resign)
        assertTrue((decision as PauseDecision.Resign).resumeSpec is ResumeSpec.AtTime)
    }

    @Test
    fun `decidePauseAction continues looping during short wifi pause`() {
        engine = createEngine()
        val decision = engine.decidePauseAction(
            state = EngineState.PAUSED_WIFI,
            currentProfile = profile,
            pauseElapsedMs = 5 * 60 * 1000, // 5 min
            totalRuntimeMs = 10 * 60 * 1000, // 10 min total
            nowMs = nowMs
        )
        assertEquals(PauseDecision.Continue, decision)
    }

    @Test
    fun `decidePauseAction resigns on prolonged wifi pause`() {
        engine = createEngine()
        val decision = engine.decidePauseAction(
            state = EngineState.PAUSED_WIFI,
            currentProfile = profile,
            pauseElapsedMs = ms30Min + 1000,
            totalRuntimeMs = 45 * 60 * 1000,
            nowMs = nowMs
        )
        assertTrue(decision is PauseDecision.Resign)
        val spec = (decision as PauseDecision.Resign).resumeSpec
        assertTrue(spec is ResumeSpec.WhenConstraintMet)
        assertEquals(NetworkType.UNMETERED, (spec as ResumeSpec.WhenConstraintMet).network)
        assertFalse(spec.batteryNotLow)
    }

    @Test
    fun `decidePauseAction resigns on prolonged battery pause with batteryNotLow constraint`() {
        engine = createEngine()
        val decision = engine.decidePauseAction(
            state = EngineState.PAUSED_BATTERY,
            currentProfile = profile,
            pauseElapsedMs = ms30Min + 1000,
            totalRuntimeMs = 45 * 60 * 1000,
            nowMs = nowMs
        )
        assertTrue(decision is PauseDecision.Resign)
        val spec = (decision as PauseDecision.Resign).resumeSpec
        assertTrue(spec is ResumeSpec.WhenConstraintMet)
        assertTrue((spec as ResumeSpec.WhenConstraintMet).batteryNotLow)
    }

    @Test
    fun `decidePauseAction continues looping during short battery pause`() {
        engine = createEngine()
        val decision = engine.decidePauseAction(
            state = EngineState.PAUSED_BATTERY,
            currentProfile = profile,
            pauseElapsedMs = 60_000, // 1 min
            totalRuntimeMs = 10 * 60 * 1000,
            nowMs = nowMs
        )
        assertEquals(PauseDecision.Continue, decision)
    }

    @Test
    fun `decidePauseAction continues looping on rate limit pause`() {
        engine = createEngine()
        val decision = engine.decidePauseAction(
            state = EngineState.PAUSED_RATE_LIMIT,
            currentProfile = profile,
            pauseElapsedMs = 5 * 60 * 1000,
            totalRuntimeMs = 30 * 60 * 1000,
            nowMs = nowMs
        )
        assertEquals(PauseDecision.Continue, decision)
    }

    @Test
    fun `nextAllowedHoursStartMs returns same-day boundary when start hour is later`() {
        engine = createEngine()
        // Construct nowMs that is at 03:00 local time (use Calendar to build it)
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 3)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis
        val p = profile.copy(allowedHoursStart = 7)
        val result = engine.nextAllowedHoursStartMs(p, now)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 7)
        assertEquals(cal.timeInMillis, result)
    }

    @Test
    fun `nextAllowedHoursStartMs returns next-day boundary when start hour has passed`() {
        engine = createEngine()
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 23)
            set(java.util.Calendar.MINUTE, 30)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis
        val p = profile.copy(allowedHoursStart = 7)
        val result = engine.nextAllowedHoursStartMs(p, now)
        cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 7)
        cal.set(java.util.Calendar.MINUTE, 0)
        assertEquals(cal.timeInMillis, result)
    }

    @Test
    fun `engine stops gracefully when no modules enabled`() = runTest {
        // Disable all modules
        every { searchModule.isEnabled() } returns false
        every { adModule.isEnabled() } returns false
        every { locationModule.isEnabled() } returns false
        every { fingerprintModule.isEnabled() } returns false
        every { cookieModule.isEnabled() } returns false
        every { appSignalModule.isEnabled() } returns false
        every { dnsModule.isEnabled() } returns false

        engine = createEngine()
        engine.start()
        delay(200)
        engine.destroy()
        // Engine should have stayed in its loop without crashing
    }

    private fun createEngine(): PoisonEngine {
        // Set up mock returns for isEnabled
        every { searchModule.isEnabled() } returns true
        every { adModule.isEnabled() } returns false
        every { locationModule.isEnabled() } returns false
        every { fingerprintModule.isEnabled() } returns false
        every { cookieModule.isEnabled() } returns false
        every { appSignalModule.isEnabled() } returns false
        every { dnsModule.isEnabled() } returns false

        val connectivityManager: android.net.ConnectivityManager = mockk(relaxed = true)
        val context: android.content.Context = mockk(relaxed = true) {
            every { getSystemService(android.content.Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        }
        val targetingEngine: TargetingEngine = mockk(relaxed = true) {
            every { setLayer1Enabled(any()) } answers { }
            every { setLayer2Enabled(any()) } answers { }
            every { setLayer3Enabled(any()) } answers { }
        }
        val clock: Clock = mockk {
            every { currentTimeMillis() } returns System.currentTimeMillis()
            every { elapsedRealtime() } returns 0L
        }
        return PoisonEngine(
            context, profileRepo, targetingEngine, dispatcher, scheduler, actionLogDao,
            blocklist, queryBankManager, crawlListManager, cityDatabase,
            searchModule, adModule, locationModule, fingerprintModule,
            cookieModule, appSignalModule, dnsModule, clock,
            loopDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )
    }
}
