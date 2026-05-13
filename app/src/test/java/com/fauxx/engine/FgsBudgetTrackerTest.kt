package com.fauxx.engine

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.fauxx.util.Clock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Direct integration test for [FgsBudgetTracker] against a real [DataStore].
 *
 * Verifies the cross-session accumulation behaviour: the original `engineStartElapsedMs`
 * timer reset on every [PoisonEngine.start], so two 4h sessions an hour apart looked
 * like "0h elapsed" to the engine while Android's rolling 24h enforcement counted 8h
 * and force-stopped the FGS. The tracker persists session totals so a fresh engine
 * session can compute its *effective* remaining budget on start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FgsBudgetTrackerTest {

    /** Mutable wall-clock that tests advance manually to simulate cross-session time gaps. */
    private class FakeClock(var nowMs: Long) : Clock {
        override fun currentTimeMillis(): Long = nowMs
        override fun elapsedRealtime(): Long = nowMs
    }

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var clock: FakeClock
    private lateinit var tracker: FgsBudgetTracker
    private lateinit var prefsFile: File

    @Before
    fun setUp() {
        prefsFile = File.createTempFile("fgs-budget-test-", ".preferences_pb").also { it.deleteOnExit() }
        prefsFile.delete() // PreferenceDataStoreFactory wants to create it itself
        dataStore = PreferenceDataStoreFactory.create(produceFile = { prefsFile })
        clock = FakeClock(START_OF_TEST_MS)
        tracker = FgsBudgetTracker(dataStore, clock)
        // Allow the init { } reactive collect to seed the cache. The first emission
        // is synchronous in PreferenceDataStoreFactory; a tiny await keeps tests flake-free.
        runBlocking { Thread.sleep(20) }
    }

    @After
    fun tearDown() {
        prefsFile.delete()
    }

    @Test
    fun `fresh tracker reports full budget`() {
        assertEquals(FgsBudgetTracker.BUDGET_MS, tracker.remainingBudgetMs())
    }

    @Test
    fun `recording a 4h session leaves 1h remaining`() = runBlocking {
        val fourHours = 4L * 60 * 60 * 1000
        tracker.recordSession(fourHours)
        // Wait for the reactive cache update to propagate.
        Thread.sleep(50)
        assertEquals(FgsBudgetTracker.BUDGET_MS - fourHours, tracker.remainingBudgetMs())
    }

    @Test
    fun `two sessions within 24h accumulate so second start sees reduced budget`() = runBlocking {
        // This is the *exact scenario* the in-process timer couldn't catch:
        // user runs 3h, stops, restarts an hour later, runs another 3h.
        val threeHours = 3L * 60 * 60 * 1000

        // First session: 3h.
        tracker.recordSession(threeHours)
        Thread.sleep(50)
        clock.nowMs += 60L * 60 * 1000 // 1h pause (no engine running)

        // The tracker should now reflect 3h consumed in the 24h window.
        assertEquals(FgsBudgetTracker.BUDGET_MS - threeHours, tracker.remainingBudgetMs())

        // Second session starts here. The engine would read remainingBudgetMs() and
        // cap its session limit accordingly. Verify the cap is exactly what's left.
        val second = tracker.remainingBudgetMs()
        assertEquals(2L * 60 * 60 * 1000, second)
    }

    @Test
    fun `budget resets when window age exceeds 24h`() = runBlocking {
        tracker.recordSession(4L * 60 * 60 * 1000)
        Thread.sleep(50)
        // Jump forward >24h. Next read should see a fresh budget.
        clock.nowMs += 25L * 60 * 60 * 1000
        assertEquals(FgsBudgetTracker.BUDGET_MS, tracker.remainingBudgetMs())
    }

    @Test
    fun `recordSession after window expiry starts a new window`() = runBlocking {
        tracker.recordSession(4L * 60 * 60 * 1000)
        Thread.sleep(50)
        clock.nowMs += 25L * 60 * 60 * 1000

        // New session in the expired-window regime: should reset, not add 4h+1h.
        tracker.recordSession(1L * 60 * 60 * 1000)
        Thread.sleep(50)
        assertEquals(FgsBudgetTracker.BUDGET_MS - 1L * 60 * 60 * 1000, tracker.remainingBudgetMs())
    }

    @Test
    fun `nextWindowResetMs returns 24h from the window start time`() = runBlocking {
        val sessionLen = 2L * 60 * 60 * 1000
        tracker.recordSession(sessionLen)
        Thread.sleep(50)
        // Window start was set to nowMs - sessionLen, so reset = nowMs - sessionLen + 24h.
        val expectedReset = (clock.nowMs - sessionLen) + FgsBudgetTracker.WINDOW_MS
        assertEquals(expectedReset, tracker.nextWindowResetMs())
        // Sanity: reset is in the future, not the past.
        assertTrue("reset must be > now", tracker.nextWindowResetMs() > clock.nowMs)
    }

    @Test
    fun `budget never goes negative even if session over-runs`() = runBlocking {
        // 8h session — exceeds the 5h budget. Tracker should clamp the reported "used"
        // to the window length so remaining stays at 0, not negative.
        tracker.recordSession(8L * 60 * 60 * 1000)
        Thread.sleep(50)
        assertEquals(0L, tracker.remainingBudgetMs())
    }

    companion object {
        private const val START_OF_TEST_MS = 1_700_000_000_000L
    }
}
