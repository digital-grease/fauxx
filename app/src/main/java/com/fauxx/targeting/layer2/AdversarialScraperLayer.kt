package com.fauxx.targeting.layer2

import com.fauxx.data.querybank.CategoryPool
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Weight for categories the platform has explicitly assigned to the user — suppress these. */
private const val CONFIRMED_WEIGHT = 0.05f

/** Weight for categories absent from the platform profile — boost these. */
private const val ABSENT_WEIGHT = 3.0f

/** Neutral weight used when scraper is disabled or data is stale. */
private const val NEUTRAL_WEIGHT = 1.0f

/** Maximum age of scrape data before it's considered stale (7 days). */
private const val STALE_THRESHOLD_MS = 7L * 24 * 60 * 60 * 1000

/**
 * Layer 2 of the Demographic Distancing Engine — adversarial scraper targeting.
 *
 * Reads cached ad-platform profiles from [PlatformProfileDao] and returns:
 * - 0.05 for categories the platform has confirmed interest in (strongly suppress)
 * - 3.0 for categories absent from all platform profiles (strongly boost)
 * - 1.0 if the scraper is disabled, data is stale, or no profiles are cached
 *
 * Falls back gracefully on any failure — never blocks the engine.
 */
@Singleton
class AdversarialScraperLayer @Inject constructor(
    private val dao: PlatformProfileDao
) {
    private val gson = Gson()
    private var enabled: Boolean = false

    /** Enable or disable this layer. Emits updated weights immediately. */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    /**
     * Emits the current Layer 2 weight map, recalculating reactively whenever cached
     * platform profiles change.
     */
    fun getWeights(): Flow<Map<CategoryPool, Float>> =
        dao.observeAll().map { profiles ->
            if (!enabled || profiles.isEmpty()) return@map neutralWeights()

            val now = System.currentTimeMillis()
            val confirmedCategories = mutableSetOf<CategoryPool>()

            for (profile in profiles) {
                if (now - profile.lastScraped > STALE_THRESHOLD_MS) continue
                val categories = parseCategories(profile.scrapedCategoriesJson)
                confirmedCategories.addAll(categories)
            }

            if (confirmedCategories.isEmpty()) return@map neutralWeights()

            CategoryPool.values().associateWith { category ->
                if (confirmedCategories.contains(category)) CONFIRMED_WEIGHT else ABSENT_WEIGHT
            }
        }

    private fun neutralWeights(): Map<CategoryPool, Float> =
        CategoryPool.values().associateWith { NEUTRAL_WEIGHT }

    private fun parseCategories(json: String): Set<CategoryPool> {
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            val names: List<String> = gson.fromJson(json, type)
            names.mapNotNull { runCatching { CategoryPool.valueOf(it) }.getOrNull() }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }
}
