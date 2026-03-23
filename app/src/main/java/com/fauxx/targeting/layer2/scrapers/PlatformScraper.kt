package com.fauxx.targeting.layer2.scrapers

import android.webkit.WebView

/**
 * Interface for ad platform scrapers. Each scraper reads the user's existing ad interest
 * categories from a platform's ad settings page.
 *
 * IMPORTANT CONSTRAINTS:
 * - Scrapers must ONLY read existing ad settings — never modify settings or click buttons
 * - Maximum 30-second timeout per scrape attempt
 * - On any failure, return empty set (never crash or throw to the engine)
 * - Use the scraper-tagged WebView from [com.fauxx.engine.webview.PhantomWebViewPool]
 *   to prevent cookie contamination between scraping and poisoning
 */
interface PlatformScraper {
    /** Unique identifier for this platform (e.g., "google", "facebook"). */
    val platformId: String

    /**
     * Scrape the user's ad interest categories from [webView].
     * Returns a set of raw platform category strings (e.g., "Video Games", "Travel").
     * Returns empty set on any failure.
     */
    suspend fun scrape(webView: WebView): Set<String>
}
