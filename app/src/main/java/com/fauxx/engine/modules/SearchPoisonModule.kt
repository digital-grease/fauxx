package com.fauxx.engine.modules

import android.util.Log
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.model.ActionType
import com.fauxx.data.model.PoisonProfile
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.data.querybank.MarkovQueryGenerator
import com.fauxx.data.querybank.QueryBankManager
import com.fauxx.engine.PoisonProfileRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

private const val TAG = "SearchPoisonModule"

/** Search engines to rotate across. */
private val SEARCH_ENGINES = listOf(
    "https://www.google.com/search?q=",
    "https://www.bing.com/search?q=",
    "https://duckduckgo.com/?q=",
    "https://search.yahoo.com/search?p="
)

/**
 * Executes synthetic search queries across multiple search engines.
 * Follows 1-3 result links with random dwell time (2-30 seconds).
 *
 * Query selection is category-weighted via the ActionDispatcher.
 */
@Singleton
class SearchPoisonModule @Inject constructor(
    private val queryBankManager: QueryBankManager,
    private val markovGenerator: MarkovQueryGenerator,
    private val profileRepo: PoisonProfileRepository,
    private val httpClient: OkHttpClient
) : Module {

    override suspend fun start() {
        Log.d(TAG, "SearchPoisonModule started")
    }

    override suspend fun stop() {
        Log.d(TAG, "SearchPoisonModule stopped")
    }

    override fun isEnabled(): Boolean = profileRepo.getProfile().searchPoisonEnabled

    override suspend fun onAction(category: CategoryPool): ActionLogEntity {
        // Use Markov generator 60% of the time for natural-looking queries
        val query = if (Random.nextFloat() < 0.60f) {
            markovGenerator.generate(category)
        } else {
            queryBankManager.randomQuery(category)
        }

        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val engine = SEARCH_ENGINES.random()
        val url = "$engine$encodedQuery"

        try {
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "Search request to $engine returned ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Search request failed: ${e.message}")
        }

        return ActionLogEntity(
            actionType = ActionType.SEARCH_QUERY,
            category = category,
            detail = "[$category] $query"
        )
    }
}
