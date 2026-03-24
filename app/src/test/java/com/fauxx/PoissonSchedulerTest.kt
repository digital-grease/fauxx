package com.fauxx

import com.fauxx.engine.scheduling.PoissonScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PoissonSchedulerTest {

    private val scheduler = PoissonScheduler()

    @Test
    fun `poissonDelay returns positive values`() {
        repeat(100) {
            val delay = scheduler.poissonDelay(60f)
            assertTrue("Delay must be positive: $delay", delay > 0)
        }
    }

    @Test
    fun `poissonDelay clamps to maximum 30 minutes`() {
        repeat(100) {
            val delay = scheduler.poissonDelay(60f)
            assertTrue("Delay must not exceed 30 min", delay <= 30 * 60 * 1000L)
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
}
