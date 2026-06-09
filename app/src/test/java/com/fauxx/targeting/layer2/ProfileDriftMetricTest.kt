package com.fauxx.targeting.layer2

import com.fauxx.data.querybank.CategoryPool
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileDriftMetricTest {

    private val gson = Gson()
    private val metric = ProfileDriftMetric()

    private fun snap(platform: String, cats: List<String>, at: Long) =
        ProfileSnapshot(
            platformName = platform, source = SnapshotSource.IMPORT,
            scrapedCategoriesJson = gson.toJson(cats), capturedAt = at,
        )

    @Test
    fun `collecting until two snapshots exist for a platform`() {
        assertEquals(DriftState.COLLECTING, metric.compute(emptyList()).state)
        val r = metric.compute(listOf(snap("google", listOf("GAMING"), 1)))
        assertEquals(DriftState.COLLECTING, r.state)
        assertNull(r.klDivergence)
    }

    @Test
    fun `identical baseline and latest yields zero drift`() {
        val snaps = listOf(
            snap("google", listOf("GAMING", "MEDICAL"), 1),
            snap("google", listOf("GAMING", "MEDICAL"), 2),
        )
        val r = metric.compute(snaps)
        assertEquals(DriftState.AVAILABLE, r.state)
        assertEquals(0.0, r.klDivergence!!, 1e-9)
    }

    @Test
    fun `a changed profile yields positive drift`() {
        val snaps = listOf(
            snap("google", listOf("GAMING"), 1),
            snap("google", listOf("COOKING", "TRAVEL", "FINANCE"), 2),
        )
        val r = metric.compute(snaps)
        assertEquals(DriftState.AVAILABLE, r.state)
        assertTrue("drift should be positive when the profile changes", r.klDivergence!! > 0.0)
    }

    @Test
    fun `kl is zero for identical sets and positive otherwise`() {
        assertEquals(0.0, metric.kl(setOf(CategoryPool.GAMING), setOf(CategoryPool.GAMING)), 1e-9)
        assertTrue(metric.kl(setOf(CategoryPool.GAMING), setOf(CategoryPool.COOKING)) > 0.0)
    }
}
