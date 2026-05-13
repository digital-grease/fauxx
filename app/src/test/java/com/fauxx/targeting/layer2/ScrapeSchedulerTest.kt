package com.fauxx.targeting.layer2

import android.content.Context
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Integration test for [ScrapeScheduler]'s WorkManager wiring.
 *
 * This is the test that *would have caught* issue #22 (Scrape Now button doesn't work):
 * the original `scrapeNow()` called `schedule()`, which enqueued a 7-day PeriodicWorkRequest
 * under unique name `fauxx_scrape` with `ExistingPeriodicWorkPolicy.KEEP`. A periodic
 * job with KEEP policy never fires immediately on subsequent taps — the button was
 * silently a no-op. This test asserts the *correct* contract: a one-time request under
 * a distinct unique-work name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ScrapeSchedulerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: ScrapeScheduler

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        scheduler = ScrapeScheduler(context)
    }

    @Test
    fun `scrapeNow enqueues a one-time request under fauxx_scrape_now`() {
        val id: UUID = scheduler.scrapeNow()

        val infos = workManager.getWorkInfosForUniqueWork("fauxx_scrape_now").get()
        assertEquals("exactly one enqueued work under fauxx_scrape_now", 1, infos.size)
        assertEquals("returned UUID must match the enqueued work id", id, infos[0].id)
        // ENQUEUED state means waiting on constraints; that's expected because the
        // test WorkManager doesn't have a real network. The key point is "the work
        // exists." Pre-fix, this query returned 0 entries.
        assertTrue(
            "expected work to be ENQUEUED or BLOCKED (waiting on constraints), got ${infos[0].state}",
            infos[0].state == WorkInfo.State.ENQUEUED ||
                infos[0].state == WorkInfo.State.BLOCKED ||
                infos[0].state == WorkInfo.State.RUNNING
        )
    }

    @Test
    fun `scrapeNow uses CONNECTED network constraint not UNMETERED`() {
        // Pre-fix: scrapeNow was wired to schedule(), which used NetworkType.UNMETERED.
        // That meant a user on cellular could never trigger an on-demand scrape — a
        // major contributor to "Scrape Now does nothing." Asserting CONNECTED here
        // catches any regression that re-tightens this constraint.
        scheduler.scrapeNow()
        val infos = workManager.getWorkInfosForUniqueWork("fauxx_scrape_now").get()
        assertEquals(1, infos.size)
        val constraints = infos[0].constraints
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
    }

    @Test
    fun `scrapeNow replaces in-flight one-shot rather than queuing duplicates`() {
        // ExistingWorkPolicy.REPLACE — user mashing the button should end up with one
        // request, not five. The replaced one becomes CANCELLED, the latest is ENQUEUED.
        val first = scheduler.scrapeNow()
        val second = scheduler.scrapeNow()
        assertNotEquals("each scrapeNow returns a fresh UUID", first, second)

        val active = workManager.getWorkInfosForUniqueWork("fauxx_scrape_now").get()
            .firstOrNull { it.state != WorkInfo.State.CANCELLED }
        assertNotNull("at least one non-cancelled work entry must remain", active)
        assertEquals("the active entry must be the most recently enqueued", second, active!!.id)
    }

    @Test
    fun `schedule and scrapeNow use distinct unique-work names`() {
        // Schedule the periodic 7-day refresh and a one-shot in the same setup.
        // They must not collide — otherwise the one-shot would replace the periodic,
        // breaking the auto-refresh cadence (or vice versa).
        scheduler.schedule()
        scheduler.scrapeNow()

        val periodic = workManager.getWorkInfosForUniqueWork("fauxx_scrape").get()
        val oneShot = workManager.getWorkInfosForUniqueWork("fauxx_scrape_now").get()

        assertEquals("fauxx_scrape (periodic) must exist exactly once", 1, periodic.size)
        assertEquals("fauxx_scrape_now (one-shot) must exist exactly once", 1, oneShot.size)
        assertNotEquals(
            "the periodic and one-shot must be distinct work entries",
            periodic[0].id, oneShot[0].id
        )
    }
}
