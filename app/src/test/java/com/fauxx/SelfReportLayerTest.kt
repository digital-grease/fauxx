package com.fauxx

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.targeting.layer1.AgeRange
import com.fauxx.targeting.layer1.CustomInterestMapper
import com.fauxx.targeting.layer1.DemographicDistanceMap
import com.fauxx.targeting.layer1.DemographicProfileDao
import com.fauxx.targeting.layer1.Gender
import com.fauxx.targeting.layer1.Profession
import com.fauxx.targeting.layer1.SelfReportLayer
import com.fauxx.targeting.layer1.UserDemographicProfile
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfReportLayerTest {

    private val customInterestMapper: CustomInterestMapper = mockk(relaxed = true)

    @Test
    fun `null profile returns all neutral weights`() = runTest {
        val dao: DemographicProfileDao = mockk {
            every { observe() } returns flowOf(null)
        }
        val distanceMap: DemographicDistanceMap = mockk {
            every { getWeights(null) } returns CategoryPool.values().associateWith { 1.0f }
        }
        val layer = SelfReportLayer(dao, distanceMap, customInterestMapper)
        val weights = layer.getWeights().first()
        weights.values.forEach { w ->
            assertEquals("All weights should be neutral (1.0)", 1.0f, w, 0.001f)
        }
    }

    @Test
    fun `profile with known demographics returns close and distant weights`() = runTest {
        val profile = UserDemographicProfile(
            ageRange = AgeRange.AGE_18_24,
            profession = Profession.STUDENT
        )
        val expectedWeights = CategoryPool.values().associateWith { category ->
            when (category) {
                CategoryPool.ACADEMIC, CategoryPool.GAMING -> 0.15f  // close
                CategoryPool.RETIREMENT, CategoryPool.PARENTING -> 2.5f  // distant
                else -> 1.0f
            }
        }
        val dao: DemographicProfileDao = mockk {
            every { observe() } returns flowOf(profile)
        }
        val distanceMap: DemographicDistanceMap = mockk {
            every { getWeights(profile) } returns expectedWeights
        }
        val layer = SelfReportLayer(dao, distanceMap, customInterestMapper)
        val weights = layer.getWeights().first()

        assertEquals(0.15f, weights[CategoryPool.ACADEMIC]!!, 0.001f)
        assertEquals(0.15f, weights[CategoryPool.GAMING]!!, 0.001f)
        assertEquals(2.5f, weights[CategoryPool.RETIREMENT]!!, 0.001f)
        assertEquals(2.5f, weights[CategoryPool.PARENTING]!!, 0.001f)
    }

    @Test
    fun `partially filled profile still returns valid weights`() = runTest {
        // Only age range set, everything else null
        val profile = UserDemographicProfile(ageRange = AgeRange.AGE_65_PLUS)
        val expectedWeights = CategoryPool.values().associateWith { category ->
            when (category) {
                CategoryPool.RETIREMENT -> 0.15f  // close for 65+
                CategoryPool.GAMING -> 2.5f  // distant for 65+
                else -> 1.0f
            }
        }
        val dao: DemographicProfileDao = mockk {
            every { observe() } returns flowOf(profile)
        }
        val distanceMap: DemographicDistanceMap = mockk {
            every { getWeights(profile) } returns expectedWeights
        }
        val layer = SelfReportLayer(dao, distanceMap, customInterestMapper)
        val weights = layer.getWeights().first()

        assertEquals(0.15f, weights[CategoryPool.RETIREMENT]!!, 0.001f)
        assertEquals(2.5f, weights[CategoryPool.GAMING]!!, 0.001f)
        // Non-mapped categories should be neutral
        assertTrue(weights[CategoryPool.COOKING]!! == 1.0f)
    }
}
