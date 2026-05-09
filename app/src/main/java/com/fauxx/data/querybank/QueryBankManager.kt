package com.fauxx.data.querybank

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads and serves synthetic search queries from bundled JSON assets, scoped to the
 * currently-active locale resolved by [LocaleManager].
 *
 * Asset layout:
 *  - Per-locale: `assets/query_banks/<localeTag>/<category>.json`
 *  - Legacy fallback: `assets/query_banks/<category>.json` (treated as English)
 *
 * The manager loads the per-locale file first; if absent it falls back to the legacy
 * path (shipped English corpus). Banks are loaded lazily and cached by `(locale, category)`.
 *
 * On load, each bank is pre-filtered through [QueryBlocklist] so harmful queries (illegal
 * content, or benign-but-signaling queries like 988/crisis-line/DV-hotline) are dropped
 * before they can be sampled. A filtered count is logged at `Timber.w` to surface corpus
 * drift for cleanup.
 *
 * NOTE on plausibility: when an active locale has no per-locale bank shipped yet, the
 * fallback emits English queries. Combined with locale-matched Accept-Language headers
 * and search-engine `hl=`/`gl=` params, that mismatch is a fingerprintable signal for
 * data brokers — fauxx therefore gates user-selectable locales behind
 * `BuildConfig.SHIPPED_LOCALES` so a user cannot pick a locale that hasn't shipped its
 * banks (see `.devloop/spikes/multilingual-support.md`).
 */
@Singleton
class QueryBankManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val queryBlocklist: QueryBlocklist,
    private val localeManager: LocaleManager
) {
    private val cache = ConcurrentHashMap<Pair<SupportedLocale, CategoryPool>, List<String>>()
    private val watcherScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // Drop the initial replay (current value at subscription) so the cache isn't
        // cleared on app start, then evict on every subsequent locale change. Old-locale
        // cache entries are not memory-pressure-bounded but keying on (locale, category)
        // means stale entries are simply never read again.
        watcherScope.launch {
            localeManager.currentLocaleFlow
                .drop(1)
                .distinctUntilChanged()
                .collect {
                    Timber.d("Locale changed; clearing QueryBankManager cache")
                    cache.clear()
                }
        }
    }

    /**
     * Get a random query from the [category] bank in the active locale.
     * Falls back to a generic query if the category bank is empty or missing.
     * The fallback is itself run through [QueryBlocklist] as a defensive check;
     * if it matches (extraordinarily unlikely for the trivial template), returns
     * an empty string which upstream gate in `SearchPoisonModule` will suppress.
     */
    fun randomQuery(category: CategoryPool): String {
        val queries = getQueries(category)
        return queries.randomOrNull() ?: fallbackQuery(category)
    }

    /**
     * Get the full query list for a [category] in the active locale, loading from
     * assets on first access. The returned list contains only queries that pass
     * [QueryBlocklist.isBlocked] == false (or an empty list, if the whole bank was
     * rejected or failed to load).
     */
    fun getQueries(category: CategoryPool): List<String> {
        val locale = localeManager.currentLocale
        return cache.getOrPut(locale to category) { loadCategory(locale, category) }
    }

    private fun loadCategory(locale: SupportedLocale, category: CategoryPool): List<String> {
        val localeFilename = "query_banks/${locale.tag}/${category.name.lowercase()}.json"
        val legacyFilename = "query_banks/${category.name.lowercase()}.json"

        val raw: List<String> = try {
            val json = openAssetWithFallback(localeFilename, legacyFilename)
                .bufferedReader().readText()
            val type = object : TypeToken<List<String>>() {}.type
            Gson().fromJson(json, type)
        } catch (e: Exception) {
            Timber.w("Could not load query bank: $localeFilename (or legacy fallback)")
            return emptyList()
        }

        val filtered = raw.filterNot { queryBlocklist.isBlocked(it) }
        val dropped = raw.size - filtered.size
        if (dropped > 0) {
            Timber.w(
                "QueryBlocklist filtered $dropped/${raw.size} harmful entries from " +
                    "$localeFilename — corpus needs cleanup"
            )
        }
        return filtered
    }

    private fun openAssetWithFallback(primary: String, fallback: String) =
        runCatching { context.assets.open(primary) }
            .getOrElse { context.assets.open(fallback) }

    private fun fallbackQuery(category: CategoryPool): String {
        val candidate = "information about ${category.name.lowercase()}"
        return if (queryBlocklist.isBlocked(candidate)) "" else candidate
    }
}
