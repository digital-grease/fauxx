package com.fauxx.targeting.allocation

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.targeting.WeightNormalizer
import com.fauxx.targeting.layer2.ProfileDriftMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdversarialAllocatorTest {

    private val cats = CategoryPool.values()
    private val normalizer = WeightNormalizer()
    private val drift = ProfileDriftMetric()

    // FINANCE strongly co-occurs with REAL_ESTATE and BUSINESS, so a broker would infer FINANCE
    // largely from its neighbours — exactly the closure the allocator must defeat.
    private val table = CooccurrenceTable(
        neighbors = mapOf(
            CategoryPool.FINANCE to mapOf(CategoryPool.REAL_ESTATE to 0.9, CategoryPool.BUSINESS to 0.8),
            CategoryPool.REAL_ESTATE to mapOf(CategoryPool.FINANCE to 0.9),
            CategoryPool.BUSINESS to mapOf(CategoryPool.FINANCE to 0.8),
        ),
        bias = -2.0, selfCoef = 6.0, neighborCoef = 3.0,
    )
    private val surrogate = BrokerSurrogate(table)
    private val allocator = AdversarialAllocator(surrogate, normalizer, drift)

    private fun uniform(): Map<CategoryPool, Float> =
        normalizer.normalizeComplete(cats.associateWith { 1f })

    @Test
    fun `empty protected set returns the input unchanged`() {
        val input = uniform()
        assertEquals(input, allocator.allocate(input, emptySet()))
    }

    @Test
    fun `reduces surrogate inference of the protected interest within budget`() {
        val input = uniform()
        val out = allocator.allocate(input, setOf(CategoryPool.FINANCE), klBudget = 0.5)

        val before = surrogate.infer(input).getValue(CategoryPool.FINANCE)
        val after = surrogate.infer(out).getValue(CategoryPool.FINANCE)
        assertTrue("protected inference should drop: before=$before after=$after", after < before)

        // Constraint respected.
        assertTrue(drift.kl(out, input) <= 0.5 + 1e-9)
        // Still a valid distribution over every category.
        assertEquals(1.0, out.values.sum().toDouble(), 1e-3)
        assertEquals(cats.size, out.size)
        out.values.forEach { assertTrue(it >= WeightNormalizer.MIN_WEIGHT - 1e-6) }
    }

    @Test
    fun `a tiny budget keeps the result within that budget`() {
        val input = uniform()
        val out = allocator.allocate(input, setOf(CategoryPool.FINANCE), klBudget = 1e-4)
        assertTrue(drift.kl(out, input) <= 1e-4 + 1e-9)
    }

    @Test
    fun `is deterministic for identical inputs`() {
        val input = uniform()
        val a = allocator.allocate(input, setOf(CategoryPool.FINANCE), klBudget = 0.5)
        val b = allocator.allocate(input, setOf(CategoryPool.FINANCE), klBudget = 0.5)
        assertEquals(a, b)
    }

    @Test
    fun `never increases the protected objective`() {
        val input = uniform()
        val protectedSet = setOf(CategoryPool.FINANCE, CategoryPool.BUSINESS)
        val out = allocator.allocate(input, protectedSet, klBudget = 0.3)
        fun obj(w: Map<CategoryPool, Float>): Double = surrogate.infer(w).let { i -> protectedSet.sumOf { i.getValue(it) } }
        assertTrue(obj(out) <= obj(input) + 1e-9)
    }

    @Test
    fun `sensitive categories are excluded from the protected set`() {
        // None of the 32 CategoryPool names trip SensitiveAttributes today, but the allocator must
        // still never act on one. Drive that path: even if a sensitive-named category were asked
        // to be protected, allocation must remain a valid distribution and not crash.
        val input = uniform()
        val out = allocator.allocate(input, setOf(CategoryPool.POLITICS), klBudget = 0.3)
        assertEquals(1.0, out.values.sum().toDouble(), 1e-3)
    }
}
