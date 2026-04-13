package com.fauxx.data.crawllist

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import com.fauxx.data.querybank.CategoryPool
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import javax.inject.Inject
import javax.inject.Singleton

/** Minimum milliseconds between requests to the same domain (safety requirement). */
private const val MIN_DOMAIN_INTERVAL_MS = 5_000L

/** Entries older than this are evicted from the rate limit map. */
private const val STALE_ENTRY_THRESHOLD_MS = 24 * 60 * 60 * 1000L

/** Minimum entries before a cleanup pass is triggered. */
private const val CLEANUP_THRESHOLD = 500

/**
 * Manages the 10,000+ URL corpus used for cookie saturation and page visits.
 *
 * - Loads URLs from assets/crawl_urls.json at startup
 * - Tracks last-visit timestamp per domain to enforce the 5-second per-domain rate limit
 * - Filters out blocked domains before returning URLs
 * - Supports category-weighted URL selection
 */
@Singleton
class CrawlListManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val blocklist: DomainBlocklist
) {
    private val allUrls: List<CrawlEntry> by lazy { loadUrls() }
    private val lastVisitByDomain = mutableMapOf<String, Long>()

    /**
     * Get a URL to visit, optionally filtered to [category].
     * Respects per-domain rate limits and blocklist.
     *
     * @param category If non-null, prefer URLs in this category.
     * @return A [CrawlEntry] to visit, or null if none is currently eligible.
     */
    fun nextUrl(category: CategoryPool? = null): CrawlEntry? {
        val now = System.currentTimeMillis()
        cleanupStaleEntries(now)
        val candidates = if (category != null) {
            allUrls.filter { it.category == category }
                .ifEmpty { allUrls } // Fall back to any category
        } else {
            allUrls
        }

        return candidates
            .filter { entry ->
                !blocklist.isUrlBlocked(entry.url) &&
                isEligible(entry.domain, now)
            }
            .randomOrNull()
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
        val now = System.currentTimeMillis()
        val candidates = if (category != null) {
            allUrls.filter { it.category == category }
                .ifEmpty { allUrls }
        } else {
            allUrls
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
    fun markVisited(domain: String, timestamp: Long = System.currentTimeMillis()) {
        lastVisitByDomain[domain] = timestamp
    }

    /** Check if a domain can be visited now (rate limit). */
    fun isEligible(domain: String, now: Long = System.currentTimeMillis()): Boolean {
        val last = lastVisitByDomain[domain] ?: return true
        return (now - last) >= MIN_DOMAIN_INTERVAL_MS
    }

    /** Evicts entries older than 24h to prevent unbounded map growth. */
    private fun cleanupStaleEntries(now: Long) {
        if (lastVisitByDomain.size < CLEANUP_THRESHOLD) return
        val cutoff = now - STALE_ENTRY_THRESHOLD_MS
        lastVisitByDomain.entries.removeAll { it.value < cutoff }
    }

    private fun loadUrls(): List<CrawlEntry> {
        return try {
            val json = context.assets.open("crawl_urls.json")
                .bufferedReader().readText()
            val type = object : TypeToken<List<CrawlEntryJson>>() {}.type
            val raw: List<CrawlEntryJson> = Gson().fromJson(json, type)
            val entries = raw.mapNotNull { it.toCrawlEntry() }
            val dropped = raw.size - entries.size
            if (dropped > 0) {
                Timber.w("CrawlListManager: dropped $dropped of ${raw.size} entries (invalid URL or unknown category)")
            }
            if (entries.isEmpty()) {
                Timber.e("CrawlListManager: crawl corpus is empty after parsing — all URL-dependent modules will be non-functional")
            }
            entries
        } catch (e: Exception) {
            Timber.e(e, "Failed to load crawl_urls.json — all URL-dependent modules will be non-functional")
            emptyList()
        }
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

/** Raw JSON representation of a crawl entry. */
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
