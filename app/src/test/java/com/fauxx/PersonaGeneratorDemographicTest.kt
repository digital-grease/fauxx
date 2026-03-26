package com.fauxx

import android.content.Context
import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.targeting.layer1.AgeRange
import com.fauxx.targeting.layer1.DemographicProfileDao
import com.fauxx.targeting.layer1.Gender
import com.fauxx.targeting.layer1.Profession
import com.fauxx.targeting.layer1.Region
import com.fauxx.targeting.layer1.UserDemographicProfile
import com.fauxx.targeting.layer3.PersonaGenerator
import com.fauxx.targeting.layer3.PersonaHistoryDao
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaGeneratorDemographicTest {

    private val historyDao: PersonaHistoryDao = mockk {
        coEvery { getRecentPersonas(any()) } returns emptyList()
    }

    private val context: Context = mockk(relaxed = true) {
        every { assets } returns mockk {
            every { open("persona_templates.json") } throws java.io.FileNotFoundException()
        }
    }

    @Test
    fun `persona rejected when matching user demographics on 2+ traits`() = runTest {
        val userProfile = UserDemographicProfile(
            ageRange = AgeRange.AGE_35_44,
            profession = Profession.ENGINEER,
            region = Region.US_MIDWEST
        )
        val demographicDao: DemographicProfileDao = mockk {
            coEvery { get() } returns userProfile
        }

        val generator = PersonaGenerator(context, historyDao, demographicDao)

        // Generate many personas and verify none match user on 2+ traits
        repeat(20) {
            val persona = generator.generate()
            val matchCount = countDemographicMatches(persona, userProfile)
            assertTrue(
                "Persona matched user on $matchCount traits: age=${persona.ageRange}, " +
                    "prof=${persona.profession}, region=${persona.region}",
                matchCount < 2
            )
        }
    }

    @Test
    fun `persona accepted when sufficiently different from user`() = runTest {
        val userProfile = UserDemographicProfile(
            ageRange = AgeRange.AGE_65_PLUS,
            profession = Profession.RETIRED,
            region = Region.US_SOUTHEAST
        )
        val demographicDao: DemographicProfileDao = mockk {
            coEvery { get() } returns userProfile
        }

        val generator = PersonaGenerator(context, historyDao, demographicDao)
        val persona = generator.generate()
        assertNotNull(persona)
        assertTrue(persona.name.isNotBlank())
    }

    @Test
    fun `graceful behavior when no user profile exists`() = runTest {
        val demographicDao: DemographicProfileDao = mockk {
            coEvery { get() } returns null
        }

        val generator = PersonaGenerator(context, historyDao, demographicDao)
        val persona = generator.generate()
        assertNotNull(persona)
        assertTrue(persona.name.isNotBlank())
        assertTrue(persona.interests.isNotEmpty())
    }

    private fun countDemographicMatches(
        persona: SyntheticPersona,
        profile: UserDemographicProfile
    ): Int {
        var count = 0
        if (profile.ageRange != null) {
            val userAge = when (profile.ageRange) {
                AgeRange.AGE_18_24 -> "18-24"
                AgeRange.AGE_25_34 -> "25-34"
                AgeRange.AGE_35_44 -> "35-44"
                AgeRange.AGE_45_54 -> "45-54"
                AgeRange.AGE_55_64 -> "55-64"
                AgeRange.AGE_65_PLUS -> "65+"
            }
            if (persona.ageRange == userAge) count++
        }
        if (profile.profession != null) {
            val userProf = when (profile.profession) {
                Profession.STUDENT -> "Student"
                Profession.TEACHER -> "Teacher"
                Profession.ENGINEER -> "Engineer"
                Profession.HEALTHCARE -> "Healthcare Worker"
                Profession.LEGAL -> "Legal"
                Profession.FINANCE_PROF -> "Business Professional"
                Profession.RETAIL -> "Retail Worker"
                Profession.TRADES -> "Trades"
                Profession.CREATIVE -> "Creative"
                Profession.RETIRED -> "Retired"
                Profession.HOMEMAKER -> "Homemaker"
                Profession.OTHER -> "Professional"
            }
            if (persona.profession == userProf) count++
        }
        if (profile.region != null && persona.region == profile.region.name) {
            count++
        }
        return count
    }
}
