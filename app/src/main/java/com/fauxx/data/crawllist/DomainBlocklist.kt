package com.fauxx.data.crawllist

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hard-coded blocklist of domains that must never be loaded by any Fauxx module.
 * Loaded from assets/blocklist.json at startup. Checked before every URL load.
 *
 * This is a non-negotiable safety requirement — no URL may bypass this check.
 */
@Singleton
class DomainBlocklist @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val parsedBlocklist: BlocklistJson by lazy { loadBlocklistJson() }
    private val blockedDomains: Set<String> by lazy {
        parsedBlocklist.domains.map { it.lowercase().trim() }.toSet()
    }
    private val blockedPatterns: List<Regex> by lazy {
        parsedBlocklist.patterns.mapNotNull { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }
    }

    /**
     * Returns true if [host] matches any blocked domain or pattern.
     * Called before every URL load across all modules and WebViews.
     */
    fun isBlocked(host: String): Boolean {
        val normalized = host.lowercase().trimStart('.')
        if (blockedDomains.contains(normalized)) return true
        if (blockedDomains.any { normalized.endsWith(".$it") }) return true
        if (blockedPatterns.any { it.containsMatchIn(normalized) }) return true
        return false
    }

    /**
     * Returns true if the full [url] string should be blocked.
     */
    fun isUrlBlocked(url: String): Boolean {
        val host = try {
            android.net.Uri.parse(url).host ?: return false
        } catch (e: Exception) {
            return true // Can't parse → block
        }
        return isBlocked(host)
    }

    private fun loadBlocklistJson(): BlocklistJson {
        return try {
            val json = context.assets.open("blocklist.json")
                .bufferedReader().readText()
            val type = object : TypeToken<BlocklistJson>() {}.type
            Gson().fromJson(json, type)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load blocklist.json — using empty blocklist")
            BlocklistJson()
        }
    }
}

/** JSON structure of blocklist.json. */
private data class BlocklistJson(
    val domains: List<String> = emptyList(),
    val patterns: List<String> = emptyList()
)
