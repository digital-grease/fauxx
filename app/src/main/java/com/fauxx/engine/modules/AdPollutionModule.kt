package com.fauxx.engine.modules

import timber.log.Timber
import com.fauxx.data.crawllist.CrawlListManager
import com.fauxx.data.crawllist.CrawlEntry
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.db.LogMetadata
import com.fauxx.data.model.ActionType
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.webview.CrawlOutcomeSink
import com.fauxx.engine.webview.PageLoadFailure
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
 * Part of its crawl-list pages are advertisers' own landing pages (#322 item 7), where their
 * retargeting pixels record a purchase intent; the log names the IAB segment. No ad clicks or
 * conversions are generated: every load is a plain page view, and nothing is added to a cart.
 */
@Singleton
class AdPollutionModule @Inject constructor(
    private val crawlListManager: CrawlListManager,
    private val webViewPool: PhantomWebViewPool,
    private val profileRepo: PoisonProfileRepository,
    private val random: Random = Random.Default,
    // #353: remembers crawl URLs that keep failing so CrawlListManager stops picking them.
    private val crawlOutcomes: CrawlOutcomeSink = CrawlOutcomeSink.NONE,
    // #322: browses on to a few more pages of the same site after a crawl-list page.
    private val siteBrowsing: SiteBrowsing = SiteBrowsing.NONE,
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

        var crawlEntry: CrawlEntry? = null
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
            crawlEntry = pending.entry
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
        var visited = false
        var failure: PageLoadFailure? = null
        val success = if (webView == null) {
            false
        } else {
            try {
                withContext(Dispatchers.Main) { webView.loadUrl(url, SYNTHETIC_WEBVIEW_HEADERS) }
                val dwellMs = random.nextLong(3_000L, 15_000L)
                delay(dwellMs)
                // #268: an error page still "finishes" loading. A main-frame failure (DNS blocked,
                // refused, HTTP 4xx/5xx) is not a successful visit, so don't log it as one. #353:
                // judged on the latest navigation, so a passed bot check counts as the visit it is.
                val loadFailure = webViewPool.lastLoadFailure(webView)
                failure = loadFailure
                visited = true
                // #73: read page metadata on Main, before release() wipes the document.
                metadata = withContext(Dispatchers.Main) {
                    webViewPool.captureMetadata(webView, url, LogMetadata.LOAD_FAILURE to loadFailure?.describe())
                }
                if (loadFailure != null) Timber.d("Ad page load failed [${loadFailure.describe()}]")
                // #322: browse on within a crawl-list site, never from an ad-preference dashboard.
                if (loadFailure == null && crawlEntry != null) {
                    val followed = siteBrowsing.browseOn(webView, sinceLoadMs = dwellMs)
                    metadata = LogMetadata.append(metadata, LogMetadata.SESSION_LINKS to followed.takeIf { it > 0 }?.toString())
                }
                loadFailure == null
            } catch (e: Exception) {
                Timber.w("Ad page load failed: ${e.message}")
                false
            } finally {
                webViewPool.release(webView)
            }
        }
        // #353: only crawl-list pages are remembered; the dashboards are a fixed, short list.
        val visitedEntry = crawlEntry
        if (visited && visitedEntry != null) crawlOutcomes.report(visitedEntry, failure)

        return ActionLogEntity(
            actionType = actionType,
            category = category,
            detail = url,
            metadata = if (crawlEntry?.isCustom == true) {
                LogMetadata.append(metadata, LogMetadata.SOURCE to LogMetadata.SOURCE_CUSTOM)
            } else {
                LogMetadata.append(metadata, LogMetadata.PURCHASE_INTENT to crawlEntry?.let(crawlListManager::describeIntent))
            },
            success = success
        )
    }
}
