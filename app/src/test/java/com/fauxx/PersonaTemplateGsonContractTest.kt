package com.fauxx

import android.content.Context
import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
import com.fauxx.targeting.layer1.DemographicProfileDao
import com.fauxx.targeting.layer3.PersonaDistribution
import com.fauxx.targeting.layer3.PersonaGenerator
import com.fauxx.targeting.layer3.PersonaHistoryDao
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Locks the Gson contract for `persona_templates/{locale}.json`. Issue #49: in
 * release builds R8 stripped `PersonaTemplate`'s field names, Gson returned
 * `LinkedTreeMap`-backed objects, and `buildPersona()` threw `ClassCastException`
 * the first time it touched `template.region`. The whole rotation method then
 * silently fell back to neutral weights for the rest of the session.
 *
 * This test exercises the full deserialize-then-read-fields path against a real
 * persona-templates JSON file. JVM unit tests don't run R8 so this won't
 * directly catch a missing `@Keep`, but it does lock against the *adjacent*
 * regression modes — schema drift, JSON-key rename, file removal, Region-enum
 * mismatch — and serves as the canary that should turn red the next time
 * someone refactors this loader.
 */
class PersonaTemplateGsonContractTest {

    @Test
    fun `loading real persona_templates es json yields template-driven personas`() = runTest {
        // Use ES because:
        //  (a) it's at the locale-keyed path `persona_templates/es.json` (the
        //      modern layout), exercising the same code path EN/FR will go
        //      through once EN migrates off the legacy flat file, and
        //  (b) ES templates use regions like "SPAIN" that are guaranteed not
        //      to appear in PersonaGenerator's hardcoded fallback list, so a
        //      single template-driven generation is provable evidence that
        //      Gson read the template correctly.
        val templatesJson = readAssetFile("persona_templates/es.json")
        val context = mockk<Context>(relaxed = true) {
            every { assets } returns mockk {
                every { open("persona_templates/es.json") } answers {
                    ByteArrayInputStream(templatesJson)
                }
            }
        }
        val historyDao: PersonaHistoryDao = mockk {
            coEvery { getRecentPersonas(any()) } returns emptyList()
        }
        val demographicDao: DemographicProfileDao = mockk {
            coEvery { get() } returns null
        }
        val localeManager: LocaleManager = mockk(relaxed = true) {
            every { currentLocale } returns SupportedLocale.ES
        }

        // The ES locale never consults the joint distribution (PersonaGenerator gates
        // sampling on EN), and PersonaDistribution loads lazily on first sample(), so
        // the strict assets mock is never asked for persona_distribution.json here.
        val generator = PersonaGenerator(
            context, historyDao, demographicDao, localeManager, PersonaDistribution(context)
        )

        // PersonaGenerator.pickRegion() (the no-template fallback) only emits
        // values from this hardcoded set. ES templates exclusively use ES/LatAm
        // regions — none overlap. So any non-fallback region in the sample is
        // proof that Gson deserialized PersonaTemplate correctly *and*
        // `template.region` was readable as a String at access time.
        val fallbackRegions = setOf(
            "US_NORTHEAST", "US_SOUTHEAST", "US_MIDWEST", "US_SOUTHWEST",
            "US_WEST", "CANADA", "UK", "WESTERN_EUROPE"
        )
        val sampleRegions = (1..50).map { generator.generate().region }

        assertTrue(
            "All 50 generated personas used fallback regions ($fallbackRegions). " +
                "This means PersonaGenerator never read a region from a real " +
                "persona_templates entry — likely because Gson deserialization " +
                "silently produced LinkedTreeMaps (R8 stripped @Keep on " +
                "PersonaTemplate), or persona_templates/es.json regressed.",
            sampleRegions.any { it !in fallbackRegions }
        )
    }

    private fun readAssetFile(relativePath: String): ByteArray {
        // :app:testFullDebugUnitTest runs with cwd at the app module root.
        val candidates = listOf(
            File("src/main/assets/$relativePath"),
            File("app/src/main/assets/$relativePath")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("Could not locate $relativePath relative to ${File(".").absolutePath}")
        return file.readBytes()
    }
}
