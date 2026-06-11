package com.fauxx

import com.fauxx.engine.scheduling.CircadianRateModulator
import com.fauxx.engine.scheduling.RateModulator
import com.fauxx.engine.scheduling.UsageHistogram
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E10 (#177): the observed-rhythm modulator. Acceptance criterion — the synthetic rate curve
 * tracks a supplied observed usage profile — plus the fallback-to-neutral and rate-honesty
 * (issue #76) contracts.
 */
class CircadianRateModulatorTest {

    private fun histogram(counts: LongArray) = object : UsageHistogram {
        override fun hourlyCounts(): LongArray = counts
    }

    private fun curve(m: CircadianRateModulator): List<Float> = (0..23).map { m.multiplier(it) }

    /** Evening-heavy profile: most screen-ons between 18:00 and 22:00. Well above maturity. */
    private fun eveningProfile(): LongArray = LongArray(24).apply {
        for (h in 0..23) this[h] = 2L
        for (h in 18..22) this[h] = 30L
    }

    @Test
    fun `neutral until enough observations accumulate`() {
        val sparse = LongArray(24).apply { this[20] = CircadianRateModulator.MIN_OBSERVATIONS - 1 }
        val m = CircadianRateModulator(histogram(sparse))
        curve(m).forEach { assertEquals(1f, it, 0f) }
    }

    @Test
    fun `synthetic rate curve tracks the supplied observed profile`() {
        val m = CircadianRateModulator(histogram(eveningProfile()))
        val c = curve(m)
        val peakHour = c.indices.maxBy { c[it] }
        val troughHour = c.indices.minBy { c[it] }
        assertTrue("peak $peakHour should fall in the observed busy window 18..22", peakHour in 18..22)
        assertTrue("trough $troughHour should be a low-usage hour", troughHour !in 18..22)
        // Busy hours get a higher multiplier than quiet hours.
        assertTrue("busy hour must out-rate a quiet hour", c[20] > c[4])
    }

    @Test
    fun `24-hour mean stays at one - rate honesty`() {
        val c = curve(CircadianRateModulator(histogram(eveningProfile())))
        val mean = c.sum() / c.size
        assertTrue("mean $mean drifted from 1.0", abs(mean - 1f) < 1e-3f)
    }

    @Test
    fun `output never leaves the documented multiplier band`() {
        // A single-hour spike is the most extreme shape the band has to absorb.
        val spike = LongArray(24).apply { this[9] = 5_000L }
        val c = curve(CircadianRateModulator(histogram(spike)))
        c.forEachIndexed { h, v ->
            assertTrue(
                "hour $h multiplier $v outside [${RateModulator.MIN_MULTIPLIER}, ${RateModulator.MAX_MULTIPLIER}]",
                v in RateModulator.MIN_MULTIPLIER..RateModulator.MAX_MULTIPLIER
            )
        }
    }

    @Test
    fun `a perfectly flat mature profile is neutral`() {
        val flat = LongArray(24) { 10L } // total 240, well past maturity, but no shape
        curve(CircadianRateModulator(histogram(flat))).forEach { assertEquals(1f, it, 1e-6f) }
    }

    @Test
    fun `out-of-range hours are neutral`() {
        val m = CircadianRateModulator(histogram(eveningProfile()))
        assertEquals(1f, m.multiplier(-1), 0f)
        assertEquals(1f, m.multiplier(24), 0f)
    }
}
