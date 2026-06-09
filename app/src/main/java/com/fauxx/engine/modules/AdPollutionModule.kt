package com.fauxx.engine.modules

import timber.log.Timber
import com.fauxx.data.crawllist.CrawlListManager
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.model.ActionType
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.webview.PhantomWebViewPool
import com.fauxx.engine.webview.SYNTHETIC_WEBVIEW_HEADERS
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
 * No ad clicks or conversions are generated: every load is a plain page visit,
 * including the occasional ad-preference dashboard visit.
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
        // 10% chance: visit an ad-preference dashboard; otherwise a plain crawl-list
        // page fetch. Both are logged as PAGE_VISIT: no ad clicks or conversions occur.
        val isDashboardVisit = random.nextFloat() < 0.10f
        val actionType = ActionType.PAGE_VISIT

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

        // #124: acquire/release off the main thread; only loadUrl + the metadata read hop to Main
        // (see PhantomWebViewPool / CookieSaturationModule for the freeze root cause).
        val webView = try {
            webViewPool.acquire()
        } catch (e: Exception) {
            Timber.w("WebView acquire failed: ${e.message}")
            null
        }
        var metadata: String? = null
        val success = if (webView == null) {
            false
        } else {
            try {
                withContext(Dispatchers.Main) { webView.loadUrl(url, SYNTHETIC_WEBVIEW_HEADERS) }
                delay(random.nextLong(3_000L, 15_000L))
                // #73: read page metadata on Main, before release() wipes the document.
                metadata = withContext(Dispatchers.Main) { webViewPool.captureMetadata(webView, url) }
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
            metadata = metadata,
            success = success
        )
    }
}
