package com.fauxx.engine.modules

import timber.log.Timber
import com.fauxx.data.custompool.CustomPoolMixer
import com.fauxx.data.custompool.CustomPoolSource
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
import com.fauxx.data.crawllist.SearchEngineHealth
import com.fauxx.engine.webview.LoadFailureKind
import com.fauxx.engine.webview.PageLoadFailure
import com.fauxx.engine.webview.PhantomWebViewPool
import com.fauxx.engine.webview.SYNTHETIC_WEBVIEW_HEADERS
import com.fauxx.locale.AcceptLanguageVariants
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
    // Issue #24. Yandex broadens the engine pool, so engine choice varies across installs
    // and personas. Each persona still keeps to one usual engine (#322, SearchHabits), as
    // real people do. yandex.com (the international entry point) accepts `lang` for query
    // language; results are then served from the geographically nearest mirror.
    SearchEngine("yandex") { q, l ->
        "https://yandex.com/search/?text=$q&lang=${l.tag}"
    }
)

/**
 * Engine ids in pool order, for the Settings opt-out list (issue #281). Ids are the stable
 * persisted keys; [searchEngineDisplayName] renders them.
 */
val SEARCH_ENGINE_IDS: List<String> = SEARCH_ENGINES.map { it.name }

/** Brand names, identical in every locale, so these are not string resources. */
fun searchEngineDisplayName(id: String): String = when (id) {
    "google" -> "Google"
    "bing" -> "Bing"
    "duckduckgo" -> "DuckDuckGo"
    "yahoo" -> "Yahoo"
    "yandex" -> "Yandex"
    else -> id.replaceFirstChar { it.uppercase() }
}

/**
 * The engines synthetic searches may use, given the user's opt-outs (issue #281).
 *
 * Some users would rather not aim synthetic traffic at an engine they trust, which is a
 * reasonable ask, but the pool exists to be diverse: personas differ in their usual engine, and
 * each sends the occasional search elsewhere (#322, [SearchHabits]), so a persona must always have
 * a second engine to use (the reason Yandex was added in #24). The Settings UI enforces
 * [MIN_ACTIVE_SEARCH_ENGINES].
 *
 * The empty-pool branch is a genuine conflict of duties, not a free choice: honoring the
 * exclusions would stop the search module entirely, while ignoring them sends traffic to an
 * engine the user explicitly asked to spare. It resolves toward keeping the module alive,
 * because the reachable causes are a preference outliving the pool it referred to (an engine
 * removed in a later build, or a set synced from a device on a different version) rather than
 * a considered "exclude everything" — which the UI cannot express. It is NOT silent: the
 * override is logged at warning level, so an opt-out that stopped being honored is
 * diagnosable from a log export rather than invisible.
 */
internal fun activeSearchEngines(
    all: List<String>,
    excluded: Set<String>,
): List<String> {
    val kept = all.filterNot { it in excluded }
    if (kept.isNotEmpty()) return kept
    Timber.w(
        "Every search engine is excluded ($excluded); ignoring the opt-out to keep the " +
            "search module running. The stored exclusions likely predate this engine pool."
    )
    return all
}

/**
 * The engines a search may use right now (#353): the user's [allowed] engines minus those
 * backing off after a bot check. Empty means "search nothing for now"; it never falls back to an
 * engine that is backing off, since another search there is exactly what the backoff prevents.
 */
internal fun selectableSearchEngines(allowed: List<String>, heldBack: (String) -> Boolean): List<String> =
    allowed.filterNot { runCatching { heldBack(it) }.getOrDefault(false) }

/**
 * Recognizes the pages search engines show suspected automation (#353), by the URL the WebView
 * ended on. Status codes alone miss them: Google's /sorry page is reached through a 302 and can
 * answer 200, and Yandex's CAPTCHA is a plain redirect.
 */
internal object SearchChallenge {
    fun isChallengeUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        val path = uri.rawPath.orEmpty().lowercase()
        return when {
            // google.com/sorry/index, and the country domains (google.co.uk, google.fr, ...).
            isGoogleHost(host) -> path.startsWith("/sorry")
            host == "yandex.com" || host.endsWith(".yandex.com") || host.startsWith("yandex.") ->
                path.startsWith("/showcaptcha") || path.startsWith("/checkcaptcha")
            host == "bing.com" || host.endsWith(".bing.com") -> path.startsWith("/turing/captcha")
            else -> false
        }
    }

    private fun isGoogleHost(host: String): Boolean {
        val labels = host.removePrefix("www.").split('.')
        return labels.firstOrNull() == "google" && labels.size in 2..3
    }
}

/**
 * Executes synthetic search activity as intent-chain SESSIONS (E5 #175): one onAction
 * call runs a whole session — a goal query, then in-topic refinements built from
 * [SearchRefinements] (so successive queries narrow the same subject instead of being
 * independently category-random), then 1-2 organic result links followed from the final
 * SERP via [SerpLinkSelector]. Independent single queries were themselves a
 * bot-detectable shape: real search behavior moves through coherent narrowing sessions.
 *
 * **Search habits (#322)**: the active persona's [SearchHabits] shape each session. It goes to
 * the persona's usual engine most of the time, refines only some of the time, and does not always
 * click a result. A person keeps to one engine and often searches without clicking; an even spread
 * across engines with every session refined and clicked looked like no one. Without habits
 * ([SearchHabitsSource.NONE]) a session keeps that old shape.
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
 *
 * **User-added searches (#328)**: when the user has added their own search terms, a size-scaled
 * share of sessions ([CustomPoolMixer]) uses one of them as the goal instead, regardless of the
 * dispatched category. Such a goal must ALSO pass [QueryBlocklist.isBlockedInAnyLocale], since the
 * user may type in a language other than the active noise language. Custom goals get no
 * refinements (the refinement templates are in the noise language and would wrap a term typed in
 * another one), are tagged [CUSTOM_TAG] in the action-log detail (whose export coarsening still
 * applies), and their text never reaches the debug log, not even on the invariant-violation path.
 */
@Singleton
class SearchPoisonModule @Inject constructor(
    private val queryBankManager: QueryBankManager,
    private val grammarGenerator: GrammarQueryGenerator,
    private val profileRepo: PoisonProfileRepository,
    private val webViewPool: PhantomWebViewPool,
    private val blocklist: DomainBlocklist,
    private val demographicDao: DemographicProfileDao,
    private val customInterestMapper: CustomInterestMapper,
    private val queryBlocklist: QueryBlocklist,
    private val localeManager: LocaleManager,
    private val crawlListManager: com.fauxx.data.crawllist.CrawlListManager,
    private val customPool: CustomPoolSource = CustomPoolSource.EMPTY,
    private val clock: com.fauxx.util.Clock = com.fauxx.util.SystemClockImpl(),
    private val random: Random = Random.Default,
    // #353: engines that answered with a bot check are skipped for a while.
    private val engineHealth: SearchEngineHealth = SearchEngineHealth.NONE,
    // #322: the active persona's usual engine and how often it refines and clicks.
    private val searchHabits: SearchHabitsSource = SearchHabitsSource.NONE,
) : Module {

    override suspend fun start() {
        Timber.d("SearchPoisonModule started")
        webViewPool.initialize()
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

    /**
     * #353: every engine the user allows is backing off after a bot check. The engine then
     * dispatches other modules instead of sending another search to a CAPTCHA, and comes back
     * here once a hold runs out.
     */
    override fun isHeldBack(): Boolean = selectableSearchEngines(allowedEngines(), engineHealth::isEngineHeldBack).isEmpty()

    /** The user's engine opt-outs (issue #281) applied; never changed by the backoff. */
    private fun allowedEngines(): List<String> =
        activeSearchEngines(SEARCH_ENGINE_IDS, profileRepo.getProfile().excludedSearchEngines)

    override suspend fun onAction(category: CategoryPool): ActionLogEntity {
        // Session goal. Use the grammar generator 60% of the time for natural-looking, per-install
        // styled queries (E5 #179); the rest are raw corpus picks. The grammar wraps Markov/corpus
        // heads, so this keeps naturalness while breaking the shared-corpus fleet signature.
        // #328: some sessions start from the user's own search terms instead. No randomness is
        // drawn when the user has none, so the built-in path is unchanged for them.
        val customSearches = customPool.searches()
        val isCustom = CustomPoolMixer.drawCustom(customSearches.size, random)
        val goal = if (isCustom) {
            customSearches.random(random)
        } else if (random.nextFloat() < 0.60f) {
            grammarGenerator.generate(category)
        } else {
            queryBankManager.randomQuery(category)
        }

        // Final safety gate on the goal. If a query reaches here matching the blocklist,
        // upstream filters (QueryBankManager load-time filter + GrammarQueryGenerator
        // resample) have failed — log the invariant violation and drop the cycle.
        if (goal.isBlank() || queryBlocklist.isBlocked(goal) ||
            (isCustom && queryBlocklist.isBlockedInAnyLocale(goal))
        ) {
            if (isCustom) {
                // The user's own term is user data: say what happened, never what it was.
                Timber.w("A custom search is now blocked by the safety guard; skipping it")
            } else {
                Timber.e(
                    "QueryBlocklist invariant violation: query '%s' escaped upstream " +
                        "guards; dropping action cycle (category=%s)",
                    goal,
                    category
                )
            }
            return ActionLogEntity(
                actionType = ActionType.SEARCH_QUERY,
                category = category,
                detail = "[BLOCKED] query suppressed by safety guard"
            )
        }

        val locale = localeManager.currentLocale
        // Honor the user's engine opt-outs (issue #281), then leave out engines backing off after
        // a bot check (#353). The engine loop does not dispatch here while every engine is held
        // back; this covers a hold that started in between.
        val allowed = allowedEngines()
        val selectable = selectableSearchEngines(allowed, engineHealth::isEngineHeldBack).toSet()
        if (selectable.isEmpty()) {
            return ActionLogEntity(
                actionType = ActionType.SEARCH_QUERY,
                category = category,
                detail = "[$category] search skipped: every enabled engine is backing off after a bot check",
                success = false,
            )
        }
        // #322: the persona's usual engine most of the time, drawn from the user's allowed engines so a
        // bot-check hold never changes which one that is.
        val habits = searchHabits.habits(locale, allowed)
        val selectableEngines = SEARCH_ENGINES.filter { it.name in selectable }
        val engine = if (habits == null) {
            selectableEngines.random(random)
        } else {
            val name = habits.pickEngine(selectableEngines.map { it.name }, locale, random)
            selectableEngines.first { it.name == name }
        }
        val goalUrl = engine.build(java.net.URLEncoder.encode(goal, "UTF-8"), locale)
        // Engine name suffix surfaces which SERP each search actually hit, so the user
        // can verify newly-added engines (e.g. Yandex per #24) are actually firing.
        // The detail carries only the GOAL query (LogScrubber's export coarsening is
        // keyed to this exact shape); refinement texts are never logged.
        val detail = "${if (isCustom) CUSTOM_TAG else "[$category]"} $goal · via ${engine.name}"

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
        // tier (Wi-Fi, mobile or the charging boost, #316): the conservative direction for
        // rate honesty.
        val profile = profileRepo.getProfile()
        val configuredCeiling = maxOf(
            profile.intensity.actionsPerHour,
            profile.mobileIntensity?.actionsPerHour ?: 0,
            profile.chargingIntensity?.actionsPerHour ?: 0
        )
        val (maxRefinements, maxLinks) = sessionBounds(configuredCeiling)
        // #322: whether to refine at all is the persona's habit; many real searches stand alone.
        val refines = maxRefinements > 0 && !isCustom &&
            (habits == null || random.nextFloat() < habits.refineChance)
        val refinements = if (refines) {
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
        var goalFailure: PageLoadFailure? = null
        var queriesDispatched = 0
        var linksFollowed = 0
        val success = if (webView == null) {
            false
        } else {
            try {
                val deadline = clock.currentTimeMillis() + SESSION_BUDGET_MS
                val goalLoad = loadWithDwell(webView, goalUrl, headers, QUERY_DWELL_MS)
                goalFailure = goalLoad.failure
                noteEngineOutcome(engine.name, goalLoad)
                val goalOk = goalLoad.ok
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

                    var botChecked = false
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
                        val load = loadWithDwell(webView, url, headers, QUERY_DWELL_MS)
                        if (load.ok) {
                            queriesDispatched++
                        } else if (load.botCheck) {
                            // #353: stop the session at the first bot check; every further
                            // query or click would land on the same challenge.
                            noteEngineOutcome(engine.name, load)
                            botChecked = true
                            break
                        }
                    }

                    // #322: a click is the persona's habit too; plenty of searches end on the results page.
                    val clicks = habits == null || random.nextFloat() < habits.clickChance

                    // Click 1-2 results on the final (most narrowed) SERP. Candidates
                    // are ALLOW-listed to curated crawl-corpus hosts — live SERPs
                    // surface arbitrary destinations and the denylist cannot vouch for
                    // them (a visited URL is itself a profile entry). The click runs
                    // the SERP's own anchor, so its instrumentation and referrer
                    // semantics are genuine, and PhantomWebViewClient re-gates the
                    // resulting navigation.
                    if (!botChecked && maxLinks > 0 && clock.currentTimeMillis() < deadline && clicks) {
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
            metadata = (
                metadata ?: LogMetadata.toJson(
                    LogMetadata.SEARCH_ENGINE to engine.name,
                    LogMetadata.LOAD_FAILURE to goalFailure?.describe(),
                )
            ).let { if (isCustom) customSearchMetadata(it) else it },
            success = success,
        )
    }

    /**
     * One SERP load's outcome. [failure] is null when the page loaded, or when the load threw or
     * timed out (then [ok] is false with no failure to show).
     */
    private class SearchLoad(val ok: Boolean, val failure: PageLoadFailure?) {
        val botCheck: Boolean get() = failure?.kind == LoadFailureKind.BOT_WALL
    }

    /**
     * #353: a bot check holds the engine back; a clean load clears it. Nothing else counts: an
     * offline device, a DNS blocker (Yandex is often filtered by name) or Fauxx's own DNS proxy
     * fail as network or local errors, never as a bot check, so they cannot hold an engine back.
     */
    private fun noteEngineOutcome(engineId: String, load: SearchLoad) {
        runCatching {
            when {
                load.botCheck -> engineHealth.recordEngineBotCheck(engineId)
                load.ok -> engineHealth.recordEngineSuccess(engineId)
            }
        }.onFailure { Timber.w("Search engine health update failed: ${it.javaClass.simpleName}") }
    }

    /**
     * Metadata for a session whose goal came from the user's own list (#328): marked with its
     * source, and without the SERP page title. That title echoes the query verbatim, and unlike
     * the detail line it is not coarsened when the log is exported, so it would carry the user's
     * own term into a shared export.
     */
    private fun customSearchMetadata(json: String?): String? =
        LogMetadata.toJson(
            *LogMetadata.parse(json)
                .filterNot { it.first == LogMetadata.PAGE_TITLE }
                .plus(LogMetadata.SOURCE to LogMetadata.SOURCE_CUSTOM)
                .toTypedArray()
        )

    /** Load [url] in [webView] and dwell like a human scanning it. Not ok on timeout/throw. */
    private suspend fun loadWithDwell(
        webView: android.webkit.WebView,
        url: String,
        headers: Map<String, String>,
        dwellMs: LongRange,
    ): SearchLoad = try {
        withTimeoutOrNull(SEARCH_LOAD_TIMEOUT_MS) {
            // #268: clear before navigating so this reflects THIS load, not an earlier
            // refinement's failure on the same acquired WebView.
            webViewPool.clearLoadError(webView)
            withContext(Dispatchers.Main) { webView.loadUrl(url, headers) }
            delay(random.nextLong(dwellMs.first, dwellMs.last + 1))
            // #268: a DNS-blocked or refused load still "finishes" — on an error page. Treat a
            // main-frame error as a failed load instead of reporting the action as a success.
            // #353: also judge by where the WebView ended up. Google's /sorry page arrives through
            // a 302 and can answer 200, so the status alone does not always say "bot check".
            val finalUrl = withTimeoutOrNull(METADATA_TIMEOUT_MS) {
                withContext(Dispatchers.Main) { runCatching { webView.url }.getOrNull() }
            }
            val recorded = webViewPool.lastLoadFailure(webView)
            val failure = if (SearchChallenge.isChallengeUrl(finalUrl) && recorded?.kind != LoadFailureKind.BOT_WALL) {
                PageLoadFailure(LoadFailureKind.BOT_WALL, "challenge page")
            } else {
                recorded
            }
            if (failure != null) {
                Timber.d("Search load failed [${failure.describe()}]")
                SearchLoad(ok = false, failure = failure)
            } else {
                SearchLoad(ok = true, failure = null)
            }
        } ?: SearchLoad(ok = false, failure = null)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w("Search WebView load failed: ${e.message}")
        SearchLoad(ok = false, failure = null)
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
        /** Action-log detail prefix for a session whose goal came from the user's own list (#328). */
        const val CUSTOM_TAG = "[CUSTOM]"

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
