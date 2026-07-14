package com.fauxx

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.scheduling.PoissonScheduler
import com.fauxx.support.FakeClock
import com.fauxx.util.SystemClockImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PoissonSchedulerTest {

    private val scheduler = PoissonScheduler(SystemClockImpl())

    /** Sentinel allowed-hours window that covers all 24 hours so wall-clock can't gate tests. */
    private val ALL_HOURS_START = 0
    private val ALL_HOURS_END = 24

    @Test
    fun `poissonDelay returns positive values`() {
        repeat(100) {
            val delay = scheduler.poissonDelay(60f)
            assertTrue("Delay must be positive: $delay", delay > 0)
        }
    }

    @Test
    fun `poissonDelay clamps to intensity-scaled maximum`() {
        // MEDIUM (60/hr): mean=60s, max=3*60s=180s
        repeat(100) {
            val delay = scheduler.poissonDelay(60f)
            assertTrue("MEDIUM delay must not exceed 180s, got ${delay}ms", delay <= 180_000L)
        }
        // HIGH (200/hr): mean=18s, max=max(60s, 3*18s)=60s
        repeat(100) {
            val delay = scheduler.poissonDelay(200f)
            assertTrue("HIGH delay must not exceed 60s, got ${delay}ms", delay <= 60_000L)
        }
        // LOW (12/hr): mean=300s, max=3*300s=900s (15 min)
        repeat(100) {
            val delay = scheduler.poissonDelay(12f)
            assertTrue("LOW delay must not exceed 900s, got ${delay}ms", delay <= 900_000L)
        }
    }

    @Test
    fun `poissonDelay clamps to minimum 1 second`() {
        repeat(100) {
            val delay = scheduler.poissonDelay(1000f) // very high rate
            assertTrue("Delay must be at least 1s", delay >= 1000L)
        }
    }

    @Test
    fun `zero rate returns safe default`() {
        val delay = scheduler.poissonDelay(0f)
        assertEquals(60_000L, delay)
    }

    @Test
    fun `mean delay approximates Poisson expectation`() {
        val ratePerHour = 60f
        val expectedMeanMs = (60f * 60f * 1000f / ratePerHour).toLong()
        val samples = (1..10000).map { scheduler.poissonDelay(ratePerHour) }
        val mean = samples.average().toLong()
        // Within 20% of expected mean (statistical tolerance)
        val tolerance = expectedMeanMs * 0.20
        assertTrue(
            "Mean $mean should be near expected $expectedMeanMs (±20%)",
            abs(mean - expectedMeanMs) < tolerance
        )
    }

    // --- Cross-niche dwell-time tests ---
    //
    // These guard against the "Finance → Legal in milliseconds" bot signal flagged
    // in the F-Droid initial MR review.

    @Test
    fun `cross-niche transitions never produce sub-30s delays`() {
        // 1000 cross-niche samples at MEDIUM (60/hr); none should fall below the floor.
        repeat(1000) {
            val delay = scheduler.nextDelayMs(
                actionsPerHour = 60,
                prev = CategoryPool.FINANCE,
                next = CategoryPool.LEGAL,
                allowedStart = ALL_HOURS_START,
                allowedEnd = ALL_HOURS_END
            )
            assertTrue(
                "Cross-niche delay must be >=30s, got ${delay}ms",
                delay >= 30_000L
            )
        }
    }

    @Test
    fun `cross-niche median exceeds 30s and p95 exceeds 2min at MEDIUM rate`() {
        val samples = (1..2000).map {
            scheduler.nextDelayMs(
                actionsPerHour = 60,
                prev = CategoryPool.FINANCE,
                next = CategoryPool.LEGAL,
                allowedStart = ALL_HOURS_START,
                allowedEnd = ALL_HOURS_END
            )
        }.sorted()
        val median = samples[samples.size / 2]
        val p95 = samples[(samples.size * 95) / 100]
        assertTrue("Cross-niche median should exceed 30s, got ${median}ms", median > 30_000L)
        assertTrue("Cross-niche p95 should exceed 2min, got ${p95}ms", p95 > 120_000L)
    }

    @Test
    fun `same-category transitions still allow burst-range delays`() {
        // Bursts are 2-30s. Over 2000 samples at ~30% probability we expect ~600 in [2s, 30s).
        // Assert at least 100 to avoid flakiness while still catching a regression where the
        // burst path stops firing.
        val burstCount = (1..2000).count {
            val delay = scheduler.nextDelayMs(
                actionsPerHour = 60,
                prev = CategoryPool.FINANCE,
                next = CategoryPool.FINANCE,
                allowedStart = ALL_HOURS_START,
                allowedEnd = ALL_HOURS_END
            )
            delay in 2_000L until 30_000L
        }
        assertTrue(
            "Same-category bursts should still occur (>=100/2000), got $burstCount",
            burstCount >= 100
        )
    }

    // --- Tier-derived cross-niche floor (issue #76) ---
    //
    // The flat 30s floor capped real throughput far below the HIGH/EXTREME targets, making the
    // displayed actions/hour dishonest. The floor is now half the mean inter-arrival clamped to
    // [5s, 30s]: LOW/MEDIUM stay 30s, HIGH drops to 9s, EXTREME to 5s.

    @Test
    fun `cross-niche floor relaxes to 9s at HIGH but never dips below it`() {
        var sawBelow30s = false
        repeat(2000) {
            val delay = scheduler.nextDelayMs(
                actionsPerHour = 200,
                prev = CategoryPool.FINANCE,
                next = CategoryPool.LEGAL,
                allowedStart = ALL_HOURS_START,
                allowedEnd = ALL_HOURS_END
            )
            assertTrue("HIGH cross-niche must be >=9s, got ${delay}ms", delay >= 9_000L)
            if (delay < 30_000L) sawBelow30s = true
        }
        // The whole point of the relaxation: HIGH must actually be allowed under the old 30s floor.
        assertTrue("HIGH cross-niche should sometimes dip below the old 30s floor", sawBelow30s)
    }

    @Test
    fun `cross-niche floor relaxes to 5s at EXTREME but never dips below it`() {
        var sawBelow9s = false
        repeat(2000) {
            val delay = scheduler.nextDelayMs(
                actionsPerHour = 500,
                prev = CategoryPool.FINANCE,
                next = CategoryPool.LEGAL,
                allowedStart = ALL_HOURS_START,
                allowedEnd = ALL_HOURS_END
            )
            assertTrue("EXTREME cross-niche must be >=5s, got ${delay}ms", delay >= 5_000L)
            if (delay < 9_000L) sawBelow9s = true
        }
        assertTrue("EXTREME cross-niche should sometimes dip below the 9s HIGH floor", sawBelow9s)
    }

    @Test
    fun `cross-niche floor stays 30s at LOW and MEDIUM`() {
        listOf(12, 60).forEach { aph ->
            repeat(1000) {
                val delay = scheduler.nextDelayMs(
                    actionsPerHour = aph,
                    prev = CategoryPool.FINANCE,
                    next = CategoryPool.LEGAL,
                    allowedStart = ALL_HOURS_START,
                    allowedEnd = ALL_HOURS_END
                )
                assertTrue("aph=$aph cross-niche must stay >=30s, got ${delay}ms", delay >= 30_000L)
            }
        }
    }

    // --- Cross-niche upper ceiling (issue #209) ---
    //
    // The lognormal dwell multiplier is unbounded, so before the fix a single cross-niche gap
    // at LOW/MEDIUM could exceed an hour (an already-clamped baseDelay up to 15 min times the
    // ~4.5x+ lognormal tail), starving the displayed actions/hour — users reported "a few
    // actions then quiet for most of the hour". The dwell is now clamped to the SAME 3x-mean
    // ceiling poissonDelay uses, so a cross-niche pause can never exceed a same-topic gap.

    @Test
    fun `cross-niche delay never exceeds the 3x-mean ceiling at LOW`() {
        // LOW (12/hr): mean=300s, ceiling=3*300s=900s (15 min). Pre-fix this could blow past an hour.
        repeat(5000) {
            val delay = scheduler.nextDelayMs(
                actionsPerHour = 12,
                prev = CategoryPool.FINANCE,
                next = CategoryPool.LEGAL,
                allowedStart = ALL_HOURS_START,
                allowedEnd = ALL_HOURS_END
            )
            assertTrue("LOW cross-niche must not exceed 900s (got ${delay}ms)", delay <= 900_000L)
        }
    }

    @Test
    fun `cross-niche delay never exceeds the 3x-mean ceiling at MEDIUM`() {
        // MEDIUM (60/hr): mean=60s, ceiling=3*60s=180s.
        repeat(5000) {
            val delay = scheduler.nextDelayMs(
                actionsPerHour = 60,
                prev = CategoryPool.FINANCE,
                next = CategoryPool.LEGAL,
                allowedStart = ALL_HOURS_START,
                allowedEnd = ALL_HOURS_END
            )
            assertTrue("MEDIUM cross-niche must not exceed 180s (got ${delay}ms)", delay <= 180_000L)
        }
    }

    // --- Degenerate (start == end) allowed-hours window (issue #124) ---
    //
    // The scheduler's old private predicate evaluated start==end as `hour in start until
    // end`, an empty range, so EVERY call fell into the quiet-hours branch and returned
    // "ms until the next `start` hour boundary". For the 0-0 window users set to mean
    // "always on" (the engine's constraint gate documents start==end as always allowed),
    // that meant one action per engine start, then a sleep landing at local midnight —
    // the field logs in #124 show scheduled delays of 9h-21h, every one expiring at
    // exactly 24:00:00.

    @Test
    fun `equal start and end behaves as a 24h window at every hour of the day`() {
        // Same-topic delays are bounded by the Poisson clamp max(60s, 3x mean) = 180s at
        // MEDIUM (60/hr), so anything above that means the quiet-hours branch fired.
        (0..23).forEach { hour ->
            val pinned = PoissonScheduler(FakeClock(epochAtLocalHour(hour)))
            repeat(20) {
                val delay = pinned.nextDelayMs(
                    actionsPerHour = 60,
                    prev = CategoryPool.GAMING,
                    next = CategoryPool.GAMING,
                    allowedStart = 0,
                    allowedEnd = 0
                )
                assertTrue(
                    "hour=$hour: a 0-0 window must be always-active; got ${delay}ms " +
                        "which looks like a delay-until-hour-boundary",
                    delay <= 180_000L
                )
            }
        }
    }

    @Test
    fun `nonzero equal start and end also behaves as a 24h window`() {
        // 13-13 at 3 AM: pre-fix this returned ~10h (sleep until 13:00).
        val pinned = PoissonScheduler(FakeClock(epochAtLocalHour(3)))
        repeat(20) {
            val delay = pinned.nextDelayMs(
                actionsPerHour = 60,
                prev = CategoryPool.GAMING,
                next = CategoryPool.GAMING,
                allowedStart = 13,
                allowedEnd = 13
            )
            assertTrue("13-13 window must be always-active at 3 AM, got ${delay}ms", delay <= 180_000L)
        }
    }

    @Test
    fun `outside a normal window still delays until the window opens`() {
        // The quiet-hours branch itself must keep working for genuinely-outside hours:
        // at 3 AM with a 7-23 window the next action belongs at 07:00, i.e. 4h out.
        val pinned = PoissonScheduler(FakeClock(epochAtLocalHour(3)))
        val delay = pinned.nextDelayMs(
            actionsPerHour = 60,
            prev = CategoryPool.GAMING,
            next = CategoryPool.GAMING,
            allowedStart = 7,
            allowedEnd = 23
        )
        assertEquals(4 * 60 * 60 * 1000L, delay)
    }

    /** Epoch ms for today at [hour]:00:00.000 local time. */
    private fun epochAtLocalHour(hour: Int): Long =
        java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `null previous category behaves like same-topic and allows bursts`() {
        // First action of a session: prev=null. Should be burst-eligible (no artificial dwell).
        val burstCount = (1..2000).count {
            val delay = scheduler.nextDelayMs(
                actionsPerHour = 60,
                prev = null,
                next = CategoryPool.FINANCE,
                allowedStart = ALL_HOURS_START,
                allowedEnd = ALL_HOURS_END
            )
            delay in 2_000L until 30_000L
        }
        assertTrue(
            "First-action (null prev) should be burst-eligible, got $burstCount/2000",
            burstCount >= 100
        )
    }
}
