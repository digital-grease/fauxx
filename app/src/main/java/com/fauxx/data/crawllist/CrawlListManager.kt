package com.fauxx.data.crawllist

import android.content.Context
import android.util.Log
import com.fauxx.data.querybank.CategoryPool
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CrawlListManager"

/** Minimum milliseconds between requests to the same domain (safety requirement). */
private const val MIN_DOMAIN_INTERVAL_MS = 5_000L

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
    private val context: Context,
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

    /** Mark a domain as visited at [timestamp]. */
    fun markVisited(domain: String, timestamp: Long = System.currentTimeMillis()) {
        lastVisitByDomain[domain] = timestamp
    }

    /** Check if a domain can be visited now (rate limit). */
    fun isEligible(domain: String, now: Long = System.currentTimeMillis()): Boolean {
        val last = lastVisitByDomain[domain] ?: return true
        return (now - last) >= MIN_DOMAIN_INTERVAL_MS
    }

    private fun loadUrls(): List<CrawlEntry> {
        return try {
            val json = context.assets.open("crawl_urls.json")
                .bufferedReader().readText()
            val type = object : TypeToken<List<CrawlEntryJson>>() {}.type
            val raw: List<CrawlEntryJson> = Gson().fromJson(json, type)
            raw.mapNotNull { it.toCrawlEntry() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load crawl_urls.json", e)
            emptyList()
        }
    }
}

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
