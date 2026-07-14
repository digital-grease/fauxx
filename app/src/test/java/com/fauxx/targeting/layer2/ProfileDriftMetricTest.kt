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

    private fun snap(
        platform: String,
        cats: List<String>,
        at: Long,
        series: SnapshotSeries = SnapshotSeries.POISONED,
    ) =
        ProfileSnapshot(
            platformName = platform, source = SnapshotSource.IMPORT,
            scrapedCategoriesJson = gson.toJson(cats), capturedAt = at, series = series,
        )

    @Test
    fun `collecting until two snapshots exist for a platform`() {
        assertEquals(DriftState.COLLECTING, metric.compute(emptyList()).state)
        val r = metric.compute(listOf(snap("google", listOf("GAMING"), 1)))
        assertEquals(DriftState.COLLECTING, r.state)
        assertNull(r.klDivergence)
    }

    @Test
    fun `an empty imported profile reports NO_PROFILE not collecting`() {
        // #220: personalized ads off -> the export parses to zero categories, so a drift value can
        // never accrue. A single empty import must report NO_PROFILE (not "collecting…" forever)...
        val one = metric.compute(listOf(snap("google", emptyList(), 1)))
        assertEquals(DriftState.NO_PROFILE, one.state)
        assertNull(one.klDivergence)

        // ...and even two empty imports, which would otherwise compute a meaningless kl=0.00.
        val two = metric.compute(
            listOf(
                snap("google", emptyList(), 1),
                snap("google", emptyList(), 2),
            )
        )
        assertEquals(DriftState.NO_PROFILE, two.state)
        assertNull(two.klDivergence)
    }

    @Test
    fun `a non-empty single import is still COLLECTING not NO_PROFILE`() {
        // Guards the boundary: one real (non-empty) snapshot is genuinely collecting, awaiting a
        // second to compute drift — it must NOT be misreported as NO_PROFILE.
        val r = metric.compute(listOf(snap("google", listOf("GAMING"), 1)))
        assertEquals(DriftState.COLLECTING, r.state)
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

    // --- Poisoned-vs-control divergence (E3 #172) ---

    @Test
    fun `control divergence is COLLECTING when there is no control series`() {
        val r = metric.computeControlDivergence(
            listOf(snap("google", listOf("GAMING"), 1), snap("google", listOf("COOKING"), 2)),
        )
        assertEquals(DriftState.COLLECTING, r.state)
    }

    @Test
    fun `control divergence is COLLECTING with a control but no poisoned series`() {
        val r = metric.computeControlDivergence(
            listOf(snap("google", listOf("GAMING"), 1, SnapshotSeries.CONTROL)),
        )
        assertEquals(DriftState.COLLECTING, r.state)
    }

    @Test
    fun `identical latest poisoned and control yields zero divergence`() {
        val r = metric.computeControlDivergence(
            listOf(
                snap("google", listOf("GAMING", "MEDICAL"), 2, SnapshotSeries.POISONED),
                snap("google", listOf("GAMING", "MEDICAL"), 2, SnapshotSeries.CONTROL),
            ),
        )
        assertEquals(DriftState.AVAILABLE, r.state)
        assertEquals(0.0, r.klDivergence!!, 1e-9)
    }

    @Test
    fun `divergent poisoned and control yields positive divergence`() {
        val r = metric.computeControlDivergence(
            listOf(
                snap("google", listOf("GAMING"), 2, SnapshotSeries.POISONED),
                snap("google", listOf("COOKING", "TRAVEL"), 2, SnapshotSeries.CONTROL),
            ),
        )
        assertEquals(DriftState.AVAILABLE, r.state)
        assertTrue(r.klDivergence!! > 0.0)
    }

    @Test
    fun `control divergence uses the latest snapshot of each series`() {
        // Poisoned drifted to COOKING (latest), control stayed at GAMING — latest-of-each diverges.
        val r = metric.computeControlDivergence(
            listOf(
                snap("google", listOf("GAMING"), 1, SnapshotSeries.POISONED),
                snap("google", listOf("COOKING"), 3, SnapshotSeries.POISONED),
                snap("google", listOf("GAMING"), 2, SnapshotSeries.CONTROL),
            ),
        )
        assertEquals(DriftState.AVAILABLE, r.state)
        assertTrue(r.klDivergence!! > 0.0)
    }

    @Test
    fun `kl is zero for identical sets and positive otherwise`() {
        assertEquals(0.0, metric.kl(setOf(CategoryPool.GAMING), setOf(CategoryPool.GAMING)), 1e-9)
        assertTrue(metric.kl(setOf(CategoryPool.GAMING), setOf(CategoryPool.COOKING)) > 0.0)
    }

    // --- Weight-map KL overload (E4 #180 allocation budget) ---

    @Test
    fun `weight-map kl is zero for identical distributions`() {
        val w = CategoryPool.values().associateWith { 1f / CategoryPool.values().size }
        assertEquals(0.0, metric.kl(w, w), 1e-9)
    }

    @Test
    fun `weight-map kl is positive when distributions differ`() {
        val cats = CategoryPool.values()
        val p = cats.associateWith { 1f / cats.size }
        val q = p.toMutableMap().apply { put(CategoryPool.GAMING, 0.5f) }
        assertTrue(metric.kl(q, p) > 0.0)
    }

    @Test
    fun `weight-map kl stays finite with absent categories`() {
        val p = mapOf(CategoryPool.GAMING to 1f)
        val q = mapOf(CategoryPool.COOKING to 1f)
        val v = metric.kl(p, q)
        assertTrue(v.isFinite() && v > 0.0)
    }
}
