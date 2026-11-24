package com.fauxx.data.location

import android.content.Context
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Where Location Spoofing may put the decoy, so that no spoofed fix comes near a sensitive place
 * (#322 item 12).
 *
 * A broker that records a visit to a clinic, a place of worship or a shelter attaches that
 * inference to the device, and here the device is the real user's. The FTC's orders against the
 * location data brokers Gravy Analytics and Venntel (January 2025), Mobilewalla (January 2025)
 * and Kochava (May 2026) list the places whose visits brokers may no longer sell. Per the owner's
 * ruling, Fauxx keeps away from all of them, and from the sensitive purchases the crawl list also
 * avoids (#322 item 7): pharmacies, law offices, payday lenders, liquor stores and the like. The
 * asset's `categories` lists them all.
 *
 * `assets/location_anchors.json`, built by `scripts/build_location_anchors.py` from Overture Maps
 * and OpenStreetMap data, gives each city up to 12 points on its public main roads and pedestrian
 * streets, within 7.5 km of the city. Each point carries its clearance: its distance to the
 * nearest sensitive place, at least 180 m. A route that stays within [Anchor.reachM] of its point
 * keeps [BUFFER_M] from every sensitive place, because a distance to a set of places changes by
 * no more than the distance moved.
 *
 * A city is missing from the asset when its map data was too thin to trust, or when none of its
 * main roads is clear. Location Spoofing never uses such a city. If the asset is missing or
 * unreadable, no city is covered and Location Spoofing does nothing.
 *
 * The asset is bundled with the app. Nothing here contacts Overture or OpenStreetMap.
 */
@Singleton
class LocationAnchors internal constructor(private val load: () -> String) {

    @Inject
    constructor(@ApplicationContext context: Context) : this({
        context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
    })

    /** A point a spoofed route may start from, [clearanceM] metres from the nearest sensitive place. */
    data class Anchor(val lat: Double, val lng: Double, val clearanceM: Int) {
        /** How far a route may stray from this point and still keep [BUFFER_M] from every sensitive place. */
        val reachM: Double get() = (clearanceM - BUFFER_M).toDouble()
    }

    /** Each covered city's anchors, by [CityCoord.name]. Loaded on first use. */
    private val byCity: Map<String, List<Anchor>> by lazy {
        runCatching { parse(load()) }.getOrElse {
            Timber.w(it, "%s unusable, location spoofing disabled", ASSET_PATH)
            emptyMap()
        }
    }

    /** True when the asset could not be read, so no city is covered. */
    val loadFailed: Boolean get() = byCity.isEmpty()

    /** True when [city] has at least one anchor. */
    fun covers(city: CityCoord): Boolean = byCity[city.name]?.isNotEmpty() == true

    /** One of [city]'s anchors, or null if the city is not covered. */
    fun pick(city: CityCoord, random: Random): Anchor? = byCity[city.name]?.randomOrNull(random)

    companion object {
        const val ASSET_PATH = "location_anchors.json"

        /** No spoofed fix comes closer than this to a sensitive place (the owner's ruling). */
        const val BUFFER_M = 150

        /**
         * The anchors in an asset's `cities` object. An anchor is dropped if its coordinates are
         * not a valid fix, or if it leaves no room beyond [BUFFER_M].
         */
        internal fun parse(json: String): Map<String, List<Anchor>> {
            val cities = JsonParser.parseString(json).asJsonObject.getAsJsonObject("cities")
            return cities.entrySet().associate { (name, points) ->
                name to points.asJsonArray.mapNotNull { point ->
                    val p = point.asJsonArray
                    val anchor = Anchor(p[0].asDouble, p[1].asDouble, p[2].asInt)
                    anchor.takeIf {
                        it.lat in -90.0..90.0 && it.lng in -180.0..180.0 && it.clearanceM > BUFFER_M
                    }
                }
            }.filterValues { it.isNotEmpty() }
        }
    }
}
