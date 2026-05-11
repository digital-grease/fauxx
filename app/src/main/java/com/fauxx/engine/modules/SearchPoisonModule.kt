package com.fauxx.engine.modules

import timber.log.Timber
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.model.ActionType
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.data.querybank.MarkovQueryGenerator
import com.fauxx.data.querybank.QueryBankManager
import com.fauxx.data.querybank.QueryBlocklist
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
import com.fauxx.targeting.layer1.CustomInterestMapper
import com.fauxx.targeting.layer1.DemographicProfileDao
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * One search-engine endpoint plus its locale-aware URL builder.
 *
 * Each engine localizes results via a different mechanism:
 *  - Google: `hl=<lang>&gl=<REGION>` query parameters
 *  - Bing: `setmkt=<lang>-<REGION>` query parameter
 *  - DuckDuckGo: `kl=<lang>-<region>` query parameter (lowercase region)
 *  - Yahoo: subdomain swap (`es.search.yahoo.com`, `fr.search.yahoo.com`)
 *
 * Returning a fully-built URL keeps engine-specific quirks isolated and makes locale
 * changes a single point of update.
 */
private data class SearchEngine(
    val name: String,
    val build: (encodedQuery: String, locale: SupportedLocale) -> String
)

private val BASE_SEARCH_ENGINES = listOf(
    SearchEngine("google") { q, l ->
        "https://www.google.com/search?q=$q&hl=${l.tag}&gl=${l.defaultRegion}"
    },
    SearchEngine("bing") { q, l ->
        "https://www.bing.com/search?q=$q&setmkt=${l.tag}-${l.defaultRegion}"
    },
    SearchEngine("duckduckgo") { q, l ->
        "https://duckduckgo.com/?q=$q&kl=${l.tag}-${l.defaultRegion.lowercase()}"
    },
    SearchEngine("yahoo") { q, l ->
        "https://${l.yahooSubdomainPrefix}search.yahoo.com/search?p=$q"
    }
)

private val RU_SEARCH_ENGINES = BASE_SEARCH_ENGINES + listOf(
    SearchEngine("yandex") { q, _ ->
        "https://yandex.ru/search/?text=$q&lr=213"
    },
    SearchEngine("mailru") { q, _ ->
        "https://go.mail.ru/search?q=$q"
    }
)

private fun searchEnginesFor(locale: SupportedLocale): List<SearchEngine> =
    if (locale == SupportedLocale.RU) RU_SEARCH_ENGINES else BASE_SEARCH_ENGINES

/**
 * Executes synthetic search queries across multiple search engines.
 * Follows 1-3 result links with random dwell time (2-30 seconds).
 *
 * Query selection is category-weighted via the ActionDispatcher. Search-engine URLs are
 * locale-aware via [LocaleManager]: in Spanish or French mode the `hl=`/`setmkt`/`kl`
 * parameters and the Yahoo subdomain switch so the dispatched query lands on the
 * region-localized SERP rather than the English default.
 *
 * **Safety**: final dispatch gate. Every query is checked through [QueryBlocklist]
 * one last time before the HTTP request is built. If a query reaches this point
 * and matches the blocklist, that represents an invariant violation (upstream
 * filters failed) and the cycle is DROPPED rather than dispatched — a missing
 * action is cheaper than a false user-signal (e.g. 988 crisis-line query that
 * could trigger a welfare check or insurance flag).
 */
@Singleton
class SearchPoisonModule @Inject constructor(
    private val queryBankManager: QueryBankManager,
    private val markovGenerator: MarkovQueryGenerator,
    private val profileRepo: PoisonProfileRepository,
    private val httpClient: OkHttpClient,
    private val demographicDao: DemographicProfileDao,
    private val customInterestMapper: CustomInterestMapper,
    private val queryBlocklist: QueryBlocklist,
    private val localeManager: LocaleManager
) : Module {

    override suspend fun start() {
        Timber.d("SearchPoisonModule started")
        injectCustomInterestSeeds()
    }

    /**
     * Read custom interests from the demographic profile, map them to categories,
     * and inject as seed phrases into the Markov generator.
     */
    private suspend fun injectCustomInterestSeeds() {
        markovGenerator.clearSeedPhrases()
        val profile = demographicDao.get() ?: return
        val customInterests = profile.getCustomInterests()
        if (customInterests.isEmpty()) return

        val mappings = customInterestMapper.mapAll(customInterests)
        for (mapping in mappings) {
            val category = mapping.category ?: continue
            markovGenerator.injectSeedPhrases(category, listOf(mapping.interest))
        }
        Timber.d("Injected ${mappings.count { it.category != null }} custom interest seed phrases")
    }

    override suspend fun stop() {
        Timber.d("SearchPoisonModule stopped")
    }

    override fun isEnabled(): Boolean = profileRepo.getProfile().searchPoisonEnabled

    override suspend fun onAction(category: CategoryPool): ActionLogEntity {
        // Use Markov generator 60% of the time for natural-looking queries
        val query = if (Random.nextFloat() < 0.60f) {
            markovGenerator.generate(category)
        } else {
            queryBankManager.randomQuery(category)
        }

        // Final safety gate. If a query reaches here matching the blocklist, upstream
        // filters (QueryBankManager load-time filter + MarkovQueryGenerator resample)
        // have failed — log the invariant violation and drop the cycle. Do NOT dispatch.
        if (query.isBlank() || queryBlocklist.isBlocked(query)) {
            Timber.e(
                "QueryBlocklist invariant violation — query '%s' escaped upstream " +
                    "guards; dropping action cycle (category=%s)",
                query,
                category
            )
            return ActionLogEntity(
                actionType = ActionType.SEARCH_QUERY,
                category = category,
                detail = "[BLOCKED] query suppressed by safety guard"
            )
        }

        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val locale = localeManager.currentLocale
        val engine = searchEnginesFor(locale).random()
        val url = engine.build(encodedQuery, locale)

        try {
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.d("Search request to ${engine.name} returned ${response.code}")
                }
            }
        } catch (e: Exception) {
            Timber.w("Search request failed: ${e.message}")
        }

        return ActionLogEntity(
            actionType = ActionType.SEARCH_QUERY,
            category = category,
            detail = "[$category] $query"
        )
    }
}
