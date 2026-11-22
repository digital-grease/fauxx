package com.fauxx

import com.fauxx.data.crawllist.CrawlListManager
import com.fauxx.data.crawllist.DomainBlocklist
import com.fauxx.data.db.ActionLogDao
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.location.CityCoord
import com.fauxx.data.location.CityDatabase
import com.fauxx.data.model.ActionType
import com.fauxx.data.model.IntensityLevel
import com.fauxx.data.model.PoisonProfile
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.data.querybank.QueryBankManager
import com.fauxx.engine.EngineState
import com.fauxx.engine.NetworkTransport
import com.fauxx.engine.PauseDecision
import com.fauxx.engine.PoisonEngine
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.modules.AppSignalModule
import com.fauxx.engine.modules.CookieSaturationModule
import com.fauxx.engine.modules.DnsNoiseModule
import com.fauxx.engine.modules.FingerprintModule
import com.fauxx.engine.modules.Module
import com.fauxx.engine.modules.SearchPoisonModule
import com.fauxx.engine.power.EngineWakeLock
import com.fauxx.engine.power.EngineWakeup
import com.fauxx.engine.scheduling.ActionDispatcher
import com.fauxx.engine.scheduling.ActionPacer
import com.fauxx.service.ResumeSpec
import com.fauxx.support.FixedPacer
import com.fauxx.support.FakeClock
import com.fauxx.targeting.TargetingEngine
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The engine keeps working with the screen off (#335): it holds the CPU only while an action runs,
 * sets a wakeup for the next one, measures its waits against elapsed realtime (which keeps counting
 * while the CPU sleeps), and does not give up the service over a network outage that comes from
 * the device sleeping.
 *
 * A suspended CPU is modelled the way it behaves: NO loop pass runs and NO coroutine time passes,
 * while elapsed realtime ([FakeClock]) jumps. [advance] is the awake case, where both move together.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PoisonEngineWakeTest {

    private val baseProfile = PoisonProfile(
        enabled = true,
        intensity = IntensityLevel.LOW,
        // The relaxed ConnectivityManager mock classifies as CELLULAR, so allow mobile (#62).
        mobileIntensity = IntensityLevel.LOW,
        batteryThresholdBattery = 0,
        batteryThresholdCharging = 0,
        allowedHoursStart = 0,
        allowedHoursEnd = 0,
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

    /**
     * The engine's own scheduler, apart from any runTest scheduler: runTest drains its scheduler
     * after the body, and a still-running engine on it would keep acting in virtual time forever.
     */
    private val sched = TestCoroutineScheduler()

    /** What the fake PowerManager reports. */
    @Volatile private var interactive = true
    @Volatile private var deviceIdle = false

    /** Runs inside the engine's power-state reads, to land a change mid-pass. */
    @Volatile private var onPowerRead: (() -> Unit)? = null

    @After
    fun tearDown() {
        if (::engine.isInitialized) engine.destroy()
    }

    /** Records every acquire and release, and whether the lock is held right now. */
    private class RecordingWakeLock : EngineWakeLock {
        val events = mutableListOf<String>()
        @Volatile var held = false
        override fun acquire(timeoutMs: Long) {
            events += "acquire"
            held = true
        }
        override fun release() {
            if (held) events += "release"
            held = false
        }
    }

    /**
     * Wakeup whose waits are virtual-time delays, like production while awake, and which a test can
     * fire the way the alarm does after the CPU slept.
     */
    private class FakeWakeup : EngineWakeup {
        val scheduled = mutableListOf<Long>()
        var cancels = 0
        var handoverReleases = 0
        private val fired = Channel<Unit>(Channel.CONFLATED)
        override fun schedule(atElapsedRealtimeMs: Long) {
            scheduled += atElapsedRealtimeMs
        }
        override fun cancel() {
            cancels++
        }
        override suspend fun await(timeoutMs: Long): Boolean =
            withTimeoutOrNull(timeoutMs) { fired.receive() } != null
        override fun releaseHandover() {
            handoverReleases++
        }
        fun fire() {
            fired.trySend(Unit)
        }
    }

    // --- the lock ---

    @Test
    fun `the CPU is held for the action and let go before the wait`() {
        val clock = FakeClock(NOON)
        val lock = RecordingWakeLock()
        var heldDuringAction: Boolean? = null
        val search = searchModule { heldDuringAction = lock.held; success() }
        engine = buildEngine(clock, wakeLock = lock, searchModule = search)

        engine.start()
        advance(clock, by = 500)

        assertEquals("the action must run with the CPU held", true, heldDuringAction)
        assertFalse("the lock must be released once the engine is waiting", lock.held)
        assertEquals(listOf("acquire", "release"), lock.events)
    }

    @Test
    fun `a zero-length wait keeps the CPU held into the next action`() {
        // At HIGH intensity the gap is often shorter than the action, so the wait is 0. Releasing
        // there sets no alarm, and the CPU could suspend between two actions with nothing to wake it.
        val clock = FakeClock(NOON)
        val lock = RecordingWakeLock()
        val search = searchModule { success() }
        engine = buildEngine(clock, wakeLock = lock, searchModule = search, nextDelayMs = 0L)

        engine.start()
        advance(clock, by = 500)

        assertEquals("two actions back to back, no release between", "acquire", lock.events[1])
    }

    @Test
    fun `a failing action still lets go of the CPU`() {
        val clock = FakeClock(NOON)
        val lock = RecordingWakeLock()
        val search = searchModule { throw RuntimeException("page load failed") }
        engine = buildEngine(clock, wakeLock = lock, searchModule = search)

        engine.start()
        advance(clock, by = 500)

        assertTrue("the action took the lock", "acquire" in lock.events)
        assertFalse("a failure must not leave the CPU held", lock.held)
    }

    @Test
    fun `stopping mid-action lets go of the CPU`() {
        val clock = FakeClock(NOON)
        val lock = RecordingWakeLock()
        val started = CompletableDeferred<Unit>()
        val search = searchModule { started.complete(Unit); CompletableDeferred<Nothing>().await() }
        engine = buildEngine(clock, wakeLock = lock, searchModule = search)

        engine.start()
        advance(clock, by = 500)
        assertTrue("the action must be in flight", started.isCompleted)
        assertTrue("and holding the CPU", lock.held)

        engine.stop()
        advance(clock, by = 100)

        assertFalse("stop() must release the lock even with an action in flight", lock.held)
    }

    @Test
    fun `a stopped run unwinding late cannot release the lock of the run that replaced it`() {
        // stop() then start() while the first run's action is still unwinding (a WebView or Room
        // call finishing its cleanup). The old loop used to catch the CancellationException as a
        // module failure and go on to wait, releasing the shared lock mid-action of the new run.
        val clock = FakeClock(NOON)
        val lock = RecordingWakeLock()
        var calls = 0
        val search = searchModule {
            calls++
            if (calls == 1) {
                try {
                    CompletableDeferred<Nothing>().await()
                } catch (ce: CancellationException) {
                    withContext(NonCancellable) { delay(1_000) } // slow cleanup
                    throw ce
                }
            }
            CompletableDeferred<Nothing>().await() // the new run's action stays in flight
        }
        engine = buildEngine(clock, wakeLock = lock, searchModule = search)

        engine.start()
        advance(clock, by = 500)
        engine.stop()
        engine.start()
        advance(clock, by = 500)
        assertEquals("the new run is mid-action", 2, calls)
        assertTrue(lock.held)

        advance(clock, by = 2_000) // the old run finishes unwinding

        assertTrue("the old run must not release the new run's lock", lock.held)
        assertEquals("nor treat its cancellation as a failure and go again", 2, calls)
    }

    // --- the wakeup ---

    @Test
    fun `the wait sets a wakeup for its deadline and clears it afterwards`() {
        val clock = FakeClock(NOON)
        val wakeup = FakeWakeup()
        val actions = mutableListOf<Long>()
        val search = searchModule { actions += clock.nowMs; success() }
        engine = buildEngine(clock, wakeup = wakeup, searchModule = search, nextDelayMs = 5 * MINUTE)

        engine.start()
        advance(clock, by = 500)

        assertEquals(1, actions.size)
        assertEquals("one wakeup, at the end of the 5-minute gap", actions[0] + 5 * MINUTE, wakeup.scheduled.single())

        advance(clock, by = 5 * MINUTE + 500)

        assertEquals("the next action ran on time", 2, actions.size)
        assertTrue("the finished wait's wakeup was cleared", wakeup.cancels >= 1)
    }

    @Test
    fun `after the CPU slept through a wait the wakeup runs the next action at once`() {
        // The bug in #335: coroutine delays run on a clock that stops while the CPU sleeps, so
        // the engine froze with the screen off.
        val clock = FakeClock(NOON)
        val wakeup = FakeWakeup()
        var actions = 0
        val search = searchModule { actions++; success() }
        engine = buildEngine(clock, wakeup = wakeup, searchModule = search, nextDelayMs = 20 * MINUTE)

        engine.start()
        advance(clock, by = 500)
        assertEquals(1, actions)

        sleepCpu(clock, 20 * MINUTE)
        wakeup.fire()
        sched.runCurrent()

        assertEquals("the wakeup must end the wait without the 20 minutes of delay", 2, actions)
    }

    @Test
    fun `the wakeup's hold on the CPU is let go once the engine takes over`() {
        // Each alarm holds the CPU for a short handover. Kept for its whole timeout, every wakeup
        // would cost the full hold even when the engine has long since taken its own lock.
        val clock = FakeClock(NOON)
        val wakeup = FakeWakeup()
        engine = buildEngine(clock, wakeup = wakeup, nextDelayMs = 20 * MINUTE)

        engine.start()
        advance(clock, by = 500)
        val before = wakeup.handoverReleases

        sleepCpu(clock, 20 * MINUTE)
        wakeup.fire()
        sched.runCurrent()

        assertTrue("the engine must drop the handover hold", wakeup.handoverReleases > before)
    }

    @Test
    fun `an early wakeup sets the alarm again and the wait goes on`() {
        // Settling for plain delays after an early wakeup froze the rest of the wait once the CPU
        // slept again, and the real alarm then fired into a wait no longer listening.
        val clock = FakeClock(NOON)
        val wakeup = FakeWakeup()
        var actions = 0
        val search = searchModule { actions++; success() }
        engine = buildEngine(clock, wakeup = wakeup, searchModule = search, nextDelayMs = 20 * MINUTE)

        engine.start()
        advance(clock, by = 500)
        val deadline = wakeup.scheduled.single()

        sleepCpu(clock, 5 * MINUTE)
        wakeup.fire() // early
        sched.runCurrent()
        assertEquals("no action before the deadline", 1, actions)
        assertEquals("the alarm is set again for the same deadline", listOf(deadline, deadline), wakeup.scheduled)

        sleepCpu(clock, 15 * MINUTE)
        wakeup.fire()
        sched.runCurrent()
        assertEquals("the re-set alarm still ends the wait", 2, actions)
    }

    @Test
    fun `a wakeup that fires instantly every time cannot make the loop spin`() {
        // Guard: if the wake signal ever completes at once, the wait must still pass time. A spin
        // never returns from runCurrent, so the fake fails loudly past a bound instead of hanging
        // the suite. Error, not Exception, so the engine's module-failure catch cannot swallow it.
        val clock = FakeClock(NOON)
        var awaits = 0
        val instant = object : EngineWakeup {
            override fun schedule(atElapsedRealtimeMs: Long) {}
            override fun cancel() {}
            override suspend fun await(timeoutMs: Long): Boolean {
                if (++awaits > 10_000) throw AssertionError("the wait is spinning on an instant wakeup")
                return true
            }
        }
        var actions = 0
        val search = searchModule { actions++; success() }
        engine = buildEngine(clock, wakeup = instant, searchModule = search, nextDelayMs = MINUTE)

        engine.start()
        advance(clock, by = 5 * MINUTE)

        assertTrue("no spin: $awaits awaits", awaits < 100)
        assertTrue("actions keep their pace: $actions in 5 minutes", actions in 4..7)
    }

    @Test
    fun `a rate-limited engine sleeps until the window drains instead of polling`() {
        // Re-checking every 15 s set an alarm each time: up to 240 wakeups an hour with the
        // screen off while the window drained.
        val clock = FakeClock(NOON)
        val wakeup = FakeWakeup()
        engine = buildEngine(clock, wakeup = wakeup, nextDelayMs = 1_000L)

        engine.start()
        advance(clock, by = 30_000) // LOW: a ceiling of 24 actions an hour, one a second here

        assertEquals(EngineState.PAUSED_RATE_LIMIT, engine.engineState.value)
        val untilWakeup = wakeup.scheduled.last() - clock.nowMs
        assertTrue("one wakeup when the oldest action leaves the hour ($untilWakeup ms)", untilWakeup > 55 * MINUTE)
    }

    @Test
    fun `a move to a network with a higher rate ends the drain sleep at once`() {
        // Rate-limited on mobile LOW with the screen off, the phone joins Wi-Fi at HIGH. The
        // network callback must end the drain sleep; the between-chunk check does not run while
        // the CPU is suspended, so without it the engine stayed paused until the drain alarm.
        val clock = FakeClock(NOON)
        var actions = 0
        val search = searchModule { actions++; success() }
        engine = buildEngine(
            clock, searchModule = search, nextDelayMs = 1_000L,
            profile = baseProfile.copy(intensity = IntensityLevel.HIGH, mobileIntensity = IntensityLevel.LOW),
        )

        engine.start() // the relaxed ConnectivityManager reads as mobile data
        advance(clock, by = 30_000) // mobile LOW: a ceiling of 24 actions an hour
        assertEquals(EngineState.PAUSED_RATE_LIMIT, engine.engineState.value)
        val atCap = actions

        engine.setCachedConstraintStateForTest(transport = NetworkTransport.WIFI)
        sched.runCurrent()
        advance(clock, by = 2_000)

        assertTrue("Wi-Fi HIGH allows more: actions must resume ($atCap -> $actions)", actions > atCap)
    }

    @Test
    fun `a permanently broken module never sets an alarm`() {
        // A failed blocklist breaks the URL modules for good (Long.MAX_VALUE). Clamping that to a
        // 30-minute backoff woke the phone every half hour forever for nothing.
        val clock = FakeClock(NOON)
        val wakeup = FakeWakeup()
        engine = buildEngine(clock, wakeup = wakeup, blocklistFailed = true)

        engine.start()
        advance(clock, by = 2 * 60 * MINUTE)

        assertTrue("no alarm for a module that can never run", wakeup.scheduled.isEmpty())
    }

    // --- network pauses ---

    @Test
    fun `a network outage while the screen is off never resigns the service`() {
        val clock = FakeClock(NOON)
        interactive = false
        engine = buildEngine(clock)
        var resignedWith: ResumeSpec? = null
        engine.setOnLongPause { resignedWith = it }

        engine.start()
        engine.setCachedConstraintStateForTest(transport = NetworkTransport.NONE)
        advance(clock, by = 45 * MINUTE)

        assertEquals(EngineState.PAUSED_WIFI, engine.engineState.value)
        assertNull("a sleeping phone's network cut must not hand recovery to a notification", resignedWith)
    }

    @Test
    fun `a night offline does not count toward the resign clock`() {
        // The user picks the phone up after a night with no network; Wi-Fi is still reassociating.
        // No pass ran overnight (the CPU was suspended), so the clock must restart on the first
        // pass with the screen on, not count the hours since the last pass.
        val clock = FakeClock(NOON)
        interactive = false
        engine = buildEngine(clock)
        var resignedWith: ResumeSpec? = null
        engine.setOnLongPause { resignedWith = it }

        engine.start()
        engine.setCachedConstraintStateForTest(transport = NetworkTransport.NONE)
        advance(clock, by = 5_000)
        sleepCpu(clock, 8 * 60 * MINUTE)
        interactive = true
        advance(clock, by = 10 * MINUTE)
        assertNull("10 minutes awake is under the threshold", resignedWith)

        advance(clock, by = 25 * MINUTE)
        assertTrue(
            "30 minutes offline with the screen on still resigns, as before",
            resignedWith is ResumeSpec.WhenConstraintMet
        )
    }

    @Test
    fun `a suspended CPU never seen asleep still does not count toward the resign clock`() {
        // The screen read as on at every pass (or the read failed), but the passes are hours apart:
        // that gap is sleep, since no pass runs while suspended.
        val clock = FakeClock(NOON)
        engine = buildEngine(clock)
        var resignedWith: ResumeSpec? = null
        engine.setOnLongPause { resignedWith = it }

        engine.start()
        engine.setCachedConstraintStateForTest(transport = NetworkTransport.NONE)
        advance(clock, by = 10 * MINUTE)
        sleepCpu(clock, 8 * 60 * MINUTE)
        advance(clock, by = 10 * MINUTE)

        assertNull("the suspended gap must not count", resignedWith)
    }

    @Test
    fun `a network blocked for this app gets its own pause and never resigns`() {
        val clock = FakeClock(NOON)
        var actions = 0
        val search = searchModule { actions++; success() }
        engine = buildEngine(clock, searchModule = search)
        var resignedWith: ResumeSpec? = null
        engine.setOnLongPause { resignedWith = it }

        engine.start()
        engine.setCachedConstraintStateForTest(networkBlocked = true)
        advance(clock, by = 2 * 60 * MINUTE)

        assertEquals(EngineState.PAUSED_NETWORK_BLOCKED, engine.engineState.value)
        assertEquals("actions would only fail", 0, actions)
        assertNull("the network is up: a network constraint would resign and restart in a loop", resignedWith)

        engine.setCachedConstraintStateForTest(networkBlocked = false)
        advance(clock, by = 100)
        assertTrue("the engine resumes as soon as the block lifts", actions > 0)
    }

    @Test
    fun `time blocked does not count toward the resign clock of the pause after it`() {
        // Forty minutes blocked with the screen on never resign. When the block gives way to a
        // Wi-Fi drop, that pause must start its own 30 minutes, not resign at once on the block's.
        val clock = FakeClock(NOON)
        engine = buildEngine(clock)
        var resignedWith: ResumeSpec? = null
        engine.setOnLongPause { resignedWith = it }

        engine.start()
        engine.setCachedConstraintStateForTest(networkBlocked = true)
        advance(clock, by = 40 * MINUTE)
        assertEquals(EngineState.PAUSED_NETWORK_BLOCKED, engine.engineState.value)

        engine.setCachedConstraintStateForTest(networkBlocked = false, transport = NetworkTransport.NONE)
        advance(clock, by = 10 * MINUTE)
        assertNull("the block's 40 minutes must not count", resignedWith)

        advance(clock, by = 25 * MINUTE)
        assertTrue("its own 30 awake minutes still resign", resignedWith is ResumeSpec.WhenConstraintMet)
    }

    @Test
    fun `a night on mobile data with mobile Off does not count toward the resign clock`() {
        // The default profile has the mobile tier Off. An OEM that drops Wi-Fi in sleep leaves
        // mobile data as the default network: PAUSED_WIFI with a CELLULAR transport, not NONE.
        overnightPauseDoesNotResign(NetworkTransport.CELLULAR, EngineState.PAUSED_WIFI)
    }

    @Test
    fun `a night on a metered Wi-Fi with mobile Off does not count toward the resign clock`() {
        overnightPauseDoesNotResign(NetworkTransport.METERED_WIFI, EngineState.PAUSED_METERED_WIFI)
    }

    private fun overnightPauseDoesNotResign(transport: NetworkTransport, expected: EngineState) {
        val clock = FakeClock(NOON)
        interactive = false
        engine = buildEngine(clock, profile = baseProfile.copy(mobileIntensity = null))
        var resignedWith: ResumeSpec? = null
        engine.setOnLongPause { resignedWith = it }

        engine.start()
        engine.setCachedConstraintStateForTest(transport = transport)
        advance(clock, by = 5_000)
        assertEquals(expected, engine.engineState.value)
        sleepCpu(clock, 8 * 60 * MINUTE)
        interactive = true
        advance(clock, by = 10 * MINUTE)
        assertNull("the night must not count", resignedWith)

        advance(clock, by = 25 * MINUTE)
        assertTrue("30 awake minutes still resign, as before", resignedWith is ResumeSpec.WhenConstraintMet)
    }

    @Test
    fun `a pause on mobile data with mobile Off ends the moment Wi-Fi returns`() {
        // A plain timer here freezes while the CPU is suspended; the network callback is what a
        // dozing device delivers when Wi-Fi comes back.
        val clock = FakeClock(NOON)
        var actions = 0
        val search = searchModule { actions++; success() }
        engine = buildEngine(clock, searchModule = search, profile = baseProfile.copy(mobileIntensity = null))

        engine.start() // the relaxed ConnectivityManager reads as mobile data
        advance(clock, by = 5_000)
        assertEquals(EngineState.PAUSED_WIFI, engine.engineState.value)

        engine.setCachedConstraintStateForTest(transport = NetworkTransport.WIFI)
        sched.runCurrent()

        assertTrue("Wi-Fi returning must end the pause without waiting out the timer", actions > 0)
    }

    @Test
    fun `a network change during the pause pass is not missed`() {
        // The change lands while the pass is checking and logging. The wait must still see it,
        // rather than sit out its timeout (60 s at LOW).
        val clock = FakeClock(NOON)
        var actions = 0
        val search = searchModule { actions++; success() }
        engine = buildEngine(clock, searchModule = search)

        engine.start()
        engine.setCachedConstraintStateForTest(transport = NetworkTransport.NONE)
        onPowerRead = {
            onPowerRead = null
            engine.setCachedConstraintStateForTest(transport = NetworkTransport.CELLULAR)
        }
        advance(clock, by = 2_000)

        assertTrue("the restored network must end the pause at once", actions > 0)
    }

    @Test
    fun `decidePauseAction never resigns on a network pause the sleeping device caused`() {
        engine = buildEngine(FakeClock(NOON))
        val longPause = 2 * 60 * MINUTE
        assertEquals(
            PauseDecision.Continue,
            engine.decidePauseAction(EngineState.PAUSED_WIFI, baseProfile, longPause, longPause, idleInducedNetworkPause = true)
        )
        assertTrue(
            "awake, the same pause still resigns",
            engine.decidePauseAction(EngineState.PAUSED_WIFI, baseProfile, longPause, longPause) is PauseDecision.Resign
        )
        assertEquals(
            "sleep that leaves a metered Wi-Fi as the network is covered too",
            PauseDecision.Continue,
            engine.decidePauseAction(
                EngineState.PAUSED_METERED_WIFI, baseProfile.copy(mobileIntensity = null), longPause, longPause,
                idleInducedNetworkPause = true,
            )
        )
        assertTrue(
            "awake, a metered Wi-Fi pause still resigns",
            engine.decidePauseAction(
                EngineState.PAUSED_METERED_WIFI, baseProfile.copy(mobileIntensity = null), longPause, longPause,
            ) is PauseDecision.Resign
        )
        assertEquals(
            PauseDecision.Continue,
            engine.decidePauseAction(EngineState.PAUSED_NETWORK_BLOCKED, baseProfile, longPause, longPause)
        )
        assertTrue(
            "quiet hours resign whatever the device is doing",
            engine.decidePauseAction(
                EngineState.PAUSED_QUIET_HOURS, baseProfile, 0, 0, idleInducedNetworkPause = true
            ) is PauseDecision.Resign
        )
    }

    // --- helpers ---

    private fun success() = ActionLogEntity(
        timestamp = 0L,
        actionType = ActionType.SEARCH_QUERY,
        category = CategoryPool.GAMING,
        detail = "q",
        success = true,
    )

    private fun searchModule(onAction: suspend () -> ActionLogEntity): SearchPoisonModule =
        mockk(relaxed = true) {
            every { isEnabled() } returns true
            coEvery { onAction(any()) } coAnswers { onAction() }
        }

    /** Awake: elapsed realtime and coroutine time move together, one second at a time. */
    private fun advance(clock: FakeClock, by: Long) {
        val step = 1_000L
        var remaining = by
        while (remaining > 0) {
            val chunk = minOf(step, remaining)
            clock.nowMs += chunk
            sched.advanceTimeBy(chunk)
            sched.runCurrent()
            remaining -= chunk
        }
        sched.runCurrent()
    }

    /** Asleep: elapsed realtime moves, no coroutine time passes and no loop pass runs. */
    private fun sleepCpu(clock: FakeClock, by: Long) {
        clock.nowMs += by
    }

    private fun buildEngine(
        clock: FakeClock,
        wakeLock: EngineWakeLock = EngineWakeLock.NONE,
        wakeup: EngineWakeup = EngineWakeup.NONE,
        searchModule: SearchPoisonModule = searchModule { success() },
        nextDelayMs: Long = MINUTE,
        blocklistFailed: Boolean = false,
        profile: PoisonProfile = baseProfile,
        loopDispatcher: CoroutineDispatcher = StandardTestDispatcher(sched),
    ): PoisonEngine {
        val profileRepo: PoisonProfileRepository = mockk { every { getProfile() } returns profile }
        val scheduler: ActionPacer = FixedPacer(nextDelayMs)
        val dispatcher: ActionDispatcher = mockk { coEvery { selectCategory() } returns CategoryPool.GAMING }
        val noopModule: Module = mockk(relaxed = true) { every { isEnabled() } returns false }
        val connectivityManager: android.net.ConnectivityManager = mockk(relaxed = true)
        val context: android.content.Context = mockk(relaxed = true) {
            every { getSystemService(android.content.Context.CONNECTIVITY_SERVICE) } returns connectivityManager
            every { getSystemService(android.os.PowerManager::class.java) } returns mockk<android.os.PowerManager> {
                every { isPowerSaveMode } returns false
                every { isInteractive } answers { onPowerRead?.invoke(); interactive }
                every { isDeviceIdleMode } answers { onPowerRead?.invoke(); deviceIdle }
                every { isIgnoringBatteryOptimizations(any()) } returns false
            }
        }
        return PoisonEngine(
            context, profileRepo,
            mockk<TargetingEngine>(relaxed = true),
            dispatcher, scheduler,
            mockk<ActionLogDao>(relaxed = true),
            mockk<DomainBlocklist> { every { loadFailed } returns blocklistFailed },
            mockk<QueryBankManager> {
                every { corpusGeneration } returns 0
                every { getQueries(any()) } returns listOf("test")
            },
            mockk<CrawlListManager> { every { corpusSize() } returns 100 },
            mockk<CityDatabase> {
                every { cities } returns listOf(CityCoord("A", 0.0, 0.0, "X"), CityCoord("B", 1.0, 1.0, "X"))
            },
            searchModule,
            adModule = noopModule,
            locationModule = noopModule,
            fingerprintModule = mockk<FingerprintModule>(relaxed = true) { every { isEnabled() } returns false },
            cookieModule = mockk<CookieSaturationModule>(relaxed = true) { every { isEnabled() } returns false },
            appSignalModule = mockk<AppSignalModule>(relaxed = true) { every { isEnabled() } returns false },
            dnsModule = mockk<DnsNoiseModule>(relaxed = true) { every { isEnabled() } returns false },
            clock = clock,
            loopDispatcher = loopDispatcher,
            wakeLock = wakeLock,
            wakeup = wakeup,
        )
    }

    private companion object {
        const val MINUTE = 60_000L

        /** Today's local 12:00. With allowed hours 0-0 (always on) the hour does not matter. */
        val NOON: Long = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 12)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
