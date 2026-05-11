package com.fauxx.engine.modules

import timber.log.Timber
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.location.CityDatabase
import com.fauxx.data.model.ActionType
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.data.querybank.QueryBankManager
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
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
    private val httpClient: OkHttpClient,
    private val localeManager: LocaleManager
) : Module {

    override suspend fun start() {
        Timber.d("LocationSignalModule started")
    }

    override suspend fun stop() {}

    override fun isEnabled(): Boolean = profileRepo.getProfile().locationSpoofEnabled

    override suspend fun onAction(category: CategoryPool): ActionLogEntity {
        val city = cityDatabase.randomCity()
        val locale = localeManager.currentLocale
        val queryTemplate = templatesFor(locale).random()
        val query = queryTemplate.replace("{city}", city.name)

        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val engine = searchEnginesFor(locale).random()
        val url = engine.build(encodedQuery, locale)

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
        private data class SearchEngine(
            val name: String,
            val build: (encodedQuery: String, locale: SupportedLocale) -> String
        )

        private val BASE_SEARCH_ENGINES = listOf(
            SearchEngine("google") { q, l -> "https://www.google.com/search?q=$q&hl=${l.tag}&gl=${l.defaultRegion}" },
            SearchEngine("bing") { q, l -> "https://www.bing.com/search?q=$q&setmkt=${l.tag}-${l.defaultRegion}" },
            SearchEngine("duckduckgo") { q, l -> "https://duckduckgo.com/?q=$q&kl=${l.tag}-${l.defaultRegion.lowercase()}" },
            SearchEngine("yahoo") { q, l -> "https://${l.yahooSubdomainPrefix}search.yahoo.com/search?p=$q" }
        )

        private val RU_SEARCH_ENGINES = BASE_SEARCH_ENGINES + listOf(
            SearchEngine("yandex") { q, _ -> "https://yandex.ru/search/?text=$q&lr=213" },
            SearchEngine("mailru") { q, _ -> "https://go.mail.ru/search?q=$q" }
        )

        private fun searchEnginesFor(locale: SupportedLocale): List<SearchEngine> =
            if (locale == SupportedLocale.RU) RU_SEARCH_ENGINES else BASE_SEARCH_ENGINES

        private fun templatesFor(locale: SupportedLocale): List<String> =
            LOCATION_QUERY_TEMPLATES[locale] ?: LOCATION_QUERY_TEMPLATES.getValue(SupportedLocale.EN)

        private val LOCATION_QUERY_TEMPLATES: Map<SupportedLocale, List<String>> = mapOf(
            SupportedLocale.EN to listOf(
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
            ),
            SupportedLocale.RU to listOf(
                "рестораны рядом {city}",
                "погода в {city}",
                "{city} местные новости",
                "куда сходить в {city}",
                "аренда квартиры {city}",
                "лучшие кофейни {city}",
                "библиотеки {city} часы работы",
                "продуктовые магазины {city}",
                "события на выходных {city}",
                "недвижимость {city} объявления",
                "цены на бензин {city}",
                "школы {city} рейтинг",
                "стоматология {city}",
                "колледжи и университеты {city}",
                "общественный транспорт {city} расписание",
                "парки рядом {city}",
                "фермерский рынок {city}",
                "фитнес клуб {city}",
                "автосервис {city}",
                "зоомагазины {city}"
            )
        )
    }
}
