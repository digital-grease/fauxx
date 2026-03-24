package com.fauxx.data.location

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CityDatabase"

/**
 * Bundled database of 500+ city center coordinates worldwide, loaded from assets/city_coords.json.
 * Used by [FakeRouteGenerator] as starting points for synthetic location routes.
 */
@Singleton
class CityDatabase @Inject constructor(
    @ApplicationContext private val context: Context
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
        return pool.randomOrNull() ?: CityCoord("New York", 40.7128, -74.0060, "US_NORTHEAST")
    }

    private fun loadCities(): List<CityCoord> {
        return try {
            val json = context.assets.open("city_coords.json")
                .bufferedReader().readText()
            val type = object : TypeToken<List<CityCoord>>() {}.type
            Gson().fromJson(json, type)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load city_coords.json", e)
            listOf(CityCoord("New York", 40.7128, -74.0060, "US_NORTHEAST"))
        }
    }
}

/** A single city entry with geographic coordinates. */
data class CityCoord(
    val name: String,
    val lat: Double,
    val lng: Double,
    val region: String = ""
)
