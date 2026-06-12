package com.fauxx.targeting.allocation

import com.fauxx.data.querybank.CategoryPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrokerSurrogateTest {

    private val cats = CategoryPool.values()
    private fun uniform(): Map<CategoryPool, Float> = cats.associateWith { 1f / cats.size }

    private val linked = CooccurrenceTable(
        neighbors = mapOf(
            CategoryPool.FINANCE to mapOf(CategoryPool.REAL_ESTATE to 1.0),
            CategoryPool.REAL_ESTATE to mapOf(CategoryPool.FINANCE to 1.0),
        ),
        bias = -2.0, selfCoef = 6.0, neighborCoef = 3.0,
    )

    @Test
    fun `higher own mass yields higher inference`() {
        val s = BrokerSurrogate(linked)
        val base = uniform()
        val boosted = base.toMutableMap().apply { put(CategoryPool.GAMING, 0.3f) }
        assertTrue(s.infer(boosted).getValue(CategoryPool.GAMING) > s.infer(base).getValue(CategoryPool.GAMING))
    }

    @Test
    fun `neighbor mass raises a category's inference even with unchanged own mass`() {
        val s = BrokerSurrogate(linked)
        val base = uniform()
        // Mass on REAL_ESTATE should raise inference of its neighbor FINANCE.
        val m = base.toMutableMap().apply { put(CategoryPool.REAL_ESTATE, 0.3f) }
        assertTrue(s.infer(m).getValue(CategoryPool.FINANCE) > s.infer(base).getValue(CategoryPool.FINANCE))
    }

    @Test
    fun `empty table responds to own mass but has no neighbor closure`() {
        val s = BrokerSurrogate(CooccurrenceTable.empty())
        val base = uniform()
        val m = base.toMutableMap().apply { put(CategoryPool.REAL_ESTATE, 0.3f) }
        // Own mass still moves a category...
        assertTrue(s.infer(m).getValue(CategoryPool.REAL_ESTATE) > s.infer(base).getValue(CategoryPool.REAL_ESTATE))
        // ...but with no edges, a would-be neighbor does not move.
        assertEquals(
            s.infer(base).getValue(CategoryPool.FINANCE),
            s.infer(m).getValue(CategoryPool.FINANCE),
            1e-9,
        )
    }

    @Test
    fun `scores are probabilities in the unit interval`() {
        val s = BrokerSurrogate(linked)
        s.infer(uniform()).values.forEach { assertTrue(it in 0.0..1.0) }
    }
}
