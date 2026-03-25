package com.fauxx

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.targeting.layer2.AdversarialScraperLayer
import com.fauxx.targeting.layer2.PlatformProfileCache
import com.fauxx.targeting.layer2.PlatformProfileDao
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

    @Test
    fun `disabled layer returns all neutral weights`() = runTest {
        val profilesFlow = MutableStateFlow(emptyList<PlatformProfileCache>())
        val dao: PlatformProfileDao = mockk { every { observeAll() } returns profilesFlow }
        val layer = AdversarialScraperLayer(dao)
        layer.setEnabled(false)

        val weights = layer.getWeights().first()
        weights.values.forEach { w ->
            assertEquals("All weights should be 1.0 when disabled", 1.0f, w, 0.001f)
        }
    }

    @Test
    fun `confirmed categories get 0_05 weight`() = runTest {
        val categories = listOf("GAMING", "MEDICAL")
        val profile = PlatformProfileCache(
            platformName = "google",
            scrapedCategoriesJson = gson.toJson(categories),
            lastScraped = System.currentTimeMillis()
        )
        val profilesFlow = MutableStateFlow(listOf(profile))
        val dao: PlatformProfileDao = mockk { every { observeAll() } returns profilesFlow }
        val layer = AdversarialScraperLayer(dao)
        layer.setEnabled(true)

        val weights = layer.getWeights().first()
        assertEquals(0.05f, weights[CategoryPool.GAMING]!!, 0.001f)
        assertEquals(0.05f, weights[CategoryPool.MEDICAL]!!, 0.001f)
    }

    @Test
    fun `absent categories get 3_0 weight`() = runTest {
        val categories = listOf("GAMING")
        val profile = PlatformProfileCache(
            platformName = "google",
            scrapedCategoriesJson = gson.toJson(categories),
            lastScraped = System.currentTimeMillis()
        )
        val profilesFlow = MutableStateFlow(listOf(profile))
        val dao: PlatformProfileDao = mockk { every { observeAll() } returns profilesFlow }
        val layer = AdversarialScraperLayer(dao)
        layer.setEnabled(true)

        val weights = layer.getWeights().first()
        // Any category NOT in the scraped list should get 3.0
        assertEquals(3.0f, weights[CategoryPool.RETIREMENT]!!, 0.001f)
        assertEquals(3.0f, weights[CategoryPool.COOKING]!!, 0.001f)
    }

    @Test
    fun `stale data returns neutral weights`() = runTest {
        val categories = listOf("GAMING")
        val eightDaysAgo = System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000
        val profile = PlatformProfileCache(
            platformName = "google",
            scrapedCategoriesJson = gson.toJson(categories),
            lastScraped = eightDaysAgo
        )
        val profilesFlow = MutableStateFlow(listOf(profile))
        val dao: PlatformProfileDao = mockk { every { observeAll() } returns profilesFlow }
        val layer = AdversarialScraperLayer(dao)
        layer.setEnabled(true)

        val weights = layer.getWeights().first()
        // All stale data should fall back to neutral
        weights.values.forEach { w ->
            assertEquals("Stale data should produce neutral weights", 1.0f, w, 0.001f)
        }
    }

    @Test
    fun `invalid JSON categories are gracefully ignored`() = runTest {
        val profile = PlatformProfileCache(
            platformName = "google",
            scrapedCategoriesJson = "not valid json",
            lastScraped = System.currentTimeMillis()
        )
        val profilesFlow = MutableStateFlow(listOf(profile))
        val dao: PlatformProfileDao = mockk { every { observeAll() } returns profilesFlow }
        val layer = AdversarialScraperLayer(dao)
        layer.setEnabled(true)

        val weights = layer.getWeights().first()
        // Should fall back to neutral on parse error
        weights.values.forEach { w ->
            assertEquals("Invalid JSON should produce neutral weights", 1.0f, w, 0.001f)
        }
    }

    @Test
    fun `multiple platforms merge confirmed categories`() = runTest {
        val googleProfile = PlatformProfileCache(
            platformName = "google",
            scrapedCategoriesJson = gson.toJson(listOf("GAMING")),
            lastScraped = System.currentTimeMillis()
        )
        val facebookProfile = PlatformProfileCache(
            platformName = "facebook",
            scrapedCategoriesJson = gson.toJson(listOf("MEDICAL")),
            lastScraped = System.currentTimeMillis()
        )
        val profilesFlow = MutableStateFlow(listOf(googleProfile, facebookProfile))
        val dao: PlatformProfileDao = mockk { every { observeAll() } returns profilesFlow }
        val layer = AdversarialScraperLayer(dao)
        layer.setEnabled(true)

        val weights = layer.getWeights().first()
        assertEquals(0.05f, weights[CategoryPool.GAMING]!!, 0.001f)
        assertEquals(0.05f, weights[CategoryPool.MEDICAL]!!, 0.001f)
        // Others should still be boosted
        assertTrue(weights[CategoryPool.COOKING]!! == 3.0f)
    }
}
