package com.fauxx.data.crawllist

import android.content.Context
import androidx.annotation.Keep
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import com.fauxx.data.custompool.CustomPoolMixer
import com.fauxx.data.custompool.CustomPoolSource
import com.fauxx.data.custompool.CustomPoolValidator
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
import com.fauxx.util.Clock
import com.fauxx.util.SystemClockImpl
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** Bundled real-world site ranks (#322 item 8), built by scripts/build_site_popularity.py. */
private const val POPULARITY_ASSET = "site_popularity.json"

/** Minimum milliseconds between requests to the same domain (safety requirement). */
private const val MIN_DOMAIN_INTERVAL_MS = 5_000L

/** Entries older than this are evicted from the rate limit map. */
private const val STALE_ENTRY_THRESHOLD_MS = 24 * 60 * 60 * 1000L

/** Minimum entries before a cleanup pass is triggered. */
private const val CLEANUP_THRESHOLD = 500

/**
 * Manages the URL corpus used for cookie saturation and page visits.
 *
 * - Loads URLs from `assets/crawl_urls/<localeTag>.json` (with fallback to legacy
 *   `assets/crawl_urls.json` for English) at first access per locale.
 * - Tracks last-visit timestamp per domain to enforce the 5-second per-domain rate limit.
 * - Filters out blocked domains before returning URLs.
 * - Supports category-weighted URL selection. Within the candidates, a site is drawn by its
 *   real-world popularity ([SitePopularity], #322 item 8), then one of its pages evenly; a share
 *   of visits goes to the persona's favourite sites instead ([HabitualSites], #322 item 3).
 * - Skips URLs and domains that keep failing ([UrlHealthGate], #353), falling back to other
 *   categories and then to ignoring that memory rather than stalling.
 * - Keeps advertisers' landing pages ([CrawlEntry.isLanding], #322 item 7) apart: a draw is a
 *   landing page the persona's [HabitualSites.landingShare] of the time when the category has
 *   one, and an editorial page otherwise.
 *
 * User-added sites (#328): when the user has added their own sites, a size-scaled share of
 * selections ([CustomPoolMixer]) draws from them instead, independently of [category]. They pass
 * the same [DomainBlocklist] check as built-in URLs plus [CustomPoolValidator.isDispatchableUrl],
 * and share the per-domain rate limit. They are NOT curated hosts ([isCuratedHost]): that
 * allow-list is the reviewed corpus only, so a user entry never widens which live-SERP results
 * the search module may follow. When every custom site is cooling down, the built-in corpus
 * serves the action as usual.
 *
 * The crawl list is locale-keyed because data brokers fingerprint domain mix as a
 * region indicator: a Spanish-mode profile should hit elmundo.es and mercadolibre,
 * not exclusively webmd.com and espn.com. The rate-limit map is shared across locales
 * (a domain visited in one locale stays cooldown'd if the locale changes mid-session).
 */
@Singleton
class CrawlListManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val blocklist: DomainBlocklist,
    private val localeManager: LocaleManager,
    private val clock: Clock = SystemClockImpl(),
    private val random: Random = Random.Default,
    private val urlHealth: UrlHealthGate = UrlHealthGate.NONE,
    private val customPool: CustomPoolSource = CustomPoolSource.EMPTY,
    private val siteHabits: SiteHabitsSource = SiteHabitsSource.NONE,
) {
    private val urlsByLocale = ConcurrentHashMap<SupportedLocale, List<CrawlEntry>>()
    private val lastVisitByDomain = mutableMapOf<String, Long>()

    /** Number of URLs in the active locale's corpus (0 if load failed). */
    fun corpusSize(): Int = currentUrls().size

    /**
     * Get a URL to visit, optionally filtered to [category].
     * Respects per-domain rate limits and blocklist.
     *
     * @param category If non-null, prefer URLs in this category.
     * @return A [CrawlEntry] to visit, or null if none is currently eligible.
     */
    fun nextUrl(category: CategoryPool? = null): CrawlEntry? = nextUrl(category, drawLanding(category))

    private fun nextUrl(category: CategoryPool?, landing: Boolean): CrawlEntry? {
        val now = clock.currentTimeMillis()
        cleanupStaleEntries(now)
        val customSites = customPool.sites()
        if (CustomPoolMixer.drawCustom(customSites.size, random)) {
            nextCustomUrl(customSites, category, now)?.let { return it }
        }
        return pick(candidatePool(category, landing).filter { entry -> isEligible(entry.domain, now) })
            ?.also { entry ->
                lastVisitByDomain[entry.domain] = now
            }
    }

    /**
     * Whether this draw goes to an advertiser's landing page (#322 item 7): the persona's
     * [HabitualSites.landingShare] of the time, and only when [category] (or, with none, the
     * corpus) has a landing page to go to.
     */
    private fun drawLanding(category: CategoryPool?): Boolean {
        val share = habitualSites()?.landingShare ?: return false
        if (share <= 0.0 || random.nextDouble() >= share) return false
        return currentUrls().any { it.isLanding && (category == null || it.category == category) }
    }

    /**
     * One of [entries]: a favourite site's page [HabitualSites.share] of the time when a favourite
     * is among them, otherwise a site drawn by popularity; then one of that site's pages evenly.
     * Weighing sites rather than pages keeps a site with many pages in the list from being drawn
     * more for it.
     */
    private fun pick(entries: List<CrawlEntry>): CrawlEntry? {
        if (entries.isEmpty()) return null
        val bySite = entries.groupBy { popularity.siteOf(it.domain) }
        habitualSites()?.let { habits ->
            val present = habits.sites.withIndex().filter { it.value in bySite }
            if (present.isNotEmpty() && random.nextDouble() < habits.share) {
                val favourite = weightedPick(present) { habits.weightAt(it.index) }.value
                return bySite.getValue(favourite).random(random)
            }
        }
        val site = weightedPick(bySite.keys.toList()) { popularity.weightOf(it) }
        return bySite.getValue(site).random(random)
    }

    private fun <T> weightedPick(items: List<T>, weight: (T) -> Double): T {
        val weights = items.map { weight(it).coerceAtLeast(0.0) }
        var point = random.nextDouble() * weights.sum()
        for ((item, w) in items.zip(weights)) {
            point -= w
            if (point < 0) return item
        }
        return items.last()
    }

    /**
     * Real-world popularity of the crawl sites. Read from the bundled asset together with the first
     * bundled crawl list, so a list a test installs directly keeps the even draw unless the test
     * installs a popularity too; an even draw as well if the asset cannot be read.
     */
    @Volatile
    private var popularity: SitePopularity = SitePopularity.EVEN

    @Volatile
    private var popularityLoaded = false

    private fun loadPopularityOnce() {
        if (popularityLoaded) return
        popularityLoaded = true
        popularity = runCatching {
            SitePopularity.parse(context.assets.open(POPULARITY_ASSET).bufferedReader().use { it.readText() })
        }.onFailure { Timber.w(it, "Site popularity unavailable; sites are drawn evenly") }
            .getOrDefault(SitePopularity.EVEN)
        purchaseIntents = runCatching {
            PurchaseIntents.parse(context.assets.open(PurchaseIntents.ASSET_PATH).bufferedReader().use { it.readText() })
        }.onFailure { Timber.w(it, "Purchase-intent names unavailable; the log shows ids") }
            .getOrDefault(PurchaseIntents.UNNAMED)
    }

    /** Test-only seam: use [popularity] instead of the bundled asset. */
    @VisibleForTesting
    internal fun replacePopularityForTest(popularity: SitePopularity) {
        popularityLoaded = true
        this.popularity = popularity
        habitsFor = null
    }

    /** Favourites for the current persona and language, derived once per persona. */
    @Volatile
    private var habitsFor: Pair<SiteHabitsKey, SupportedLocale>? = null

    @Volatile
    private var habits: HabitualSites? = null

    private fun habitualSites(): HabitualSites? {
        val key = runCatching { siteHabits.current() }.getOrNull() ?: return null
        val locale = localeManager.currentLocale
        val wanted = key to locale
        if (habitsFor != wanted) {
            habits = HabitualSites.derive(key.key, key.interests, currentUrls(), popularity, locale.tag)
            habitsFor = wanted
        }
        return habits
    }

    /**
     * Like [nextUrl], but when all candidates are rate-limited, returns the entry
     * with the shortest remaining cooldown instead of null.
     *
     * @return A [PendingCrawlEntry] containing the entry and the milliseconds to wait
     *   before it becomes eligible (0 if immediately available), or null only if the
     *   corpus itself is empty for the requested category.
     */
    fun nextUrlOrWait(category: CategoryPool? = null): PendingCrawlEntry? {
        val landing = drawLanding(category)
        // Fast path: try the normal lookup first
        val immediate = nextUrl(category, landing)
        if (immediate != null) return PendingCrawlEntry(immediate, 0L)

        // All candidates are rate-limited — find the one with the shortest remaining wait
        val now = clock.currentTimeMillis()
        val eligible = candidatePool(category, landing)
        if (eligible.isEmpty()) return null

        // Find the entry whose domain's rate-limit expires soonest
        val best = eligible.minByOrNull { entry ->
            val lastVisit = lastVisitByDomain[entry.domain] ?: 0L
            val remaining = (lastVisit + MIN_DOMAIN_INTERVAL_MS) - now
            maxOf(0L, remaining)
        } ?: return null

        val lastVisit = lastVisitByDomain[best.domain] ?: 0L
        val waitMs = maxOf(0L, (lastVisit + MIN_DOMAIN_INTERVAL_MS) - now)
        return PendingCrawlEntry(best, waitMs)
    }

    /**
     * The URLs a pick may choose from, before the rate limit: the landing pages when [landing], the
     * editorial pages otherwise; of those, [category]'s entries (or every entry), minus blocklisted
     * ones and minus those [urlHealth] holds back (#353).
     *
     * Holding back must never stall the crawler. If every healthy-looking entry of [category] is
     * held back, the pick falls back to the healthy entries of any category, and if the whole
     * corpus is held back, the memory is ignored and the plain list is used. Landing pages that
     * are all held back fall back to editorial pages the same way.
     */
    private fun candidatePool(category: CategoryPool?, landing: Boolean = false): List<CrawlEntry> {
        val all = currentUrls()
        if (landing) {
            val landingPages = all.filter { it.isLanding && (category == null || it.category == category) }
            landingPages.filter { !blocklist.isUrlBlocked(it.url) && isHealthy(it) }.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val urls = all.filterNot { it.isLanding }.ifEmpty { all }
        val inCategory = if (category != null) {
            urls.filter { it.category == category }.ifEmpty { urls } // Fall back to any category
        } else {
            urls
        }
        val allowed = inCategory.filter { !blocklist.isUrlBlocked(it.url) }
        allowed.filter(::isHealthy).takeIf { it.isNotEmpty() }?.let { return it }
        if (inCategory !== urls) {
            urls.filter { !blocklist.isUrlBlocked(it.url) && isHealthy(it) }.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return allowed
    }

    private fun isHealthy(entry: CrawlEntry): Boolean =
        runCatching { !urlHealth.isSuppressed(entry.url, entry.domain) }.getOrDefault(true)

    /**
     * A user-added site that passes the safety gates and is off cooldown, or null. The entry
     * carries the requested [category] (or the first category when none was requested) only
     * because [CrawlEntry.category] is non-null; custom sites are not categorised and are drawn
     * without regard to it.
     */
    private fun nextCustomUrl(sites: List<String>, category: CategoryPool?, now: Long): CrawlEntry? =
        sites.asSequence()
            .filter { CustomPoolValidator.isDispatchableUrl(it) && !blocklist.isUrlBlocked(it) }
            .mapNotNull { url ->
                CustomPoolValidator.hostOf(url)?.let { host ->
                    CrawlEntry(url, host, category ?: CategoryPool.entries.first(), isCustom = true)
                }
            }
            .filter { isHealthy(it) && isEligible(it.domain, now) }
            .toList()
            .randomOrNull(random)
            ?.also { lastVisitByDomain[it.domain] = now }

    /** Mark a domain as visited at [timestamp]. */
    fun markVisited(domain: String, timestamp: Long = clock.currentTimeMillis()) {
        lastVisitByDomain[domain] = timestamp
    }

    /** Check if a domain can be visited now (rate limit). */
    fun isEligible(domain: String, now: Long = clock.currentTimeMillis()): Boolean {
        val last = lastVisitByDomain[domain] ?: return true
        return (now - last) >= MIN_DOMAIN_INTERVAL_MS
    }

    private fun currentUrls(): List<CrawlEntry> {
        val locale = localeManager.currentLocale
        return urlsByLocale.getOrPut(locale) { loadUrls(locale) }
    }

    private val hostsByLocale = ConcurrentHashMap<SupportedLocale, Set<String>>()

    /**
     * True if [host] belongs to the curated crawl corpus for the active locale (exact
     * host or subdomain, www-insensitive). E5 (#175) uses this as the ALLOW-list for
     * following SERP result links: the corpus is the project's only reviewed set of
     * safe-to-visit destinations, whereas the blocklist is a finite named denylist
     * that cannot vouch for arbitrary live-SERP results (a visited URL IS the profile
     * entry per the QueryBlocklist threat model). Empty corpus means nothing is
     * allowed — fail closed.
     */
    fun isCuratedHost(host: String): Boolean {
        val normalized = host.lowercase().removePrefix("www.")
        return curatedHosts().any { normalized == it || normalized.endsWith(".$it") }
    }

    /**
     * True if [host] is an advertiser's site with landing pages in the active locale's corpus
     * (#322 item 7), matched like [isCuratedHost].
     */
    fun isShopHost(host: String): Boolean {
        val normalized = host.lowercase().removePrefix("www.")
        return shopHosts().any { normalized == it || normalized.endsWith(".$it") }
    }

    /**
     * True if [url] is on a shop's site ([isShopHost]) and its address names a sensitive purchase
     * ([SensitivePurchases]). Browsing on and search clicks skip such pages, so a shop's menus
     * cannot lead a visit to a purchase intent the curation keeps out.
     */
    fun isSensitiveShopPage(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return false
        return isShopHost(host) && SensitivePurchases.mentions(url)
    }

    /** The purchase-intent node [entry] signals, named, or null for an editorial page. */
    fun describeIntent(entry: CrawlEntry): String? = entry.intent?.let { purchaseIntents.describe(it) }

    private val shopHostsByLocale = ConcurrentHashMap<SupportedLocale, Set<String>>()

    private fun shopHosts(): Set<String> =
        shopHostsByLocale.getOrPut(localeManager.currentLocale) {
            currentUrls().filter { it.isLanding }.mapNotNullTo(mutableSetOf()) { entry ->
                runCatching { java.net.URI(entry.url).host }.getOrNull()?.lowercase()?.removePrefix("www.")
            }
        }

    /**
     * Names of the purchase-intent nodes, read from the bundled asset with the first bundled crawl
     * list, as [popularity] is; a list a test installs directly keeps them unnamed.
     */
    @Volatile
    private var purchaseIntents: PurchaseIntents = PurchaseIntents.UNNAMED

    private fun curatedHosts(): Set<String> =
        hostsByLocale.getOrPut(localeManager.currentLocale) {
            currentUrls().mapNotNullTo(mutableSetOf()) { entry ->
                runCatching { java.net.URI(entry.url).host }.getOrNull()
                    ?.lowercase()?.removePrefix("www.")
            }
        }

    /** Evicts entries older than 24h to prevent unbounded map growth. */
    private fun cleanupStaleEntries(now: Long) {
        if (lastVisitByDomain.size < CLEANUP_THRESHOLD) return
        val cutoff = now - STALE_ENTRY_THRESHOLD_MS
        lastVisitByDomain.entries.removeAll { it.value < cutoff }
    }

    private fun loadUrls(locale: SupportedLocale): List<CrawlEntry> {
        loadPopularityOnce()
        val localePath = "crawl_urls/${locale.tag}.json"
        val legacyPath = "crawl_urls.json"
        return try {
            val stream = runCatching { context.assets.open(localePath) }
                .getOrElse {
                    if (locale == SupportedLocale.EN) context.assets.open(legacyPath)
                    else throw it
                }
            val json = stream.bufferedReader().readText()
            val type = object : TypeToken<List<CrawlEntryJson>>() {}.type
            val raw: List<CrawlEntryJson> = Gson().fromJson(json, type)
            val entries = raw.mapNotNull { it.toCrawlEntry() }
            val dropped = raw.size - entries.size
            if (dropped > 0) {
                Timber.w("CrawlListManager[${locale.tag}]: dropped $dropped of ${raw.size} entries (invalid URL or unknown category)")
            }
            if (entries.isEmpty()) {
                Timber.e("CrawlListManager[${locale.tag}]: crawl corpus is empty after parsing — all URL-dependent modules will be non-functional")
            }
            entries
        } catch (e: Exception) {
            Timber.e(e, "Failed to load crawl_urls for locale=${locale.tag} — all URL-dependent modules will be non-functional")
            emptyList()
        }
    }

    /**
     * Test-only seam: directly install a URL list for the given locale, bypassing
     * the asset-load path. Used by unit tests to feed deterministic corpora without
     * relying on reflection or filesystem assets.
     */
    @VisibleForTesting
    internal fun replaceUrlsForTest(locale: SupportedLocale, urls: List<CrawlEntry>) {
        urlsByLocale[locale] = urls
        hostsByLocale.remove(locale)
        shopHostsByLocale.remove(locale)
        habitsFor = null
    }
}

/**
 * A crawl entry paired with the milliseconds to wait before it becomes eligible.
 * A [waitMs] of 0 means immediately available.
 */
data class PendingCrawlEntry(
    val entry: CrawlEntry,
    val waitMs: Long
)

/**
 * A single URL entry in the crawl corpus.
 *
 * [isCustom] marks a site the user added themselves (#328). Modules use it to keep the address out
 * of the debug log and to tag the action-log entry; it is otherwise visited like any other entry.
 *
 * [intent] marks an advertiser's landing page (#322 item 7): the IAB Audience Taxonomy 1.1
 * purchase-intent node a visit there signals ([PurchaseIntents] names it). Ordinary draws leave
 * landing pages out; the persona's [HabitualSites.landingShare] of draws goes to them instead.
 */
data class CrawlEntry(
    val url: String,
    val domain: String,
    val category: CategoryPool,
    val isCustom: Boolean = false,
    val intent: Int? = null,
) {
    /** An advertiser's landing page rather than an editorial page. */
    val isLanding: Boolean get() = intent != null
}

/**
 * Raw JSON representation of a crawl entry.
 *
 * @Keep: without this, R8 in release builds strips or renames the `url` / `category`
 * fields, and Gson's reflection-based deserialization returns all-blank entries —
 * every one then drops in [toCrawlEntry] and the corpus loads as empty.
 */
@Keep
private data class CrawlEntryJson(
    val url: String = "",
    val category: String = "",
    val intent: Int? = null,
) {
    fun toCrawlEntry(): CrawlEntry? {
        if (url.isBlank()) return null
        val cat = runCatching { CategoryPool.valueOf(category.uppercase()) }.getOrNull()
            ?: return null
        val domain = try {
            android.net.Uri.parse(url).host ?: return null
        } catch (e: Exception) { return null }
        return CrawlEntry(url, domain, cat, intent = intent?.takeIf { it > 0 })
    }
}
