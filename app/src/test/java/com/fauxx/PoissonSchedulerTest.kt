package com.fauxx

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.scheduling.PoissonScheduler
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
