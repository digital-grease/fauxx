package com.fauxx.data.querybank

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads and serves synthetic search queries from bundled JSON assets.
 *
 * Each [CategoryPool] value maps to a JSON file at assets/query_banks/{category}.json
 * containing 500+ queries. Queries are loaded lazily per category and cached in memory.
 *
 * On load, each bank is pre-filtered through [QueryBlocklist] so harmful queries (illegal
 * content, or benign-but-signaling queries like 988/crisis-line/DV-hotline) are dropped
 * before they can be sampled. A filtered count is logged at `Timber.w` to surface corpus
 * drift for cleanup.
 */
@Singleton
class QueryBankManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val queryBlocklist: QueryBlocklist
) {
    private val cache = mutableMapOf<CategoryPool, List<String>>()

    /**
     * Get a random query from the [category] bank.
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
     * Get the full query list for a [category], loading from assets on first access.
     * The returned list is guaranteed to contain only queries that pass
     * [QueryBlocklist.isBlocked] == false (or an empty list, if the whole bank was
     * rejected or failed to load).
     */
    fun getQueries(category: CategoryPool): List<String> {
        return cache.getOrPut(category) { loadCategory(category) }
    }

    private fun loadCategory(category: CategoryPool): List<String> {
        val filename = "query_banks/${category.name.lowercase()}.json"
        val raw: List<String> = try {
            val json = context.assets.open(filename).bufferedReader().readText()
            val type = object : TypeToken<List<String>>() {}.type
            Gson().fromJson(json, type)
        } catch (e: Exception) {
            Timber.w("Could not load query bank: $filename")
            return emptyList()
        }

        val filtered = raw.filterNot { queryBlocklist.isBlocked(it) }
        val dropped = raw.size - filtered.size
        if (dropped > 0) {
            Timber.w(
                "QueryBlocklist filtered $dropped/${raw.size} harmful entries from " +
                    "$filename — corpus needs cleanup"
            )
        }
        return filtered
    }

    private fun fallbackQuery(category: CategoryPool): String {
        val candidate = "information about ${category.name.lowercase()}"
        return if (queryBlocklist.isBlocked(candidate)) "" else candidate
    }
}
