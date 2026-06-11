package com.fauxx

import android.content.Context
import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
import com.fauxx.targeting.layer1.AgeRange
import com.fauxx.targeting.layer1.DemographicProfileDao
import com.fauxx.targeting.layer1.Profession
import com.fauxx.targeting.layer1.Region
import com.fauxx.targeting.layer1.UserDemographicProfile
import com.fauxx.targeting.layer3.DemographicCell
import com.fauxx.targeting.layer3.PersonaDistribution
import com.fauxx.targeting.layer3.PersonaGenerator
import com.fauxx.targeting.layer3.PersonaHistoryDao
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException

/**
 * E7 (#173): EN-locale personas joint-sample (age, profession, region) from the bundled
 * ACS PUMS distribution; non-EN locales never consult it; every existing gate still
 * applies to joint-sampled candidates.
 */
class PersonaGeneratorJointSamplingTest {

    private val historyDao: PersonaHistoryDao = mockk {
        coEvery { getRecentPersonas(any()) } returns emptyList()
    }

    private val noProfileDao: DemographicProfileDao = mockk {
        coEvery { get() } returns null
    }

    private fun localeManager(locale: SupportedLocale): LocaleManager =
        mockk(relaxed = true) { every { currentLocale } returns locale }

    /**
     * Context with the given distribution JSON (null = missing) and optionally EN
     * persona templates at the legacy path (null = no templates).
     */
    private fun contextWith(
        distributionJson: String?,
        enTemplatesJson: String? = null,
    ): Context = mockk(relaxed = true) {
        every { assets } returns mockk {
            every { open("persona_templates/en.json") } throws FileNotFoundException()
            every { open("persona_templates/es.json") } throws FileNotFoundException()
            if (enTemplatesJson == null) {
                every { open("persona_templates.json") } throws FileNotFoundException()
            } else {
                every { open("persona_templates.json") } answers {
                    ByteArrayInputStream(enTemplatesJson.toByteArray())
                }
            }
            if (distributionJson == null) {
                every { open(PersonaDistribution.ASSET_PATH) } throws FileNotFoundException()
            } else {
                every { open(PersonaDistribution.ASSET_PATH) } answers {
                    ByteArrayInputStream(distributionJson.toByteArray())
                }
            }
        }
    }

    // Two cells chosen to avoid the AGE_18_24/AGE_65_PLUS consistency rules, so candidate
    // rejection can only come from the joint-sampling path under test.
    private val twoCellJson = """{"version":1,"cells":[
        {"age":"AGE_25_34","profession":"ENGINEER","region":"US_WEST","weight":0.6},
        {"age":"AGE_45_54","profession":"TEACHER","region":"US_MIDWEST","weight":0.4}
    ]}"""

    @Test
    fun `EN personas use whole joint cells - traits never mix across cells`() = runTest {
        val context = contextWith(twoCellJson)
        val generator = PersonaGenerator(
            context, historyDao, noProfileDao, localeManager(SupportedLocale.EN),
            PersonaDistribution(context)
        )

        val allowed = setOf(
            Triple("AGE_25_34", "ENGINEER", "US_WEST"),
            Triple("AGE_45_54", "TEACHER", "US_MIDWEST")
        )
        repeat(30) {
            val p = generator.generate()
            assertTrue(
                "Demographics not from a single joint cell: " +
                    "age=${p.ageRange}, prof=${p.profession}, region=${p.region}",
                Triple(p.ageRange, p.profession, p.region) in allowed
            )
        }
    }

    @Test
    fun `sampled cell overrides template demographics - production EN configuration`() = runTest {
        // Production EN ships 89 templates AND the distribution; the E7 feature IS the
        // precedence `cell?.age ?: template?.ageRange` in buildPersona. Template
        // demographics are disjoint from both cells, so any template-derived value in
        // the output means the precedence regressed (the suite would otherwise stay
        // green with templates mocked away). Interests must still come from the
        // template — that half of the hybrid is unchanged by E7.
        val enTemplates = """[{
            "archetype": "decoy", "ageRange": "AGE_55_64", "profession": "LEGAL",
            "region": "US_SOUTHEAST", "interests": ["COOKING", "TRAVEL", "FITNESS"]
        }]"""
        val context = contextWith(twoCellJson, enTemplates)
        val generator = PersonaGenerator(
            context, historyDao, noProfileDao, localeManager(SupportedLocale.EN),
            PersonaDistribution(context)
        )

        val allowed = setOf(
            Triple("AGE_25_34", "ENGINEER", "US_WEST"),
            Triple("AGE_45_54", "TEACHER", "US_MIDWEST")
        )
        repeat(30) {
            val p = generator.generate()
            assertTrue(
                "Template demographics leaked past the sampled cell: " +
                    "age=${p.ageRange}, prof=${p.profession}, region=${p.region}",
                Triple(p.ageRange, p.profession, p.region) in allowed
            )
            assertEquals(
                "Interests should still come from the template",
                setOf(
                    com.fauxx.data.querybank.CategoryPool.COOKING,
                    com.fauxx.data.querybank.CategoryPool.TRAVEL,
                    com.fauxx.data.querybank.CategoryPool.FITNESS
                ),
                p.interests
            )
        }
    }

    @Test
    fun `EN falls back to enum-name pickers when distribution asset is missing`() = runTest {
        val context = contextWith(null)
        val generator = PersonaGenerator(
            context, historyDao, noProfileDao, localeManager(SupportedLocale.EN),
            PersonaDistribution(context)
        )

        val ageNames = AgeRange.entries.map { it.name }.toSet()
        val professionNames = Profession.entries.map { it.name }.toSet()
        repeat(20) {
            val p = generator.generate()
            assertTrue("ageRange not an enum name: ${p.ageRange}", p.ageRange in ageNames)
            assertTrue("profession not an enum name: ${p.profession}", p.profession in professionNames)
        }
    }

    @Test
    fun `non-EN locale never consults the distribution`() = runTest {
        val distribution = mockk<PersonaDistribution> {
            every { sample(any()) } returns
                DemographicCell("AGE_25_34", "ENGINEER", "US_WEST", 1.0)
        }
        val generator = PersonaGenerator(
            contextWith(null), historyDao, noProfileDao, localeManager(SupportedLocale.ES),
            distribution
        )

        repeat(10) { generator.generate() }
        verify(exactly = 0) { distribution.sample(any()) }
    }

    @Test
    fun `gate boundary - exactly 2 matching traits rejects, 1 matching trait accepts`() = runTest {
        val oneCellJson = """{"version":1,"cells":[
            {"age":"AGE_25_34","profession":"ENGINEER","region":"US_WEST","weight":1.0}
        ]}"""

        // 2 matches (age + profession; region differs): every candidate rejected -> fallback.
        val twoTraitDao: DemographicProfileDao = mockk {
            coEvery { get() } returns UserDemographicProfile(
                ageRange = AgeRange.AGE_25_34,
                profession = Profession.ENGINEER,
                region = Region.US_MIDWEST
            )
        }
        val context = contextWith(oneCellJson)
        val rejected = PersonaGenerator(
            context, historyDao, twoTraitDao, localeManager(SupportedLocale.EN),
            PersonaDistribution(context)
        ).generate()
        assertEquals("AGE_35_44", rejected.ageRange)

        // 1 match (age only): the cell passes the gate untouched.
        val oneTraitDao: DemographicProfileDao = mockk {
            coEvery { get() } returns UserDemographicProfile(
                ageRange = AgeRange.AGE_25_34,
                profession = Profession.TEACHER,
                region = Region.US_MIDWEST
            )
        }
        val context2 = contextWith(oneCellJson)
        val accepted = PersonaGenerator(
            context2, historyDao, oneTraitDao, localeManager(SupportedLocale.EN),
            PersonaDistribution(context2)
        ).generate()
        assertEquals("AGE_25_34", accepted.ageRange)
        assertEquals("ENGINEER", accepted.profession)
        assertEquals("US_WEST", accepted.region)
    }

    @Test
    fun `user-demographic gate still rejects joint-sampled candidates`() = runTest {
        // Single-cell distribution that always collides with the user's own profile on
        // all three traits: every attempt must be rejected, forcing the fallback persona.
        val oneCellJson = """{"version":1,"cells":[
            {"age":"AGE_25_34","profession":"ENGINEER","region":"US_WEST","weight":1.0}
        ]}"""
        val userProfileDao: DemographicProfileDao = mockk {
            coEvery { get() } returns UserDemographicProfile(
                ageRange = AgeRange.AGE_25_34,
                profession = Profession.ENGINEER,
                region = Region.US_WEST
            )
        }
        val context = contextWith(oneCellJson)
        val generator = PersonaGenerator(
            context, historyDao, userProfileDao, localeManager(SupportedLocale.EN),
            PersonaDistribution(context)
        )

        val p = generator.generate()
        assertEquals("AGE_35_44", p.ageRange)
        assertEquals("OTHER", p.profession)
        assertEquals("US_MIDWEST", p.region)
    }
}
