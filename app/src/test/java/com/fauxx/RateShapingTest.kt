package com.fauxx

import com.fauxx.engine.scheduling.RateShaping
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E10 (#177): [RateShaping.meanPreservingFit] is the shared shaping primitive behind the
 * circadian and composite modulators. Its two guarantees — 24h mean exactly 1.0 (rate honesty,
 * issue #76) and every entry inside the clamp band — are what keep the modulation honest, so
 * they are pinned directly here rather than only through the modulators that use them.
 */
class RateShapingTest {

    private val lo = 0.5
    private val hi = 1.5

    private fun mean(a: DoubleArray) = a.average()

    @Test
    fun `mean is exactly one regardless of input shape`() {
        val inputs = listOf(
            doubleArrayOf(1.0, 2.0, 3.0, 4.0),
            doubleArrayOf(0.0, 0.0, 100.0, 0.0, 0.0),
            DoubleArray(24) { (it % 5).toDouble() },
            doubleArrayOf(7.0, 7.0, 7.0),
        )
        inputs.forEach { input ->
            val out = RateShaping.meanPreservingFit(input, lo, hi)
            assertEquals("mean must be 1.0 for ${input.toList()}", 1.0, mean(out), 1e-9)
        }
    }

    @Test
    fun `every entry stays within the clamp band`() {
        // A single dominant spike is the worst case for the upper bound.
        val spike = DoubleArray(24).also { it[20] = 1_000.0 }
        val out = RateShaping.meanPreservingFit(spike, lo, hi)
        out.forEachIndexed { i, v ->
            assertTrue("hour $i value $v below lo", v >= lo - 1e-9)
            assertTrue("hour $i value $v above hi", v <= hi + 1e-9)
        }
        // The spike hour is the peak, capped at hi; the rest sit at the attenuated floor.
        assertEquals(hi, out[20], 1e-9)
    }

    @Test
    fun `shape is preserved - ordering of inputs matches ordering of outputs`() {
        val input = doubleArrayOf(1.0, 5.0, 2.0, 8.0, 3.0)
        val out = RateShaping.meanPreservingFit(input, lo, hi)
        // Monotone map: argmax/argmin and pairwise order are preserved.
        assertEquals(input.indices.maxBy { input[it] }, out.indices.maxBy { out[it] })
        assertEquals(input.indices.minBy { input[it] }, out.indices.minBy { out[it] })
        for (i in input.indices) for (j in input.indices) {
            if (input[i] < input[j]) assertTrue(out[i] <= out[j] + 1e-12)
        }
    }

    @Test
    fun `flat input yields a flat neutral curve`() {
        val out = RateShaping.meanPreservingFit(DoubleArray(24) { 42.0 }, lo, hi)
        out.forEach { assertEquals(1.0, it, 1e-12) }
    }

    @Test
    fun `degenerate inputs fall back to neutral`() {
        // All-zero (no signal) and empty inputs must not divide by zero.
        RateShaping.meanPreservingFit(DoubleArray(24), lo, hi).forEach { assertEquals(1.0, it, 0.0) }
        assertEquals(0, RateShaping.meanPreservingFit(DoubleArray(0), lo, hi).size)
    }

    @Test
    fun `mild shape is not over-attenuated - it actually modulates`() {
        // A gentle ±25% shape fits inside the band without scaling, so it passes through.
        val input = doubleArrayOf(0.75, 1.25, 0.75, 1.25)
        val out = RateShaping.meanPreservingFit(input, lo, hi)
        assertTrue("output must vary, not collapse to flat", abs(out.max() - out.min()) > 0.1)
    }
}
