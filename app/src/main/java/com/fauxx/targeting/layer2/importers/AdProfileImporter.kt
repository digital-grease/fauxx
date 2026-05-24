package com.fauxx.targeting.layer2.importers

import android.net.Uri

/**
 * Source of a user-driven Layer 2 ad-profile import.
 *
 * Replaces the live in-app scraper path (retired in v0.3.0 — see issue #51 for why
 * cookie isolation between apps made the in-app scrape architecturally unworkable).
 * Users export their ad-targeting data from the platform themselves (Google Takeout
 * or Facebook DYI), then import the resulting archive via the Storage Access Framework.
 *
 * The [platformId] matches the key persisted in `PlatformProfileCache.platformName`, so
 * downstream consumers (`AdversarialScraperLayer.getWeights()`) need no changes — the
 * cache schema is unchanged across the architectural retreat.
 */
enum class ImportSource(val platformId: String, val displayName: String) {
    GOOGLE_TAKEOUT("google", "Google Takeout"),
    FACEBOOK_DYI("facebook", "Facebook DYI")
}

/**
 * Outcome of a single import attempt. Sealed so the UI can render a specific message
 * for each shape rather than a generic "failed" toast — the most common failure mode
 * is a user picking the wrong archive subset (e.g., a full Takeout instead of the
 * Ads-only export), and a clear error keeps support load low.
 */
sealed class ImportResult {
    /** Archive parsed, [categoryCount] interest categories persisted to the cache. */
    data class Success(val source: ImportSource, val categoryCount: Int) : ImportResult()

    /**
     * Archive opened successfully but didn't contain the platform's ad-interest data.
     * Usually means the user exported the wrong subset (e.g., picked "My Activity" but
     * not "Ads" in Takeout). [reason] describes what the importer looked for.
     */
    data class WrongFormat(val source: ImportSource, val reason: String) : ImportResult()

    /** Archive found the expected data file but parsing failed. */
    data class ParseError(
        val source: ImportSource,
        val message: String,
        val cause: Throwable? = null
    ) : ImportResult()

    /**
     * Couldn't open the [Uri] at all (permission revoked, file disappeared, etc.).
     * Distinct from [WrongFormat] because the user can't fix it by re-exporting.
     */
    data class IoError(val source: ImportSource, val cause: Throwable) : ImportResult()
}

/**
 * Reads an ad-targeting category archive from a user-provided file [Uri] (Storage Access
 * Framework) and persists the extracted categories to `PlatformProfileCache`.
 *
 * Implementations MUST:
 * - Stream-parse archives (Takeout ZIPs can exceed 10 GB) — never unzip to disk
 * - Return [ImportResult.WrongFormat] with a specific reason rather than throwing when
 *   the expected data file is missing inside the archive
 * - Never throw across the public boundary — all error paths funnel through [ImportResult]
 */
interface AdProfileImporter {
    /** Identifies which platform / export format this importer handles. */
    val source: ImportSource

    /**
     * Parse the archive at [uri] and write any extracted categories to
     * `PlatformProfileCache` under [source].platformId. Idempotent — repeated imports
     * overwrite the cache entry (the export is the source of truth at import time).
     */
    suspend fun import(uri: Uri): ImportResult
}
