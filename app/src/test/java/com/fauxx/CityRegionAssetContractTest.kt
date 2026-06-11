package com.fauxx

import android.content.Context
import com.fauxx.data.location.CityCoord
import com.fauxx.data.location.CityDatabase
import com.fauxx.targeting.layer1.Region
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E8 (#174) contract test for the city-region backfill: every bundled city must carry
 * region tokens that persona region hints can match, because
 * [CityDatabase.randomCity]'s substring filter silently falls back to ALL cities on an
 * empty match — exactly the dead-code trap (M3 spike, trap 1) the backfill fixes.
 * Regenerate with scripts/backfill_city_regions.py if this turns red.
 */
class CityRegionAssetContractTest {

    private val cities: List<CityCoord> by lazy {
        val type = object : TypeToken<List<CityCoord>>() {}.type
        Gson().fromJson(readAsset().decodeToString(), type)
    }

    // RUSSIA has no Region enum value but is the region string of the ru-locale
    // persona templates; UNBOUND is the sentinel for cities deliberately excluded
    // from persona location stories (uninhabited territories). The hint match is
    // plain substring.
    private val validTokens =
        Region.entries.map { it.name }.toSet() + "RUSSIA" + "UNBOUND"

    @Test
    fun `city count matches the shipped asset`() {
        assertEquals(806, cities.size)
    }

    @Test
    fun `every city has a non-empty region`() {
        cities.forEach { assertTrue("${it.name} has no region", it.region.isNotBlank()) }
    }

    @Test
    fun `every region token is a Region enum name or RUSSIA`() {
        cities.forEach { city ->
            city.region.split(" ").forEach { token ->
                assertTrue("${city.name}: unknown token $token", token in validTokens)
            }
        }
    }

    @Test
    fun `US cities carry exactly one US region token or the UNBOUND sentinel`() {
        val us = cities.filter { it.name.endsWith(", United States") }
        assertTrue(us.isNotEmpty())
        us.forEach { city ->
            val tokens = city.region.split(" ")
            assertTrue("${city.name}: ${city.region}", tokens.size == 1)
            val token = tokens.single()
            assertTrue(
                "${city.name}: ${city.region}",
                token.startsWith("US_") || token == "UNBOUND"
            )
        }
    }

    @Test
    fun `every region a shipped persona can carry matches at least one city`() {
        // Data-driven against the real persona sources: every region string a persona
        // can be generated with (templates for all locales + the E7 US distribution)
        // must be matchable, or that persona's location channel silently unbinds.
        // Region.OTHER is the documented exception: legacy EN templates carry it and
        // it intentionally falls back to the unfiltered world list.
        val personaRegions = personaSourceRegions() - "OTHER"
        assertTrue(personaRegions.size >= 15)
        personaRegions.forEach { hint ->
            assertTrue(
                "persona region $hint matches no city — that persona's location " +
                    "story would silently unbind",
                cities.any { it.region.contains(hint, ignoreCase = true) }
            )
        }
    }

    private fun personaSourceRegions(): Set<String> {
        val gson = Gson()
        val regions = mutableSetOf<String>()
        listOf(
            "persona_templates.json", "persona_templates/es.json",
            "persona_templates/fr.json", "persona_templates/ru.json"
        ).forEach { path ->
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            val templates: List<Map<String, Any>> =
                gson.fromJson(readAssetText(path), type)
            templates.mapNotNullTo(regions) { it["region"] as? String }
        }
        val distType = object : TypeToken<Map<String, Any>>() {}.type
        val dist: Map<String, Any> = gson.fromJson(
            readAssetText("persona_distribution.json"), distType
        )
        @Suppress("UNCHECKED_CAST")
        val dims = dist["dimensions"] as Map<String, List<String>>
        regions.addAll(dims.getValue("region"))
        return regions
    }

    @Test
    fun `every US persona region can be matched by at least one city`() {
        val usRegions = listOf(
            "US_NORTHEAST", "US_SOUTHEAST", "US_MIDWEST", "US_SOUTHWEST", "US_WEST"
        )
        usRegions.forEach { region ->
            assertTrue(
                "no city tagged $region — EN personas in that region would silently " +
                    "fall back to the full world list",
                cities.any { it.region == region }
            )
        }
    }

    @Test
    fun `randomCity honors persona region hints against the real asset`() {
        val db = cityDatabase(Random(42))

        repeat(50) {
            assertTrue(db.randomCity(regionHint = "US_WEST").region.contains("US_WEST"))
        }
        repeat(20) {
            val quebec = db.randomCity(regionHint = "QUEBEC")
            assertTrue(quebec.name, quebec.name in setOf("Montreal, Canada", "Quebec City, Canada"))
        }
        repeat(20) {
            assertTrue(db.randomCity(regionHint = "RUSSIA").name.endsWith(", Russia"))
        }
        // Unknown hints fall back to the WHOLE list, not to a single hardcoded city:
        // 100 draws must span many distinct regions.
        val fallbackRegions = (1..100).map { db.randomCity(regionHint = "NO_SUCH_REGION").region }.toSet()
        assertTrue(
            "unknown hint should draw from the full world list, saw only $fallbackRegions",
            fallbackRegions.size > 3
        )
    }

    private fun cityDatabase(random: Random): CityDatabase {
        val bytes = readAsset()
        val context = mockk<Context>(relaxed = true) {
            every { assets } returns mockk {
                every { open("city_coords.json") } answers { ByteArrayInputStream(bytes) }
            }
        }
        return CityDatabase(context, random)
    }

    private fun readAsset(): ByteArray = readAssetBytes("city_coords.json")

    private fun readAssetText(relativePath: String): String =
        readAssetBytes(relativePath).decodeToString()

    private fun readAssetBytes(relativePath: String): ByteArray {
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
