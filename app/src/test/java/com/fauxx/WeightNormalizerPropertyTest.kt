package com.fauxx

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.targeting.WeightNormalizer
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property test for [WeightNormalizer]: over thousands of seeded random weight maps the
 * output must (a) sum to ~1.0 and (b) keep every category at or above [WeightNormalizer.MIN_WEIGHT]
 * simultaneously. The MIN_WEIGHT floor exists because a truly-zero category is itself a
 * detectable signal, so "floor preserved AND sums to 1" is the real invariant, including the
 * skewed / floor-saturated edge the two-pass normalization has to handle.
 *
 * First kotest-property test in the module: kotest-property runs inside an ordinary JUnit4
 * @Test via runBlocking + checkAll, with no JUnit5 engine (which would conflict with the
 * Robolectric/JUnit4 suite). Fixed default seed -> reproducible shrinking on failure.
 */
class WeightNormalizerPropertyTest {

    private val normalizer = WeightNormalizer()
    private val categories = CategoryPool.values().toList()

    @Test
    fun `normalized weights always sum to ~1 and preserve the floor`() = runBlocking<Unit> {
        val weightMaps = Arb.map(
            Arb.element(categories),
            Arb.float(min = 0f, max = 1_000f),
            minSize = 1,
            maxSize = categories.size,
        )
        checkAll(2_000, weightMaps) { weights ->
            val out = normalizer.normalize(weights)
            assertEquals(
                "normalized weights must sum to ~1.0; in=$weights out=$out",
                1.0f,
                out.values.sum(),
                0.01f,
            )
            out.forEach { (category, w) ->
                assertTrue(
                    "$category weight $w fell below MIN_WEIGHT; out=$out",
                    w >= WeightNormalizer.MIN_WEIGHT - 1e-6f,
                )
            }
        }
    }

    @Test
    fun `extreme skew (one dominant category, rest near zero) still sums to ~1`() {
        // The case the two-pass floor-then-renormalize has to survive: 30 categories pushed
        // to the floor while one dominates. Floors must not inflate the total past 1.
        val weights = categories.mapIndexed { i, c -> c to if (i == 0) 1_000f else 0.000_1f }.toMap()
        val out = normalizer.normalize(weights)
        assertEquals(1.0f, out.values.sum(), 0.01f)
        out.values.forEach { assertTrue(it >= WeightNormalizer.MIN_WEIGHT - 1e-6f) }
    }
}
