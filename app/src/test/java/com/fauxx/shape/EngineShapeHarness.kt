package com.fauxx.shape

import android.content.Context
import android.webkit.ValueCallback
import android.webkit.WebView
import com.fauxx.data.crawllist.CrawlListManager
import com.fauxx.data.crawllist.DomainBlocklist
import com.fauxx.data.db.ActionLogDao
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.db.CategoryCount
import com.fauxx.data.location.CityCoord
import com.fauxx.data.location.CityDatabase
import com.fauxx.data.model.ActionType
import com.fauxx.data.model.PoisonProfile
import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.data.querybank.GrammarQueryGenerator
import com.fauxx.data.querybank.QueryBankManager
import com.fauxx.data.querybank.QueryBlocklist
import com.fauxx.di.QueryGrammarSeed
import com.fauxx.engine.PoisonEngine
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.dns.CustomDns
import com.fauxx.engine.modules.AdPollutionModule
import com.fauxx.engine.modules.AppSignalModule
import com.fauxx.engine.modules.CookieSaturationModule
import com.fauxx.engine.modules.DnsNoiseModule
import com.fauxx.engine.modules.FingerprintModule
import com.fauxx.engine.modules.Module
import com.fauxx.engine.modules.PersonaSearchHabits
import com.fauxx.engine.modules.SearchPoisonModule
import com.fauxx.engine.modules.SerpLinkSelector
import com.fauxx.engine.scheduling.ActionDispatcher
import com.fauxx.engine.scheduling.PoissonScheduler
import com.fauxx.engine.webview.PhantomWebViewPool
import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
import com.fauxx.network.dns.DnsHealth
import com.fauxx.network.dns.HostResolver
import com.fauxx.network.dns.Resolution
import com.fauxx.service.ResumeSpec
import com.fauxx.targeting.TargetingEngine
import com.fauxx.targeting.WeightNormalizer
import com.fauxx.targeting.layer1.DemographicProfileDao
import com.fauxx.targeting.layer3.PersonaRateModulator
import com.fauxx.targeting.layer3.PersonaRotationLayer
import com.fauxx.util.Clock
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import timber.log.Timber

/**
 * Runs the real [PoisonEngine] for simulated days and records what an observer would see (#322
 * step 0), so every change to the stream's shape can be measured before and after.
 *
 * Real: the engine loop (rate cap, quiet-hours resign, execution-time subtraction), the
 * [PoissonScheduler] with the persona's daily rhythm, the [ActionDispatcher] over the default
 * Layer 3 weight blend, the [CrawlListManager] over the shipped English corpus and blocklist, and
 * the search, cookie, ad and DNS modules with their own dwell times, session plans and link
 * selection.
 *
 * Faked, because they are the network or the device:
 * - The WebView pool hands out one WebView that records each top-level `loadUrl` and result click
 *   at the virtual time it happens instead of loading anything. Every load succeeds. Page loads
 *   take no time of their own, which matches production, where the dwell starts as soon as
 *   `loadUrl` returns.
 * - A search results page lists [SERP_RESULTS] corpus pages from the dispatched category, so
 *   result clicks can always find a curated host. Real results pages offer fewer, so the harness
 *   overstates how often a search ends in a click. Bing and Yahoo wrap each result in their own
 *   redirector, as the real pages do, so searches there end on the results page.
 * - DNS lookups go to a recording resolver.
 * - The fingerprint module only re-binds the persona's device and makes no request, so it is a
 *   stub that logs an action.
 * - The device: always online, never low on battery, never asleep. Its network mock reads as
 *   mobile data, so the profile's mobile tier is the one that governs. The quiet-hours resign is
 *   answered the way the resume alarm answers it, by stopping the engine and starting it again at
 *   the time the engine asked for.
 * - Queries: the text does not affect the shape, so every goal is the same placeholder.
 *
 * Location spoofing and app signals are off by default and are not simulated, so the [profile]
 * must leave them off.
 *
 * Time: [clock] reads the test scheduler, so wall clock, elapsed realtime and coroutine delays
 * all move together and a single [run] call covers every simulated day.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class EngineShapeHarness(
    private val scope: TestScope,
    private val appContext: Context,
    private val profile: PoisonProfile,
    seed: Long,
    private val startEpochMs: Long,
) {
    init {
        require(!profile.locationSpoofEnabled && !profile.appSignalEnabled) {
            "The harness does not simulate location spoofing or app signals"
        }
    }

    private val clock: Clock = SchedulerClock(scope.testScheduler, startEpochMs)
    private val seeds = Random(seed)
    private fun nextRandom() = Random(seeds.nextLong())

    private val events = mutableListOf<StreamEvent>()
    private val actions = mutableListOf<ActionRecord>()

    /** Resume requests that were not quiet-hours resumes; any is a constraint the harness tripped. */
    val unexpectedResigns = mutableListOf<ResumeSpec>()

    /** The engine's log lines during [run], newest last, for explaining a failed invariant. */
    private val log = ArrayDeque<String>()
    private val logTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (log.size == LOG_LINES) log.removeFirst()
            log.addLast("${java.time.Instant.ofEpochMilli(clock.currentTimeMillis())} $message${t?.let { " ($it)" } ?: ""}")
        }
    }

    /** The last [lines] engine log lines from [run]. */
    fun logTail(lines: Int = 40): String = log.toList().takeLast(lines).joinToString("\n")

    /**
     * Every mock below, so [run] can drop their call history each simulated hour. MockK keeps every
     * call, with its stack trace, for verification; over weeks of simulated actions that alone
     * fills the heap. Anything called per page load is a plain fake instead.
     */
    private val mocks = mutableListOf<Any>()
    private fun <T : Any> tracked(mock: T): T = mock.also { mocks += it }

    private val persona = SyntheticPersona(
        id = "shape-$seed",
        name = "Shape Harness",
        ageRange = "AGE_35_44",
        profession = "ENGINEER",
        region = "US_MIDWEST",
        interests = CategoryPool.values().toList().shuffled(nextRandom()).take(PERSONA_INTERESTS).toSet(),
        createdAt = startEpochMs,
        activeUntil = Long.MAX_VALUE,
    )

    private val personaLayer: PersonaRotationLayer = tracked(mockk(relaxed = true) {
        every { personaForChannel(any()) } returns persona
    })

    // The default stack (Layer 3 on, Layers 1 and 2 off) through the production blend and normalizer.
    private val weights = WeightNormalizer().normalizeComplete(PersonaRotationLayer.weightsFor(persona.interests))

    private val targetingEngine: TargetingEngine = tracked(mockk(relaxed = true) {
        every { cachedWeights } returns MutableStateFlow(weights)
    })

    /** The category the engine last dispatched, so a results page can list pages on its topic. */
    private var dispatchedCategory: CategoryPool = CategoryPool.values().first()

    private val dispatcher: ActionDispatcher = tracked(spyk(ActionDispatcher(targetingEngine, nextRandom())) {
        every { selectCategory() } answers { callOriginal().also { dispatchedCategory = it } }
    })

    private val profileRepo: PoisonProfileRepository = tracked(mockk(relaxed = true) {
        every { getProfile() } returns profile
    })

    private val localeManager: LocaleManager = tracked(mockk(relaxed = true) {
        every { currentLocale } returns SupportedLocale.EN
    })

    // The shipped blocklist, so corpus pages production would refuse are refused here too.
    private val blocklist = DomainBlocklist(appContext)

    private val crawlListManager = CrawlListManager(appContext, blocklist, localeManager, clock, nextRandom())

    private val corpusByCategory: Map<CategoryPool, List<String>> = loadCorpus(appContext)
    private val serpRandom = nextRandom()
    private var currentUrl: String? = null
    private var currentSerpEngine: String? = null
    private val clickTargets = mutableMapOf<String, String>()

    /** A Robolectric WebView that records instead of loading. */
    private val webView: WebView = object : WebView(appContext) {
        override fun loadUrl(url: String, additionalHttpHeaders: Map<String, String>) =
            recordLoad(url, fromSearch = false)

        override fun getUrl(): String? = currentUrl

        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            resultCallback?.onReceiveValue(evaluate(script))
        }
    }

    private val webViewPool: PhantomWebViewPool = tracked(mockk(relaxed = true) {
        coEvery { acquire() } returns webView
        coEvery { release(any()) } just Runs
        every { lastLoadFailure(any()) } returns null
        every { captureMetadata(any(), any(), *anyVararg()) } returns null
    })

    private val queryBankManager: QueryBankManager = tracked(mockk(relaxed = true) {
        every { corpusGeneration } returns 0
        every { getQueries(any()) } returns listOf(PLACEHOLDER_QUERY)
        every { randomQuery(any()) } returns PLACEHOLDER_QUERY
    })

    private val searchModule = SearchPoisonModule(
        queryBankManager = queryBankManager,
        // A spy over a real instance: generate's default argument reads the instance's own Random,
        // which a plain mock does not have.
        grammarGenerator = tracked(
            spyk(
                GrammarQueryGenerator(
                    queryBankManager, tracked(mockk(relaxed = true)), localeManager, tracked(mockk(relaxed = true)),
                    personaLayer, QueryGrammarSeed { 0L }, nextRandom(),
                ),
            ) {
                every { generate(any(), any()) } returns PLACEHOLDER_QUERY
            },
        ),
        profileRepo = profileRepo,
        webViewPool = webViewPool,
        blocklist = blocklist,
        demographicDao = tracked(mockk<DemographicProfileDao>(relaxed = true) { coEvery { get() } returns null }),
        customInterestMapper = tracked(mockk(relaxed = true)),
        queryBlocklist = tracked(mockk<QueryBlocklist>(relaxed = true) {
            every { isBlocked(any()) } returns false
            every { isBlockedInAnyLocale(any()) } returns false
        }),
        localeManager = localeManager,
        crawlListManager = crawlListManager,
        clock = clock,
        random = nextRandom(),
        searchHabits = PersonaSearchHabits(personaLayer, QueryGrammarSeed { seed }),
    )

    private val cookieModule = CookieSaturationModule(crawlListManager, webViewPool, profileRepo, nextRandom())
    private val adModule = AdPollutionModule(crawlListManager, webViewPool, profileRepo, nextRandom())

    private val recordingDns = object : CustomDns {
        override val health: StateFlow<DnsHealth> = MutableStateFlow(DnsHealth.Off)
        override suspend fun start() {}
        override suspend fun stop() {}
        override fun noiseResolver(): HostResolver = HostResolver { host ->
            events += StreamEvent(clock.currentTimeMillis(), LoadKind.DNS, host)
            Resolution.Addresses(listOf(LOOPBACK))
        }
    }
    // On the test dispatcher: a lookup on a real IO thread would let virtual time run past it.
    private val dnsModule = DnsNoiseModule(
        crawlListManager, profileRepo, recordingDns, ioDispatcher = StandardTestDispatcher(scope.testScheduler),
    )

    private val fingerprintModule: FingerprintModule = tracked(mockk(relaxed = true) {
        every { isEnabled() } answers { profile.fingerprintEnabled }
        coEvery { onAction(any()) } answers {
            ActionLogEntity(actionType = ActionType.FINGERPRINT_ROTATE, category = firstArg(), detail = "persona device")
        }
    })

    /** Records each logged action at the virtual time the engine logs it. */
    private val actionLogDao = object : ActionLogDao {
        override suspend fun insert(entry: ActionLogEntity): Long {
            actions += ActionRecord(clock.currentTimeMillis(), entry.actionType, entry.success)
            return actions.size.toLong()
        }
        override fun observeAll(): Flow<List<ActionLogEntity>> = flowOf(emptyList())
        override fun countSince(sinceMillis: Long): Flow<Int> = flowOf(0)
        override suspend fun deleteOlderThan(beforeMillis: Long) {}
        override suspend fun getAllForExport(): List<ActionLogEntity> = emptyList()
        override suspend fun countPerCategorySince(sinceMillis: Long): List<CategoryCount> = emptyList()
    }

    private val connectivity: android.net.ConnectivityManager = tracked(mockk(relaxed = true))

    private val engine = PoisonEngine(
        context = tracked(mockk<Context>(relaxed = true) {
            every { getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivity
            every { getSystemService(android.net.ConnectivityManager::class.java) } returns connectivity
        }),
        profile = profileRepo,
        targetingEngine = targetingEngine,
        dispatcher = dispatcher,
        scheduler = PoissonScheduler(clock, nextRandom(), PersonaRateModulator(personaLayer)),
        actionLogDao = actionLogDao,
        blocklist = blocklist,
        queryBankManager = queryBankManager,
        crawlListManager = crawlListManager,
        cityDatabase = tracked(mockk<CityDatabase>(relaxed = true) {
            every { cities } returns listOf(CityCoord("A", 0.0, 0.0, "X"), CityCoord("B", 1.0, 1.0, "X"))
        }),
        searchModule = searchModule,
        adModule = adModule,
        locationModule = tracked(mockk<Module>(relaxed = true) { every { isEnabled() } returns false }),
        fingerprintModule = fingerprintModule,
        cookieModule = cookieModule,
        appSignalModule = tracked(mockk<AppSignalModule>(relaxed = true) { every { isEnabled() } returns false }),
        dnsModule = dnsModule,
        clock = clock,
        loopDispatcher = StandardTestDispatcher(scope.testScheduler),
        random = nextRandom(),
    )

    /** Run the engine for [days] simulated days from [startEpochMs] and return what it did. */
    fun run(days: Int): StreamRecording {
        Timber.plant(logTree)
        try {
            armResume()
            scope.backgroundScope.launch {
                while (true) {
                    delay(HOUR_MS)
                    clearMocks(
                        mocks.first(), *mocks.drop(1).toTypedArray(),
                        answers = false, recordedCalls = true, childMocks = true,
                        verificationMarks = true, exclusionRules = false,
                    )
                }
            }
            engine.start()
            scope.testScheduler.advanceTimeBy(days * DAY_MS)
            scope.testScheduler.runCurrent()
            engine.setOnLongPause(null)
            engine.destroy()
            scope.testScheduler.runCurrent()
        } finally {
            Timber.uproot(logTree)
        }
        val end = startEpochMs + days * DAY_MS
        return StreamRecording(
            events = events.filter { it.atMs < end },
            actions = actions.filter { it.atMs < end },
            startEpochMs = startEpochMs,
            days = days,
            allowedStart = profile.allowedHoursStart,
            allowedEnd = profile.allowedHoursEnd,
        )
    }

    /**
     * Answer a resign the way the service and its resume alarm do: stop, then start again when
     * asked. A stop clears the handler, so it is set again before every start.
     */
    private fun armResume() {
        engine.setOnLongPause { spec ->
            if (spec !is ResumeSpec.AtTime) unexpectedResigns += spec
            scope.backgroundScope.launch {
                engine.stop()
                val at = (spec as? ResumeSpec.AtTime)?.epochMs ?: (clock.currentTimeMillis() + UNEXPECTED_RESUME_MS)
                delay((at - clock.currentTimeMillis()).coerceAtLeast(0L))
                armResume()
                engine.start()
            }
        }
    }

    private fun recordLoad(url: String, fromSearch: Boolean) {
        currentUrl = url
        val uri = runCatching { URI(url) }.getOrNull() ?: return
        val host = uri.host?.lowercase() ?: return
        val engineId = if (fromSearch) null else serpEngine(host, uri)
        val kind = if (engineId != null) LoadKind.SERP else LoadKind.PAGE
        currentSerpEngine = engineId
        events += StreamEvent(clock.currentTimeMillis(), kind, host, url, engineId, fromSearch)
    }

    /** The link collector gets a results page on the dispatched topic; a click navigates. */
    private fun evaluate(js: String): String {
        if (js == SerpLinkSelector.COLLECT_LINKS_JS) {
            val results = corpusByCategory[dispatchedCategory].orEmpty()
                .shuffled(serpRandom)
                .distinctBy { runCatching { URI(it).host }.getOrNull() }
                .take(SERP_RESULTS)
            val links = results.map { href ->
                val target = URLEncoder.encode(href, "UTF-8")
                when (currentSerpEngine) {
                    "bing" -> "https://www.bing.com/ck/a?u=$target"
                    "yahoo" -> "https://r.search.yahoo.com/RU=$target/RK=2"
                    else -> href
                }
            }
            clickTargets.clear()
            for ((link, href) in links.zip(results)) clickTargets[SerpLinkSelector.buildClickJs(link)] = href
            return gson.toJson(gson.toJson(SERP_CHROME_LINKS + links))
        }
        val href = clickTargets[js] ?: return gson.toJson("missing")
        recordLoad(href, fromSearch = true)
        return gson.toJson("clicked")
    }

    private class SchedulerClock(private val scheduler: TestCoroutineScheduler, private val startEpochMs: Long) : Clock {
        override fun currentTimeMillis(): Long = startEpochMs + scheduler.currentTime
        override fun elapsedRealtime(): Long = BOOT_ELAPSED_MS + scheduler.currentTime
    }

    private data class RawCrawlEntry(val url: String = "", val category: String = "")

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        private const val HOUR_MS = 60L * 60 * 1000
        private const val BOOT_ELAPSED_MS = 1_000_000L
        private const val UNEXPECTED_RESUME_MS = 60_000L
        private const val PERSONA_INTERESTS = 4
        private const val LOG_LINES = 500
        private const val SERP_RESULTS = 8
        private const val PLACEHOLDER_QUERY = "shape harness query"

        /** Engine chrome every results page has; the selector's engine-family filter drops these. */
        private val SERP_CHROME_LINKS = listOf("https://www.google.com/preferences", "https://accounts.google.com/")

        private val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        private val gson = Gson()

        /**
         * The engine a results-page URL belongs to, keyed to the URL shapes SearchPoisonModule builds.
         * Other pages on an engine's domain (the ad module's ad-settings dashboards) are pages.
         */
        internal fun serpEngine(host: String, uri: URI): String? {
            val path = uri.rawPath.orEmpty()
            return when {
                host == "www.google.com" && path == "/search" -> "google"
                host == "www.bing.com" && path == "/search" -> "bing"
                host == "duckduckgo.com" && uri.rawQuery.orEmpty().contains("q=") -> "duckduckgo"
                host.endsWith("search.yahoo.com") && path == "/search" -> "yahoo"
                host == "yandex.com" && path.startsWith("/search") -> "yandex"
                else -> null
            }
        }

        private fun loadCorpus(context: Context): Map<CategoryPool, List<String>> {
            val json = context.assets.open("crawl_urls.json").bufferedReader().use { it.readText() }
            val raw: List<RawCrawlEntry> = gson.fromJson(json, object : TypeToken<List<RawCrawlEntry>>() {}.type)
            return raw.mapNotNull { entry ->
                val category = runCatching { CategoryPool.valueOf(entry.category.uppercase()) }.getOrNull()
                category?.let { it to entry.url }
            }.groupBy({ it.first }, { it.second })
        }
    }
}
