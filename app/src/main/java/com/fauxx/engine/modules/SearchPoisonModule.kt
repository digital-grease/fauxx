package com.fauxx.engine.modules

import timber.log.Timber
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.db.LogMetadata
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
import com.fauxx.data.crawllist.DomainBlocklist
import com.fauxx.engine.webview.PhantomWebViewPool
import com.fauxx.engine.webview.SYNTHETIC_WEBVIEW_HEADERS
import com.fauxx.locale.AcceptLanguageVariants
import com.fauxx.network.UserAgentPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 *  - Yandex: `lang=<lang>` query parameter
 *
 * Returning a fully-built URL keeps engine-specific quirks isolated and makes locale
 * changes a single point of update.
 */
private data class SearchEngine(
    val name: String,
    val build: (encodedQuery: String, locale: SupportedLocale) -> String
)

private val SEARCH_ENGINES = listOf(
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
    },
    // Issue #24. Yandex broadens the engine pool — more engine diversity makes the
    // synthetic-traffic profile harder to fingerprint as bot activity, since real
    // users don't typically stick to a single SERP. yandex.com (the international
    // entry point) accepts `lang` for query language; results are then served from
    // the geographically nearest mirror.
    SearchEngine("yandex") { q, l ->
        "https://yandex.com/search/?text=$q&lang=${l.tag}"
    }
)

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
    private val webViewPool: PhantomWebViewPool,
    private val userAgentPool: UserAgentPool,
    private val blocklist: DomainBlocklist,
    private val demographicDao: DemographicProfileDao,
    private val customInterestMapper: CustomInterestMapper,
    private val queryBlocklist: QueryBlocklist,
    private val localeManager: LocaleManager,
    private val random: Random = Random.Default,
) : Module {

    override suspend fun start() {
        Timber.d("SearchPoisonModule started")
        webViewPool.initialize()
        // Guarantee a coherent Android-Chromium UA on the search path even when
        // FingerprintModule (the usual UA source) is disabled (issue #168).
        webViewPool.setUserAgentIfUnset(userAgentPool.randomChromiumAndroid())
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
        val query = if (random.nextFloat() < 0.60f) {
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
        val engine = SEARCH_ENGINES.random(random)
        val url = engine.build(encodedQuery, localeManager.currentLocale)
        // Engine name suffix surfaces which SERP each search actually hit, so the user
        // can verify newly-added engines (e.g. Yandex per #24) are actually firing.
        val detail = "[$category] $query · via ${engine.name}"

        // Defensive blocklist gate. The search path now runs through the WebView, so the
        // OkHttp BlocklistInterceptor no longer covers it (#165), and Android does NOT fire
        // shouldOverrideUrlLoading for a programmatic main-frame loadUrl (only
        // shouldInterceptRequest gates subresources). isUrlBlocked fails closed on an
        // unparseable URL or a failed blocklist load. SERP hosts are not blocklisted today;
        // this is defense-in-depth.
        if (blocklist.isUrlBlocked(url)) {
            Timber.w("Search URL gated by blocklist (engine=${engine.name})")
            return ActionLogEntity(
                actionType = ActionType.SEARCH_QUERY,
                category = category,
                detail = detail,
                success = false,
            )
        }

        // Route the search through the real Chromium WebView so the TLS handshake (JA3/JA4),
        // HTTP/2 SETTINGS, and header order are genuinely Chrome's and coherent with the
        // Chromium UA (#168/#169). Accept-Language is locale-coherent with the SERP hl/gl
        // params; Sec-GPC rides along via SYNTHETIC_WEBVIEW_HEADERS.
        val headers = SYNTHETIC_WEBVIEW_HEADERS +
            ("Accept-Language" to AcceptLanguageVariants.forLocale(localeManager.currentLocale, random))

        val webView = try {
            webViewPool.acquire()
        } catch (e: Exception) {
            Timber.w("WebView acquire failed for search: ${e.message}")
            null
        }
        var metadata: String? = null
        val success = if (webView == null) {
            false
        } else {
            try {
                withTimeoutOrNull(SEARCH_LOAD_TIMEOUT_MS) {
                    withContext(Dispatchers.Main) { webView.loadUrl(url, headers) }
                    delay(random.nextLong(2_000L, 8_000L))
                    // Read metadata on Main before release() wipes the document (issue #73).
                    metadata = withContext(Dispatchers.Main) {
                        webViewPool.captureMetadata(webView, url, LogMetadata.SEARCH_ENGINE to engine.name)
                    }
                    true
                } ?: false
            } catch (e: Exception) {
                Timber.w("Search WebView load failed: ${e.message}")
                false
            } finally {
                webViewPool.release(webView)
            }
        }

        return ActionLogEntity(
            actionType = ActionType.SEARCH_QUERY,
            category = category,
            detail = detail,
            metadata = metadata ?: LogMetadata.toJson(LogMetadata.SEARCH_ENGINE to engine.name),
            success = success,
        )
    }

    companion object {
        /** Bounds a wedged SERP render so it can't hold a pool slot (replaces the OkHttp readTimeout). */
        private const val SEARCH_LOAD_TIMEOUT_MS = 30_000L
    }
}
