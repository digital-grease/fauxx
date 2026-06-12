package com.fauxx.engine.modules

import timber.log.Timber
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.db.LogMetadata
import com.fauxx.data.model.ActionType
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.data.querybank.GrammarQueryGenerator
import com.fauxx.data.querybank.QueryBankManager
import com.fauxx.data.querybank.QueryBlocklist
import com.fauxx.data.querybank.SearchRefinements
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
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
 * Executes synthetic search activity as intent-chain SESSIONS (E5 #175): one onAction
 * call runs a whole session — a goal query, then in-topic refinements built from
 * [SearchRefinements] (so successive queries narrow the same subject instead of being
 * independently category-random), then 1-2 organic result links followed from the final
 * SERP via [SerpLinkSelector]. Independent single queries were themselves a
 * bot-detectable shape: real search behavior moves through coherent narrowing sessions.
 *
 * Topic switches are session BOUNDARIES: the next onAction (usually a different
 * category from the dispatcher) starts a new chain, and the engine schedules that
 * transition through PoissonScheduler's cross-niche dwell handling, so a switch reads
 * as a deliberate pause rather than an instant pivot. Session size scales DOWN with the
 * configured intensity so long sessions can't starve the displayed actions/hour at
 * aggressive tiers (issue #76); the whole session is also wall-clock budgeted.
 *
 * Query selection is category-weighted via the ActionDispatcher. Search-engine URLs are
 * locale-aware via [LocaleManager]: in Spanish or French mode the `hl=`/`setmkt`/`kl`
 * parameters and the Yahoo subdomain switch so the dispatched query lands on the
 * region-localized SERP rather than the English default.
 *
 * **Safety**: final dispatch gate. EVERY query in a session — goal and each refinement —
 * is checked through [QueryBlocklist] one last time before its request is built. A
 * blocked goal drops the whole cycle (an invariant violation: upstream filters failed);
 * a blocked refinement is skipped without dispatch while the session continues, since
 * its goal already passed. A missing action is cheaper than a false user-signal (e.g.
 * a 988 crisis-line query that could trigger a welfare check or insurance flag).
 * Followed links are ALLOW-listed to curated crawl-corpus hosts (the denylist alone
 * cannot vouch for arbitrary live-SERP destinations, and a visited URL is itself a
 * profile entry), re-gated through [DomainBlocklist], navigated by clicking the SERP's
 * own anchor, and re-checked by PhantomWebViewClient on every navigation/redirect.
 * Documented residual: the allow-list guarantee is FIRST-HOP strong — once a curated
 * page is open, its own redirects/JS navigations are gated by the denylist only, the
 * same trust extended to every other module that loads corpus pages. The corpus
 * (reviewed editorial publishers, no open redirectors or UGC hosts) is the trust root.
 */
@Singleton
class SearchPoisonModule @Inject constructor(
    private val queryBankManager: QueryBankManager,
    private val grammarGenerator: GrammarQueryGenerator,
    private val profileRepo: PoisonProfileRepository,
    private val webViewPool: PhantomWebViewPool,
    private val userAgentPool: UserAgentPool,
    private val blocklist: DomainBlocklist,
    private val demographicDao: DemographicProfileDao,
    private val customInterestMapper: CustomInterestMapper,
    private val queryBlocklist: QueryBlocklist,
    private val localeManager: LocaleManager,
    private val crawlListManager: com.fauxx.data.crawllist.CrawlListManager,
    private val clock: com.fauxx.util.Clock = com.fauxx.util.SystemClockImpl(),
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
        grammarGenerator.clearSeedPhrases()
        val profile = demographicDao.get() ?: return
        val customInterests = profile.getCustomInterests()
        if (customInterests.isEmpty()) return

        val mappings = customInterestMapper.mapAll(customInterests)
        for (mapping in mappings) {
            val category = mapping.category ?: continue
            grammarGenerator.injectSeedPhrases(category, listOf(mapping.interest))
        }
        Timber.d("Injected ${mappings.count { it.category != null }} custom interest seed phrases")
    }

    override suspend fun stop() {
        Timber.d("SearchPoisonModule stopped")
    }

    override fun isEnabled(): Boolean = profileRepo.getProfile().searchPoisonEnabled

    override suspend fun onAction(category: CategoryPool): ActionLogEntity {
        // Session goal. Use the grammar generator 60% of the time for natural-looking, per-install
        // styled queries (E5 #179); the rest are raw corpus picks. The grammar wraps Markov/corpus
        // heads, so this keeps naturalness while breaking the shared-corpus fleet signature.
        val goal = if (random.nextFloat() < 0.60f) {
            grammarGenerator.generate(category)
        } else {
            queryBankManager.randomQuery(category)
        }

        // Final safety gate on the goal. If a query reaches here matching the blocklist,
        // upstream filters (QueryBankManager load-time filter + GrammarQueryGenerator
        // resample) have failed — log the invariant violation and drop the cycle.
        if (goal.isBlank() || queryBlocklist.isBlocked(goal)) {
            Timber.e(
                "QueryBlocklist invariant violation — query '%s' escaped upstream " +
                    "guards; dropping action cycle (category=%s)",
                goal,
                category
            )
            return ActionLogEntity(
                actionType = ActionType.SEARCH_QUERY,
                category = category,
                detail = "[BLOCKED] query suppressed by safety guard"
            )
        }

        val locale = localeManager.currentLocale
        val engine = SEARCH_ENGINES.random(random)
        val goalUrl = engine.build(java.net.URLEncoder.encode(goal, "UTF-8"), locale)
        // Engine name suffix surfaces which SERP each search actually hit, so the user
        // can verify newly-added engines (e.g. Yandex per #24) are actually firing.
        // The detail carries only the GOAL query (LogScrubber's export coarsening is
        // keyed to this exact shape); refinement texts are never logged.
        val detail = "[$category] $goal · via ${engine.name}"

        // Defensive blocklist gate. The search path runs through the WebView, so the
        // OkHttp BlocklistInterceptor no longer covers it (#165), and Android does NOT fire
        // shouldOverrideUrlLoading for a programmatic main-frame loadUrl (only
        // shouldInterceptRequest gates subresources). isUrlBlocked fails closed on an
        // unparseable URL or a failed blocklist load. SERP hosts are not blocklisted today;
        // this is defense-in-depth.
        if (blocklist.isUrlBlocked(goalUrl)) {
            Timber.w("Search URL gated by blocklist (engine=${engine.name})")
            return ActionLogEntity(
                actionType = ActionType.SEARCH_QUERY,
                category = category,
                detail = detail,
                success = false,
            )
        }

        // Session plan, scaled down at aggressive tiers so a long session can't starve
        // the displayed actions/hour (issue #76). The module can't see which transport
        // the engine is currently pacing on, so it sizes against the HIGHEST configured
        // tier (Wi-Fi or mobile) — the conservative direction for rate honesty.
        val profile = profileRepo.getProfile()
        val configuredCeiling = maxOf(
            profile.intensity.actionsPerHour,
            profile.mobileIntensity?.actionsPerHour ?: 0
        )
        val (maxRefinements, maxLinks) = sessionBounds(configuredCeiling)
        val refinements = if (maxRefinements > 0) {
            SearchRefinements.refine(goal, locale, random.nextInt(1, maxRefinements + 1), random)
        } else {
            emptyList()
        }

        // Route the session through the real Chromium WebView so the TLS handshake
        // (JA3/JA4), HTTP/2 SETTINGS, and header order are genuinely Chrome's and
        // coherent with the Chromium UA (#168/#169). Accept-Language is locale-coherent
        // with the SERP hl/gl params; Sec-GPC rides along via SYNTHETIC_WEBVIEW_HEADERS.
        // One WebView is held for the whole session: queries and clicks share history
        // and cookies, like one human tab.
        val headers = SYNTHETIC_WEBVIEW_HEADERS +
            ("Accept-Language" to AcceptLanguageVariants.forLocale(locale, random))

        val webView = try {
            webViewPool.acquire()
        } catch (e: Exception) {
            Timber.w("WebView acquire failed for search: ${e.message}")
            null
        }
        var metadata: String? = null
        var queriesDispatched = 0
        var linksFollowed = 0
        val success = if (webView == null) {
            false
        } else {
            try {
                val deadline = clock.currentTimeMillis() + SESSION_BUDGET_MS
                val goalOk = loadWithDwell(webView, goalUrl, headers, QUERY_DWELL_MS)
                if (goalOk) {
                    queriesDispatched = 1

                    // Snapshot page metadata NOW, while the document is the goal SERP:
                    // its title only echoes the goal (already in the detail line). A
                    // later snapshot would log the title of a refinement SERP or a
                    // followed third-party page — text this module promises never to
                    // log. Session counts are appended after the session ends.
                    metadata = withTimeoutOrNull(METADATA_TIMEOUT_MS) {
                        withContext(Dispatchers.Main) {
                            webViewPool.captureMetadata(
                                webView, goalUrl,
                                LogMetadata.SEARCH_ENGINE to engine.name,
                            )
                        }
                    }

                    for (refinement in refinements) {
                        if (clock.currentTimeMillis() > deadline) break
                        // Final safety gate on EVERY query in the chain. A blocked
                        // refinement is skipped (its goal already passed); query text is
                        // never logged from this path.
                        if (refinement.isBlank() || queryBlocklist.isBlocked(refinement)) {
                            Timber.w("Refinement suppressed by safety guard (category=%s)", category)
                            continue
                        }
                        val url = engine.build(java.net.URLEncoder.encode(refinement, "UTF-8"), locale)
                        if (blocklist.isUrlBlocked(url)) continue
                        if (loadWithDwell(webView, url, headers, QUERY_DWELL_MS)) {
                            queriesDispatched++
                        }
                    }

                    // Click 1-2 results on the final (most narrowed) SERP. Candidates
                    // are ALLOW-listed to curated crawl-corpus hosts — live SERPs
                    // surface arbitrary destinations and the denylist cannot vouch for
                    // them (a visited URL is itself a profile entry). The click runs
                    // the SERP's own anchor, so its instrumentation and referrer
                    // semantics are genuine, and PhantomWebViewClient re-gates the
                    // resulting navigation.
                    if (maxLinks > 0 && clock.currentTimeMillis() < deadline) {
                        val hrefs = collectSerpLinks(webView)
                        val links = SerpLinkSelector.select(
                            hrefs, random,
                            max = random.nextInt(1, maxLinks + 1),
                            isAllowed = crawlListManager::isCuratedHost,
                            isBlocked = blocklist::isUrlBlocked,
                        )
                        for (link in links) {
                            if (clock.currentTimeMillis() > deadline) break
                            if (clickResultLink(webView, link)) {
                                linksFollowed++
                                delay(random.nextLong(LINK_DWELL_MS.first, LINK_DWELL_MS.last + 1))
                            }
                        }
                    }

                    metadata = LogMetadata.append(
                        metadata,
                        LogMetadata.SESSION_QUERIES to queriesDispatched.toString(),
                        LogMetadata.SESSION_LINKS to linksFollowed.takeIf { it > 0 }?.toString(),
                    )
                }
                goalOk
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // engine stop/teardown must abort the session immediately
            } catch (e: Exception) {
                Timber.w("Search WebView session failed: ${e.message}")
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

    /** Load [url] in [webView] and dwell like a human scanning it. False on timeout/throw. */
    private suspend fun loadWithDwell(
        webView: android.webkit.WebView,
        url: String,
        headers: Map<String, String>,
        dwellMs: LongRange,
    ): Boolean = try {
        withTimeoutOrNull(SEARCH_LOAD_TIMEOUT_MS) {
            withContext(Dispatchers.Main) { webView.loadUrl(url, headers) }
            delay(random.nextLong(dwellMs.first, dwellMs.last + 1))
            true
        } ?: false
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w("Search WebView load failed: ${e.message}")
        false
    }

    /** Harvest anchor hrefs from the loaded SERP; empty on timeout or eval failure. */
    private suspend fun collectSerpLinks(webView: android.webkit.WebView): List<String> =
        withTimeoutOrNull(SERP_EXTRACT_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    webView.evaluateJavascript(SerpLinkSelector.COLLECT_LINKS_JS) { result ->
                        if (cont.isActive) cont.resume(SerpLinkSelector.parseHrefs(result))
                    }
                }
            }
        } ?: emptyList()

    /** Click the SERP anchor for [href]; true if the anchor was found and clicked. */
    private suspend fun clickResultLink(webView: android.webkit.WebView, href: String): Boolean =
        withTimeoutOrNull(SERP_EXTRACT_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    webView.evaluateJavascript(SerpLinkSelector.buildClickJs(href)) { result ->
                        if (cont.isActive) cont.resume(result?.contains("clicked") == true)
                    }
                }
            }
        } ?: false

    companion object {
        /** Bounds a wedged SERP render so it can't hold a pool slot (replaces the OkHttp readTimeout). */
        private const val SEARCH_LOAD_TIMEOUT_MS = 30_000L

        /** Whole-session wall-clock budget; steps are trimmed once it is exhausted. */
        private const val SESSION_BUDGET_MS = 60_000L

        /** Bounds the SERP link-harvest / click JS evaluations. */
        private const val SERP_EXTRACT_TIMEOUT_MS = 5_000L

        /** Bounds the main-thread metadata snapshot. */
        private const val METADATA_TIMEOUT_MS = 5_000L

        /** Dwell after each SERP load (scanning results / typing the next refinement). */
        private val QUERY_DWELL_MS = 2_000L..8_000L

        /** Dwell on a followed result page. */
        private val LINK_DWELL_MS = 3_000L..12_000L

        /**
         * Session size by the highest configured intensity tier (issue #76 honesty):
         * full narrative sessions at MEDIUM, a slimmer shape at LOW (documented as the
         * battery-sensitive tier — fewer extra page loads), 1+1 at HIGH, and the
         * pre-E5 single query at EXTREME where the mean gap (~7s) can't fit a session.
         */
        internal fun sessionBounds(actionsPerHour: Int): Pair<Int, Int> = when {
            actionsPerHour <= 12 -> 2 to 1   // LOW: battery-sensitive
            actionsPerHour <= 60 -> 3 to 2   // MEDIUM
            actionsPerHour <= 200 -> 1 to 1  // HIGH
            else -> 0 to 0                   // EXTREME
        }
    }
}
