package com.fauxx.targeting.layer1

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.targeting.layer2.CategoryMapper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of mapping a free-text custom interest to a [CategoryPool].
 *
 * @property interest The raw user-entered string.
 * @property category The resolved [CategoryPool], or null if unmappable.
 * @property confidence How reliable the mapping is — HIGH for exact/keyword match,
 *   LOW for heuristic, NONE when no match was found.
 */
data class InterestMapping(
    val interest: String,
    val category: CategoryPool?,
    val confidence: MappingConfidence
)

enum class MappingConfidence { HIGH, LOW, NONE }

/**
 * Maps user-entered free-text interests to [CategoryPool] values.
 * Delegates to [CategoryMapper]'s fuzzy matching and adds a confidence signal
 * so the UI can show the user what their interest mapped to and let them correct it.
 *
 * Unmapped interests are returned with [MappingConfidence.NONE] — the caller
 * (SelfReportLayer) decides how to handle them (light global suppression).
 */
@Singleton
class CustomInterestMapper @Inject constructor(
    private val categoryMapper: CategoryMapper
) {
    /**
     * Map a single free-text interest to a [CategoryPool].
     * Returns an [InterestMapping] with confidence level.
     */
    fun map(interest: String): InterestMapping {
        val trimmed = interest.trim()
        if (trimmed.isBlank()) return InterestMapping(trimmed, null, MappingConfidence.NONE)

        val category = categoryMapper.map(trimmed)
        val confidence = when {
            category == null -> MappingConfidence.NONE
            isHighConfidence(trimmed, category) -> MappingConfidence.HIGH
            else -> MappingConfidence.LOW
        }
        return InterestMapping(trimmed, category, confidence)
    }

    /** Map all custom interests, preserving order. */
    fun mapAll(interests: List<String>): List<InterestMapping> =
        interests.map { map(it) }

    /** Extract only the successfully mapped categories. */
    fun resolveCategories(interests: List<String>): Set<CategoryPool> =
        interests.mapNotNull { map(it).category }.toSet()

    /**
     * HIGH confidence when the interest string closely matches the category name
     * (e.g., "gaming" → GAMING, "real estate" → REAL_ESTATE).
     * LOW confidence for heuristic matches (e.g., "woodworking" → HOME_IMPROVEMENT).
     */
    private fun isHighConfidence(interest: String, category: CategoryPool): Boolean {
        val lowerInterest = interest.lowercase()
        val categoryWords = category.name.lowercase().replace("_", " ")
        return lowerInterest.contains(categoryWords) || categoryWords.contains(lowerInterest)
    }
}
