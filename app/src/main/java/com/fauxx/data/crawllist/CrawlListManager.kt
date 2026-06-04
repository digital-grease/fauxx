package com.fauxx.data.crawllist

import android.content.Context
import androidx.annotation.Keep
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
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
 * - Supports category-weighted URL selection.
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
    fun nextUrl(category: CategoryPool? = null): CrawlEntry? {
        val now = clock.currentTimeMillis()
        cleanupStaleEntries(now)
        val urls = currentUrls()
        val candidates = if (category != null) {
            urls.filter { it.category == category }
                .ifEmpty { urls } // Fall back to any category
        } else {
            urls
        }

        return candidates
            .filter { entry ->
                !blocklist.isUrlBlocked(entry.url) &&
                isEligible(entry.domain, now)
            }
            .randomOrNull(random)
            ?.also { entry ->
                lastVisitByDomain[entry.domain] = now
            }
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
        // Fast path: try the normal lookup first
        val immediate = nextUrl(category)
        if (immediate != null) return PendingCrawlEntry(immediate, 0L)

        // All candidates are rate-limited — find the one with the shortest remaining wait
        val now = clock.currentTimeMillis()
        val urls = currentUrls()
        val candidates = if (category != null) {
            urls.filter { it.category == category }
                .ifEmpty { urls }
        } else {
            urls
        }

        val eligible = candidates.filter { !blocklist.isUrlBlocked(it.url) }
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

    /** Evicts entries older than 24h to prevent unbounded map growth. */
    private fun cleanupStaleEntries(now: Long) {
        if (lastVisitByDomain.size < CLEANUP_THRESHOLD) return
        val cutoff = now - STALE_ENTRY_THRESHOLD_MS
        lastVisitByDomain.entries.removeAll { it.value < cutoff }
    }

    private fun loadUrls(locale: SupportedLocale): List<CrawlEntry> {
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

/** A single URL entry in the crawl corpus. */
data class CrawlEntry(
    val url: String,
    val domain: String,
    val category: CategoryPool
)

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
    val category: String = ""
) {
    fun toCrawlEntry(): CrawlEntry? {
        if (url.isBlank()) return null
        val cat = runCatching { CategoryPool.valueOf(category.uppercase()) }.getOrNull()
            ?: return null
        val domain = try {
            android.net.Uri.parse(url).host ?: return null
        } catch (e: Exception) { return null }
        return CrawlEntry(url, domain, cat)
    }
}
