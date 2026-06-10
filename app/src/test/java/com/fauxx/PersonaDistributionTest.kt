package com.fauxx

import android.content.Context
import com.fauxx.targeting.layer3.PersonaDistribution
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import kotlin.random.Random

class PersonaDistributionTest {

    private fun distributionWith(json: String?): PersonaDistribution {
        val context = mockk<Context>(relaxed = true) {
            every { assets } returns mockk {
                if (json == null) {
                    every { open(PersonaDistribution.ASSET_PATH) } throws FileNotFoundException()
                } else {
                    every { open(PersonaDistribution.ASSET_PATH) } answers {
                        ByteArrayInputStream(json.toByteArray())
                    }
                }
            }
        }
        return PersonaDistribution(context)
    }

    private fun cellJson(age: String, prof: String, region: String, weight: Double) =
        """{"age":"$age","profession":"$prof","region":"$region","weight":$weight}"""

    @Test
    fun `sample returns null when asset missing`() {
        assertNull(distributionWith(null).sample(Random(1)))
    }

    @Test
    fun `sample returns null for malformed json`() {
        assertNull(distributionWith("not json at all {").sample(Random(1)))
    }

    @Test
    fun `sample returns null when no cells are usable`() {
        val json = """{"version":1,"cells":[
            ${cellJson("AGE_999", "STUDENT", "US_WEST", 0.5)},
            ${cellJson("AGE_25_34", "ASTRONAUT", "US_WEST", 0.3)},
            ${cellJson("AGE_25_34", "STUDENT", "UK", 0.1)},
            ${cellJson("AGE_25_34", "STUDENT", "US_WEST", 0.0)}
        ]}"""
        assertNull(distributionWith(json).sample(Random(1)))
    }

    @Test
    fun `invalid cells are dropped while valid cells remain samplable`() {
        val json = """{"version":1,"cells":[
            ${cellJson("AGE_25_34", "ASTRONAUT", "US_WEST", 0.9)},
            ${cellJson("AGE_25_34", "STUDENT", "SPAIN", 0.9)},
            ${cellJson("AGE_45_54", "TEACHER", "US_MIDWEST", 0.1)}
        ]}"""
        val distribution = distributionWith(json)
        repeat(50) {
            val cell = distribution.sample(Random(it))
            assertNotNull(cell)
            assertEquals("AGE_45_54", cell!!.age)
            assertEquals("TEACHER", cell.profession)
            assertEquals("US_MIDWEST", cell.region)
        }
    }

    @Test
    fun `sampling frequency tracks cell weights`() {
        val json = """{"version":1,"cells":[
            ${cellJson("AGE_25_34", "ENGINEER", "US_WEST", 0.9)},
            ${cellJson("AGE_65_PLUS", "RETIRED", "US_MIDWEST", 0.1)}
        ]}"""
        val distribution = distributionWith(json)
        val random = Random(42)
        val draws = 2000
        val heavy = (1..draws).count { distribution.sample(random)!!.profession == "ENGINEER" }
        val fraction = heavy.toFloat() / draws
        assertTrue(
            "Expected ~0.9 of draws from the 0.9-weight cell, got $fraction",
            fraction in 0.85f..0.95f
        )
    }

    @Test
    fun `weights that do not sum to one are normalized by total weight`() {
        // 3.0 vs 1.0: if sample() dropped its totalWeight scaling (correct exactly when
        // weights sum to 1), the heavy cell would be drawn ~100% instead of ~75%.
        val json = """{"version":1,"cells":[
            ${cellJson("AGE_25_34", "ENGINEER", "US_WEST", 3.0)},
            ${cellJson("AGE_65_PLUS", "RETIRED", "US_MIDWEST", 1.0)}
        ]}"""
        val distribution = distributionWith(json)
        val random = Random(13)
        val draws = 2000
        val heavy = (1..draws).count { distribution.sample(random)!!.profession == "ENGINEER" }
        val fraction = heavy.toFloat() / draws
        assertTrue(
            "Expected ~0.75 of draws from the 3.0-weight cell, got $fraction",
            fraction in 0.70f..0.80f
        )
    }

    @Test
    fun `null cell elements are tolerated`() {
        val json = """{"version":1,"cells":[
            null,
            ${cellJson("AGE_45_54", "TEACHER", "US_MIDWEST", 1.0)}
        ]}"""
        val cell = distributionWith(json).sample(Random(1))
        assertNotNull(cell)
        assertEquals("TEACHER", cell!!.profession)
    }

    @Test
    fun `sampled traits always co-occur as a whole cell`() {
        val json = """{"version":1,"cells":[
            ${cellJson("AGE_18_24", "STUDENT", "US_WEST", 0.5)},
            ${cellJson("AGE_65_PLUS", "RETIRED", "US_MIDWEST", 0.5)}
        ]}"""
        val distribution = distributionWith(json)
        val allowed = setOf(
            Triple("AGE_18_24", "STUDENT", "US_WEST"),
            Triple("AGE_65_PLUS", "RETIRED", "US_MIDWEST")
        )
        val random = Random(7)
        repeat(200) {
            val cell = distribution.sample(random)!!
            assertTrue(
                "Cross-cell trait mix: $cell",
                Triple(cell.age, cell.profession, cell.region) in allowed
            )
        }
    }

    /**
     * Contract test against the REAL bundled artifact, mirroring
     * [PersonaTemplateGsonContractTest]: locks the loader <-> asset schema (key names,
     * enum-name values, positive weights) so schema drift in either the build script or
     * the loader turns this red. JVM tests don't run R8, so @Keep regressions still need
     * release smoke testing.
     */
    @Test
    fun `real persona_distribution asset loads and samples valid enum names`() {
        val real = readAssetFile(PersonaDistribution.ASSET_PATH).decodeToString()
        val distribution = distributionWith(real)
        val random = Random(99)
        repeat(100) {
            val cell = distribution.sample(random)
            assertNotNull("Real asset failed to load or had no usable cells", cell)
            // valueOf throws (failing the test) if the artifact carries unknown names.
            com.fauxx.targeting.layer1.AgeRange.valueOf(cell!!.age)
            com.fauxx.targeting.layer1.Profession.valueOf(cell.profession)
            com.fauxx.targeting.layer1.Region.valueOf(cell.region)
            assertTrue(cell.region.startsWith("US_"))
            assertTrue(cell.weight > 0.0)
        }
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
