package com.fauxx.targeting.layer1

import com.fauxx.data.querybank.CategoryPool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Layer 1 of the Demographic Distancing Engine — optional self-report targeting.
 *
 * Reads [UserDemographicProfile] from Room and returns weights per [CategoryPool]:
 * - 0.15 for categories close to the user's demographics (suppress these)
 * - 2.5 for categories distant from the user's demographics (boost these)
 * - 1.0 for neutral categories or when no profile is set
 *
 * Falls back to all-1.0 if the user skipped onboarding.
 */
@Singleton
class SelfReportLayer @Inject constructor(
    private val dao: DemographicProfileDao,
    private val distanceMap: DemographicDistanceMap
) {
    /**
     * Emits the current Layer 1 weight map, recalculating reactively whenever the
     * user's demographic profile changes in the database.
     */
    fun getWeights(): Flow<Map<CategoryPool, Float>> =
        dao.observe().map { profile ->
            distanceMap.getWeights(profile)
        }
}
