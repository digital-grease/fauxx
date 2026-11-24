package com.fauxx.data.location

import android.content.Context
import androidx.annotation.Keep
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Bundled database of 500+ city center coordinates worldwide, loaded from assets/city_coords.json.
 * Used by [FakeRouteGenerator] as starting points for synthetic location routes.
 */
@Singleton
class CityDatabase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val random: Random = Random.Default,
) {
    val cities: List<CityCoord> by lazy { loadCities() }

    /** Return a random city coordinate, optionally filtered by [regionHint]. */
    fun randomCity(regionHint: String? = null): CityCoord {
        val pool = if (regionHint != null) {
            cities.filter { it.region.contains(regionHint, ignoreCase = true) }
                .ifEmpty { cities }
        } else {
            cities
        }
        return pool.randomOrNull(random) ?: CityCoord("New York", 40.7128, -74.0060, "US_NORTHEAST")
    }

    /**
     * A random city that is [eligible], from [regionHint]'s cities when the hint names a region
     * the database has, otherwise from all cities. Null when no such city is eligible.
     *
     * Unlike [randomCity], this never falls back from a known region to other regions or to a
     * default city. Location Spoofing (#322 item 12) passes only cities with a point clear of
     * sensitive places, and skips the action rather than spoof outside the persona's region.
     */
    fun randomEligibleCity(regionHint: String?, eligible: (CityCoord) -> Boolean): CityCoord? {
        val inRegion = regionHint?.let { hint -> cities.filter { it.region.contains(hint, ignoreCase = true) } }
        return inRegion.orEmpty().ifEmpty { cities }.filter(eligible).randomOrNull(random)
    }

    private fun loadCities(): List<CityCoord> {
        return try {
            val json = context.assets.open("city_coords.json")
                .bufferedReader().readText()
            val type = object : TypeToken<List<CityCoord>>() {}.type
            Gson().fromJson(json, type)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load city_coords.json")
            listOf(CityCoord("New York", 40.7128, -74.0060, "US_NORTHEAST"))
        }
    }
}

/**
 * A single city entry with geographic coordinates.
 *
 * @Keep: without this, R8 in release builds renames the `name` / `lat` / `lng` /
 * `region` fields, and Gson's reflection-based deserialization produces blank
 * entries — LocationSpoofModule would silently fall back to the single hardcoded
 * "New York" default.
 */
@Keep
data class CityCoord(
    val name: String,
    val lat: Double,
    val lng: Double,
    val region: String = ""
)
