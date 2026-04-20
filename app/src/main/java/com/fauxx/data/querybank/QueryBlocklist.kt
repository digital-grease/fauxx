package com.fauxx.data.querybank

import android.content.Context
import androidx.annotation.Keep
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Blocklist for query CONTENT (distinct from [com.fauxx.data.crawllist.DomainBlocklist]
 * which blocks URL destinations). Rejects two classes of query:
 *
 *  - **Class A — harm to others / illegal content**: bomb recipes, CSAM, drug synthesis,
 *    weaponization guides. Safe-to-execute-by-humans is NOT a defense.
 *  - **Class B — self-signal harm**: queries that are individually benign (often
 *    lifesaving for a real searcher) but create a false first-person distress signal
 *    when dispatched as synthetic user activity. Example: a 988 query from a real user
 *    is a lifeline; the same query injected as noise creates a false "user in crisis"
 *    flag in broker / government / insurer profiles with real-world consequences
 *    (wellness checks, insurance denial, watchlists).
 *
 * Enforced at four chokepoints to provide defense in depth:
 *  1. [QueryBankManager.getQueries] — pre-filters the corpus at load time
 *  2. [MarkovQueryGenerator.generate] — post-generation check with resample fallback
 *  3. [MarkovQueryGenerator.injectSeedPhrases] — rejects user-supplied harmful seeds
 *  4. [com.fauxx.engine.modules.SearchPoisonModule.onAction] — final dispatch gate;
 *     skips the action cycle rather than dispatching
 *
 * **Fail-closed**: if `harmful_queries.json` fails to load, [loadFailed] is set to
 * `true` and [isBlocked] returns `true` for every input. A missing safety list must
 * NEVER silently downgrade to "allow everything".
 */
@Singleton
class QueryBlocklist @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * `true` if `harmful_queries.json` could not be loaded. When this is set,
     * [isBlocked] returns `true` for every query (fail-closed). Callers may expose
     * this as a health warning.
     */
    @Volatile
    var loadFailed: Boolean = false
        private set

    private val parsed: HarmfulQueriesJson by lazy { loadJson() }

    /** All substring terms from both harm classes, lowercased. */
    private val phraseTerms: Set<String> by lazy {
        (parsed.classATerms + parsed.selfSignalTerms)
            .map { it.lowercase().trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private val regexes: List<Regex> by lazy {
        parsed.regexPatterns.mapNotNull {
            runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull()
        }
    }

    init {
        // Eagerly resolve the lazy load so [loadFailed] is set at injection time
        // before any module tries to filter a query.
        phraseTerms.size
    }

    /**
     * Returns `true` if [query] matches any harmful phrase or regex pattern.
     *
     * Checked at every query chokepoint. Comparison is case-insensitive and uses
     * substring match for phrase terms (multi-word anchors prevent false positives
     * on common single words — see `harmful_queries.json` contributor rules).
     *
     * If [loadFailed] is set, returns `true` for every input (fail-closed).
     */
    fun isBlocked(query: String): Boolean {
        if (loadFailed) return true
        val normalized = query.lowercase()
        if (phraseTerms.any { normalized.contains(it) }) return true
        if (regexes.any { it.containsMatchIn(normalized) }) return true
        return false
    }

    private fun loadJson(): HarmfulQueriesJson {
        return try {
            val json = context.assets.open("harmful_queries.json")
                .bufferedReader().readText()
            val type = object : TypeToken<HarmfulQueriesJson>() {}.type
            val result: HarmfulQueriesJson = Gson().fromJson(json, type)
            if (result.classATerms.isEmpty() &&
                result.selfSignalTerms.isEmpty() &&
                result.regexPatterns.isEmpty()
            ) {
                Timber.e("harmful_queries.json loaded but all lists empty — failing closed")
                loadFailed = true
            }
            result
        } catch (e: Exception) {
            Timber.e(e, "Failed to load harmful_queries.json — failing closed, all queries will be blocked")
            loadFailed = true
            HarmfulQueriesJson()
        }
    }
}

/**
 * JSON structure of `assets/harmful_queries.json`.
 *
 * @Keep: without this, R8 in release builds strips or renames this type, and
 * Gson's reflection-based deserialization returns an empty object — flipping
 * [QueryBlocklist.loadFailed] to `true` and fail-closing every query chokepoint.
 */
@Keep
private data class HarmfulQueriesJson(
    @com.google.gson.annotations.SerializedName("class_a_terms")
    val classATerms: List<String> = emptyList(),
    @com.google.gson.annotations.SerializedName("self_signal_terms")
    val selfSignalTerms: List<String> = emptyList(),
    @com.google.gson.annotations.SerializedName("regex_patterns")
    val regexPatterns: List<String> = emptyList()
)
