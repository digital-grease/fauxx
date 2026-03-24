package com.fauxx.data.querybank

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "QueryBankManager"

/**
 * Loads and serves synthetic search queries from bundled JSON assets.
 *
 * Each [CategoryPool] value maps to a JSON file at assets/query_banks/{category}.json
 * containing 500+ queries. Queries are loaded lazily per category and cached in memory.
 */
@Singleton
class QueryBankManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cache = mutableMapOf<CategoryPool, List<String>>()

    /**
     * Get a random query from the [category] bank.
     * Falls back to a generic query if the category bank is empty or missing.
     */
    fun randomQuery(category: CategoryPool): String {
        val queries = getQueries(category)
        return queries.randomOrNull() ?: "information about ${category.name.lowercase()}"
    }

    /**
     * Get the full query list for a [category], loading from assets on first access.
     */
    fun getQueries(category: CategoryPool): List<String> {
        return cache.getOrPut(category) { loadCategory(category) }
    }

    private fun loadCategory(category: CategoryPool): List<String> {
        val filename = "query_banks/${category.name.lowercase()}.json"
        return try {
            val json = context.assets.open(filename).bufferedReader().readText()
            val type = object : TypeToken<List<String>>() {}.type
            Gson().fromJson(json, type)
        } catch (e: Exception) {
            Log.w(TAG, "Could not load query bank: $filename")
            emptyList()
        }
    }
}
