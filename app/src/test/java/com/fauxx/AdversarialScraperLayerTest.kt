package com.fauxx

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.targeting.layer2.AdversarialScraperLayer
import com.fauxx.targeting.layer2.PlatformProfileCache
import com.fauxx.targeting.layer2.PlatformProfileDao
import com.fauxx.targeting.layer2.ProfileDriftCalculator
import com.fauxx.targeting.layer2.ProfileSnapshot
import com.fauxx.targeting.layer2.ProfileSnapshotDao
import com.fauxx.targeting.layer2.SnapshotSource
import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdversarialScraperLayerTest {

    private val gson = Gson()

    /** Build a layer with a real drift calculator and a snapshot DAO returning [snapshots]. */
    private fun layer(
        dao: PlatformProfileDao,
        snapshots: List<ProfileSnapshot> = emptyList(),
    ): AdversarialScraperLayer {
        val snapshotDao: ProfileSnapshotDao =
            mockk { every { observeAll() } returns MutableStateFlow(snapshots) }
        return AdversarialScraperLayer(dao, snapshotDao, ProfileDriftCalculator())
    }

    private fun daoWith(vararg profiles: PlatformProfileCache): PlatformProfileDao =
        mockk { every { observeAll() } returns MutableStateFlow(profiles.toList()) }

    private fun profile(platform: String, categories: List<String>, lastScraped: Long = System.currentTimeMillis()) =
        PlatformProfileCache(platform, gson.toJson(categories), lastScraped)

    private fun snapshot(platform: String, categories: List<String>, at: Long) =
        ProfileSnapshot(
            id = at, platformName = platform, source = SnapshotSource.IMPORT,
            scrapedCategoriesJson = gson.toJson(categories), capturedAt = at,
        )

    @Test
    fun `disabled layer returns all neutral weights`() = runTest {
        val layer = layer(daoWith())
        layer.setEnabled(false)
        layer.getWeights().first().values.forEach { w ->
            assertEquals("All weights should be 1.0 when disabled", 1.0f, w, 0.001f)
        }
    }

    @Test
    fun `confirmed categories get 0_05 weight`() = runTest {
        val layer = layer(daoWith(profile("google", listOf("GAMING", "MEDICAL"))))
        layer.setEnabled(true)
        val weights = layer.getWeights().first()
        assertEquals(0.05f, weights[CategoryPool.GAMING]!!, 0.001f)
        assertEquals(0.05f, weights[CategoryPool.MEDICAL]!!, 0.001f)
    }

    @Test
    fun `absent categories get 3_0 weight`() = runTest {
        val layer = layer(daoWith(profile("google", listOf("GAMING"))))
        layer.setEnabled(true)
        val weights = layer.getWeights().first()
        assertEquals(3.0f, weights[CategoryPool.RETIREMENT]!!, 0.001f)
        assertEquals(3.0f, weights[CategoryPool.COOKING]!!, 0.001f)
    }

    @Test
    fun `stale data returns neutral weights`() = runTest {
        val eightDaysAgo = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000
        val layer = layer(daoWith(profile("google", listOf("GAMING"), lastScraped = eightDaysAgo)))
        layer.setEnabled(true)
        layer.getWeights().first().values.forEach { w ->
            assertEquals("Stale data should produce neutral weights", 1.0f, w, 0.001f)
        }
    }

    @Test
    fun `invalid JSON categories are gracefully ignored`() = runTest {
        val layer = layer(daoWith(PlatformProfileCache("google", "not valid json", System.currentTimeMillis())))
        layer.setEnabled(true)
        layer.getWeights().first().values.forEach { w ->
            assertEquals("Invalid JSON should produce neutral weights", 1.0f, w, 0.001f)
        }
    }

    @Test
    fun `multiple platforms merge confirmed categories`() = runTest {
        val layer = layer(daoWith(profile("google", listOf("GAMING")), profile("facebook", listOf("MEDICAL"))))
        layer.setEnabled(true)
        val weights = layer.getWeights().first()
        assertEquals(0.05f, weights[CategoryPool.GAMING]!!, 0.001f)
        assertEquals(0.05f, weights[CategoryPool.MEDICAL]!!, 0.001f)
        assertTrue(weights[CategoryPool.COOKING]!! == 3.0f)
    }

    @Test
    fun `a category present in both recent snapshots is pushed harder`() = runTest {
        // GAMING appears in both of google's two most recent snapshots (sticky = not budging).
        // MEDICAL is confirmed (in the latest cache) but not sticky -> standard suppression.
        val snaps = listOf(
            snapshot("google", listOf("GAMING"), at = 1_000),
            snapshot("google", listOf("GAMING"), at = 2_000),
        )
        val layer = layer(daoWith(profile("google", listOf("GAMING", "MEDICAL"))), snaps)
        layer.setEnabled(true)
        val weights = layer.getWeights().first()
        assertEquals("sticky confirmed category is pushed harder", 0.02f, weights[CategoryPool.GAMING]!!, 0.001f)
        assertEquals("non-sticky confirmed stays at standard suppression", 0.05f, weights[CategoryPool.MEDICAL]!!, 0.001f)
    }

    @Test
    fun `a single snapshot does not trigger sticky weighting`() = runTest {
        val snaps = listOf(snapshot("google", listOf("GAMING"), at = 1_000))
        val layer = layer(daoWith(profile("google", listOf("GAMING"))), snaps)
        layer.setEnabled(true)
        // Only one snapshot -> no drift comparison -> standard 0.05, not 0.02.
        assertEquals(0.05f, layer.getWeights().first()[CategoryPool.GAMING]!!, 0.001f)
    }
}
