package com.fauxx.engine.modules

import timber.log.Timber
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.location.CityDatabase
import com.fauxx.data.model.ActionType
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.data.querybank.QueryBankManager
import com.fauxx.engine.PoisonProfileRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Play Store-safe alternative to LocationSpoofModule.
 *
 * Instead of injecting mock GPS coordinates (which requires developer mode and violates
 * Play Store policy), this module poisons location inference by generating search queries
 * and HTTP requests that suggest the user is in a different geographic area.
 *
 * Techniques:
 * - Searches for local businesses, weather, and news in random distant cities
 * - Visits regional news sites and city-specific pages
 * - Generates queries like "restaurants near [city]", "weather in [city]", "[city] local news"
 */
@Singleton
class LocationSignalModule @Inject constructor(
    private val cityDatabase: CityDatabase,
    private val queryBankManager: QueryBankManager,
    private val profileRepo: PoisonProfileRepository,
    private val httpClient: OkHttpClient
) : Module {

    override suspend fun start() {
        Timber.d("LocationSignalModule started")
    }

    override suspend fun stop() {}

    override fun isEnabled(): Boolean = profileRepo.getProfile().locationSpoofEnabled

    override suspend fun onAction(category: CategoryPool): ActionLogEntity {
        val city = cityDatabase.randomCity()
        val queryTemplate = LOCATION_QUERY_TEMPLATES.random()
        val query = queryTemplate.replace("{city}", city.name)

        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val engine = SEARCH_ENGINES.random()
        val url = "$engine$encodedQuery"

        try {
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.d("Location signal search returned ${response.code}")
                }
            }
        } catch (e: Exception) {
            Timber.w("Location signal request failed: ${e.message}")
        }

        return ActionLogEntity(
            actionType = ActionType.SEARCH_QUERY,
            category = category,
            detail = "Location signal: $query (${city.region})"
        )
    }

    companion object {
        private val SEARCH_ENGINES = listOf(
            "https://www.google.com/search?q=",
            "https://www.bing.com/search?q=",
            "https://duckduckgo.com/?q=",
            "https://search.yahoo.com/search?p="
        )

        private val LOCATION_QUERY_TEMPLATES = listOf(
            "restaurants near {city}",
            "weather in {city}",
            "{city} local news",
            "things to do in {city}",
            "{city} apartments for rent",
            "best coffee shops {city}",
            "{city} public library hours",
            "grocery stores near {city}",
            "{city} events this weekend",
            "{city} real estate listings",
            "gas prices {city}",
            "{city} school district ratings",
            "dentist near {city}",
            "{city} community college",
            "{city} public transit schedule",
            "parks near {city}",
            "{city} farmers market",
            "{city} gym membership",
            "auto repair {city}",
            "{city} pet stores"
        )
    }
}
