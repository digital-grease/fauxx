package com.fauxx

import android.content.Context
import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
import com.fauxx.targeting.layer1.AgeRange
import com.fauxx.targeting.layer1.DemographicProfileDao
import com.fauxx.targeting.layer1.Gender
import com.fauxx.targeting.layer1.Profession
import com.fauxx.targeting.layer1.Region
import com.fauxx.targeting.layer1.UserDemographicProfile
import com.fauxx.targeting.layer3.PersonaDistribution
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
            // Templates, and the E7 joint distribution, are all missing — the
            // generator falls back to its built-in random pickers, which is what
            // these tests want to exercise.
            every { open("persona_templates/en.json") } throws java.io.FileNotFoundException()
            every { open("persona_templates.json") } throws java.io.FileNotFoundException()
            every { open(PersonaDistribution.ASSET_PATH) } throws java.io.FileNotFoundException()
        }
    }

    private val distribution = PersonaDistribution(context)

    private val localeManager: LocaleManager = mockk(relaxed = true) {
        every { currentLocale } returns SupportedLocale.EN
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

        val generator = PersonaGenerator(context, historyDao, demographicDao, localeManager, distribution)

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

        val generator = PersonaGenerator(context, historyDao, demographicDao, localeManager, distribution)
        val persona = generator.generate()
        assertNotNull(persona)
        assertTrue(persona.name.isNotBlank())
    }

    @Test
    fun `graceful behavior when no user profile exists`() = runTest {
        val demographicDao: DemographicProfileDao = mockk {
            coEvery { get() } returns null
        }

        val generator = PersonaGenerator(context, historyDao, demographicDao, localeManager, distribution)
        val persona = generator.generate()
        assertNotNull(persona)
        assertTrue(persona.name.isNotBlank())
        assertTrue(persona.interests.isNotEmpty())
    }

    private fun countDemographicMatches(
        persona: SyntheticPersona,
        profile: UserDemographicProfile
    ): Int {
        // SyntheticPersona stores demographics as enum names (E7 canonicalization).
        var count = 0
        if (profile.ageRange != null && persona.ageRange == profile.ageRange.name) count++
        if (profile.profession != null && persona.profession == profile.profession.name) count++
        if (profile.region != null && persona.region == profile.region.name) count++
        return count
    }
}
