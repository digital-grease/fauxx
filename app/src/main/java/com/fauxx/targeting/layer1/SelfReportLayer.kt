package com.fauxx.targeting.layer1

import com.fauxx.data.querybank.CategoryPool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Weight applied to categories the user explicitly selected or that mapped from custom interests. */
private const val SELF_REPORTED_CLOSE_WEIGHT = 0.15f

/**
 * Light suppression applied across ALL categories when a custom interest can't be mapped
 * to any [CategoryPool]. Conservative approach: if we don't know what it is, slightly
 * reduce all categories rather than risk boosting the user's actual interest.
 */
private const val UNMAPPED_GLOBAL_SUPPRESSION = 0.92f

/**
 * Layer 1 of the Demographic Distancing Engine — optional self-report targeting.
 *
 * Reads [UserDemographicProfile] from Room and returns weights per [CategoryPool]:
 * - 0.15 for categories close to the user's demographics (suppress these)
 * - 2.5 for categories distant from the user's demographics (boost these)
 * - 1.0 for neutral categories or when no profile is set
 *
 * Additionally incorporates:
 * - Chip-selected interests from the profile (treated as CLOSE/suppress)
 * - Custom free-text interests mapped via [CustomInterestMapper] (mapped ones treated
 *   as CLOSE; unmapped ones apply light global suppression)
 *
 * Falls back to all-1.0 if the user skipped onboarding.
 */
@Singleton
class SelfReportLayer @Inject constructor(
    private val dao: DemographicProfileDao,
    private val distanceMap: DemographicDistanceMap,
    private val customInterestMapper: CustomInterestMapper
) {
    /**
     * Emits the current Layer 1 weight map, recalculating reactively whenever the
     * user's demographic profile changes in the database.
     */
    fun getWeights(): Flow<Map<CategoryPool, Float>> =
        dao.observe().map { profile ->
            val baseWeights = distanceMap.getWeights(profile)
            if (profile == null) return@map baseWeights
            applyInterests(baseWeights, profile)
        }

    /**
     * Overlay chip-selected and custom interests onto the demographic distance weights.
     * Interest-matched categories get suppressed (CLOSE weight) since they represent
     * the user's real interests.
     */
    private fun applyInterests(
        baseWeights: Map<CategoryPool, Float>,
        profile: UserDemographicProfile
    ): Map<CategoryPool, Float> {
        // Categories from chip selection
        val chipCategories = profile.getInterests()

        // Categories from custom free-text interests
        val customInterests = profile.getCustomInterests()
        val mappings = customInterestMapper.mapAll(customInterests)
        val mappedCategories = mappings.mapNotNull { it.category }.toSet()
        val hasUnmapped = mappings.any { it.confidence == MappingConfidence.NONE }

        val allCloseCategories = chipCategories + mappedCategories

        if (allCloseCategories.isEmpty() && !hasUnmapped) return baseWeights

        return baseWeights.mapValues { (category, weight) ->
            when {
                // Explicit interest → suppress (take the more suppressive of base vs interest)
                category in allCloseCategories -> minOf(weight, SELF_REPORTED_CLOSE_WEIGHT)
                // Unmapped custom interests → light global suppression
                hasUnmapped -> weight * UNMAPPED_GLOBAL_SUPPRESSION
                else -> weight
            }
        }
    }
}
