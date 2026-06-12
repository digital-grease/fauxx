package com.fauxx.targeting.allocation

import com.fauxx.data.querybank.CategoryPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CooccurrenceTableTest {

    private fun table() = CooccurrenceTable(
        neighbors = mapOf(
            CategoryPool.FINANCE to mapOf(CategoryPool.REAL_ESTATE to 0.8),
            CategoryPool.REAL_ESTATE to mapOf(CategoryPool.FINANCE to 0.8),
        ),
        bias = -2.0, selfCoef = 6.0, neighborCoef = 3.0,
    )

    @Test
    fun `affinity is symmetric and same-category is zero`() {
        val t = table()
        assertEquals(0.8, t.affinity(CategoryPool.FINANCE, CategoryPool.REAL_ESTATE), 1e-9)
        assertEquals(0.8, t.affinity(CategoryPool.REAL_ESTATE, CategoryPool.FINANCE), 1e-9)
        assertEquals(0.0, t.affinity(CategoryPool.FINANCE, CategoryPool.FINANCE), 1e-9)
        assertEquals(0.0, t.affinity(CategoryPool.FINANCE, CategoryPool.GAMING), 1e-9)
    }

    @Test
    fun `neighborsOf returns edges or empty`() {
        val t = table()
        assertEquals(setOf(CategoryPool.REAL_ESTATE), t.neighborsOf(CategoryPool.FINANCE).keys)
        assertTrue(t.neighborsOf(CategoryPool.GAMING).isEmpty())
    }

    @Test
    fun `empty table has model defaults and no structure`() {
        val e = CooccurrenceTable.empty()
        assertTrue(e.isEmpty)
        assertEquals(CooccurrenceTable.DEFAULT_BIAS, e.bias, 1e-9)
        assertEquals(CooccurrenceTable.DEFAULT_SELF_COEF, e.selfCoef, 1e-9)
        assertEquals(0.0, e.affinity(CategoryPool.FINANCE, CategoryPool.REAL_ESTATE), 1e-9)
        assertTrue(e.neighborsOf(CategoryPool.FINANCE).isEmpty())
    }
}
