package com.fauxx.engine.modules

import timber.log.Timber
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
    private val profileRepo: PoisonProfileRepository,
    private val random: Random = Random.Default,
) : Module {

    override suspend fun start() {
        webViewPool.initialize()
    }

    override suspend fun stop() {}

    override fun isEnabled(): Boolean = profileRepo.getProfile().adPollutionEnabled

    override suspend fun onAction(category: CategoryPool): ActionLogEntity {
        // 10% chance: visit an ad dashboard (logged as AD_CLICK);
        // otherwise: plain crawl-list page fetch (logged as PAGE_VISIT).
        val isDashboardVisit = random.nextFloat() < 0.10f
        val actionType = if (isDashboardVisit) ActionType.AD_CLICK else ActionType.PAGE_VISIT

        val url = if (isDashboardVisit) {
            AD_DASHBOARD_URLS.random(random)
        } else {
            val pending = crawlListManager.nextUrlOrWait(category)
                ?: crawlListManager.nextUrlOrWait(null)
            if (pending == null) {
                return ActionLogEntity(
                    actionType = actionType,
                    category = category,
                    detail = "No eligible URL",
                    success = false
                )
            }
            if (pending.waitMs > 0) {
                delay(pending.waitMs)
                crawlListManager.markVisited(pending.entry.domain)
            }
            pending.entry.url
        }

        // #124: acquire/release off the main thread; only loadUrl hops to Main (see
        // PhantomWebViewPool / CookieSaturationModule for the freeze root cause).
        val webView = try {
            webViewPool.acquire()
        } catch (e: Exception) {
            Timber.w("WebView acquire failed: ${e.message}")
            null
        }
        val success = if (webView == null) {
            false
        } else {
            try {
                withContext(Dispatchers.Main) { webView.loadUrl(url) }
                delay(random.nextLong(3_000L, 15_000L))
                true
            } catch (e: Exception) {
                Timber.w("Ad page load failed: ${e.message}")
                false
            } finally {
                webViewPool.release(webView)
            }
        }

        return ActionLogEntity(
            actionType = actionType,
            category = category,
            detail = url,
            success = success
        )
    }
}
