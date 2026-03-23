package com.fauxx.engine.modules

import android.util.Log
import com.fauxx.data.crawllist.CrawlListManager
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.model.ActionType
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.webview.PhantomWebViewPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

private const val TAG = "AdPollutionModule"

/** Ad preference dashboard URLs to visit. */
private val AD_DASHBOARD_URLS = listOf(
    "https://adssettings.google.com/",
    "https://optout.aboutads.info/",
    "https://www.networkadvertising.org/choices/"
)

/**
 * Loads ad-heavy pages in a background WebView and visits ad preference dashboards
 * to populate the user's ad profile with off-demographic signals.
 *
 * Sub-1% CTR simulation: only "clicks" (loads ad landing page) on ~0.8% of page loads.
 */
@Singleton
class AdPollutionModule @Inject constructor(
    private val crawlListManager: CrawlListManager,
    private val webViewPool: PhantomWebViewPool,
    private val profileRepo: PoisonProfileRepository
) : Module {

    override suspend fun start() {
        webViewPool.initialize()
    }

    override suspend fun stop() {}

    override fun isEnabled(): Boolean = profileRepo.getProfile().adPollutionEnabled

    override suspend fun onAction(category: CategoryPool): ActionLogEntity {
        // 10% chance: visit an ad dashboard
        val url = if (Random.nextFloat() < 0.10f) {
            AD_DASHBOARD_URLS.random()
        } else {
            crawlListManager.nextUrl(category)?.url ?: return ActionLogEntity(
                actionType = ActionType.AD_CLICK,
                category = category,
                detail = "No eligible URL",
                success = false
            )
        }

        withContext(Dispatchers.Main) {
            val webView = webViewPool.acquire()
            try {
                webView.loadUrl(url)
                delay(Random.nextLong(3_000L, 15_000L))
            } catch (e: Exception) {
                Log.w(TAG, "Ad page load failed: ${e.message}")
            } finally {
                webViewPool.release(webView)
            }
        }

        return ActionLogEntity(
            actionType = ActionType.AD_CLICK,
            category = category,
            detail = url
        )
    }
}
