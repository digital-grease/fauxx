package com.fauxx.targeting.layer2

import android.content.Context
import android.util.Log
import com.fauxx.data.querybank.CategoryPool
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CategoryMapper"

/**
 * Maps raw ad platform category strings (e.g., "Video Games", "Software Development") to
 * [CategoryPool] enum values. Uses a JSON lookup table first, then falls back to fuzzy keyword
 * matching. Unknown strings are logged and skipped without crashing.
 */
@Singleton
class CategoryMapper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Raw platform string → CategoryPool name, loaded from assets. */
    private val exactMap: Map<String, String> by lazy { loadExactMap() }

    /**
     * Map a raw platform category string to a [CategoryPool] value.
     * Returns null and logs a warning if no match is found.
     */
    fun map(platformString: String): CategoryPool? {
        val normalized = platformString.trim()

        // 1. Exact match from JSON map
        val exactMatch = exactMap[normalized]
            ?: exactMap.entries.firstOrNull { it.key.equals(normalized, ignoreCase = true) }?.value
        if (exactMatch != null) {
            return runCatching { CategoryPool.valueOf(exactMatch) }.getOrNull()
        }

        // 2. Fuzzy keyword matching against CategoryPool names
        val lowerInput = normalized.lowercase()
        for (category in CategoryPool.values()) {
            val categoryWords = category.name.lowercase().replace("_", " ")
            if (lowerInput.contains(categoryWords) || categoryWords.contains(lowerInput)) {
                return category
            }
        }

        // 3. Keyword-based heuristics for common platform strings
        val heuristic = applyHeuristics(lowerInput)
        if (heuristic != null) return heuristic

        Log.d(TAG, "No CategoryPool match for platform string: '$platformString'")
        return null
    }

    /**
     * Map a collection of platform strings to a set of [CategoryPool] values.
     * Unmatched strings are skipped silently.
     */
    fun mapAll(platformStrings: Collection<String>): Set<CategoryPool> =
        platformStrings.mapNotNull { map(it) }.toSet()

    private fun applyHeuristics(lower: String): CategoryPool? = when {
        lower.contains("game") || lower.contains("gaming") -> CategoryPool.GAMING
        lower.contains("health") || lower.contains("medical") || lower.contains("doctor") -> CategoryPool.MEDICAL
        lower.contains("sport") || lower.contains("fitness") || lower.contains("exercise") -> CategoryPool.SPORTS
        lower.contains("travel") || lower.contains("vacation") || lower.contains("hotel") -> CategoryPool.TRAVEL
        lower.contains("food") || lower.contains("recipe") || lower.contains("restaurant") -> CategoryPool.FOOD
        lower.contains("tech") || lower.contains("software") || lower.contains("computer") -> CategoryPool.TECHNOLOGY
        lower.contains("fashion") || lower.contains("clothing") || lower.contains("apparel") -> CategoryPool.FASHION
        lower.contains("finance") || lower.contains("invest") || lower.contains("stock") -> CategoryPool.FINANCE
        lower.contains("parent") || lower.contains("baby") || lower.contains("child") -> CategoryPool.PARENTING
        lower.contains("auto") || lower.contains("car") || lower.contains("vehicle") -> CategoryPool.AUTOMOTIVE
        lower.contains("home") || lower.contains("garden") || lower.contains("house") -> CategoryPool.HOME_IMPROVEMENT
        lower.contains("music") || lower.contains("concert") || lower.contains("album") -> CategoryPool.MUSIC
        lower.contains("beauty") || lower.contains("cosmetic") || lower.contains("makeup") -> CategoryPool.BEAUTY
        lower.contains("cook") || lower.contains("baking") || lower.contains("kitchen") -> CategoryPool.COOKING
        lower.contains("real estate") || lower.contains("property") || lower.contains("mortgage") -> CategoryPool.REAL_ESTATE
        lower.contains("retire") || lower.contains("pension") || lower.contains("senior") -> CategoryPool.RETIREMENT
        lower.contains("legal") || lower.contains("lawyer") || lower.contains("attorney") -> CategoryPool.LEGAL
        lower.contains("farm") || lower.contains("agriculture") || lower.contains("crop") -> CategoryPool.AGRICULTURE
        lower.contains("educat") || lower.contains("academic") || lower.contains("college") -> CategoryPool.ACADEMIC
        lower.contains("pet") || lower.contains("dog") || lower.contains("cat") -> CategoryPool.PETS
        lower.contains("entertain") || lower.contains("movie") || lower.contains("film") -> CategoryPool.ENTERTAINMENT
        lower.contains("science") || lower.contains("research") || lower.contains("lab") -> CategoryPool.SCIENCE
        lower.contains("business") || lower.contains("entrepreneur") || lower.contains("startup") -> CategoryPool.BUSINESS
        else -> null
    }

    private fun loadExactMap(): Map<String, String> {
        return try {
            val json = context.assets.open("platform_category_map.json")
                .bufferedReader().readText()
            val type = object : TypeToken<Map<String, String>>() {}.type
            Gson().fromJson(json, type)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load platform_category_map.json", e)
            emptyMap()
        }
    }
}
