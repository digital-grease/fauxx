package com.fauxx.engine.modules

import timber.log.Timber
import com.fauxx.data.crawllist.CrawlListManager
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

/**
 * Visits URLs from [CrawlListManager] in an isolated background WebView to accumulate
 * diverse tracker cookies. URL selection is weighted by category from ActionDispatcher.
 *
 * Respects per-domain rate limits enforced by CrawlListManager.
 */
@Singleton
class CookieSaturationModule @Inject constructor(
    private val crawlListManager: CrawlListManager,
    private val webViewPool: PhantomWebViewPool,
    private val profileRepo: PoisonProfileRepository,
    private val random: Random = Random.Default,
    // #353: remembers crawl URLs that keep failing so CrawlListManager stops picking them.
    private val crawlOutcomes: CrawlOutcomeSink = CrawlOutcomeSink.NONE,
    // #322: browses on to a few more pages of the same site after the landing page.
    private val siteBrowsing: SiteBrowsing = SiteBrowsing.NONE,
) : Module {

    override suspend fun start() {
        webViewPool.initialize()
    }

    override suspend fun stop() {}

    override fun isEnabled(): Boolean = profileRepo.getProfile().cookieSaturationEnabled

    override suspend fun onAction(category: CategoryPool): ActionLogEntity {
        val pending = crawlListManager.nextUrlOrWait(category)
            ?: crawlListManager.nextUrlOrWait(null)

        if (pending == null) {
            return ActionLogEntity(
                actionType = ActionType.COOKIE_HARVEST,
                category = category,
                detail = "No eligible URL available",
                success = false
            )
        }

        if (pending.waitMs > 0) {
            delay(pending.waitMs)
            crawlListManager.markVisited(pending.entry.domain)
        }

        val entry = pending.entry

        val dwellMs = random.nextLong(2_000L, 10_000L) // 2-10 second dwell

        // #124: acquire/release run off the main thread (the engine loop is on Dispatchers.IO);
        // only loadUrl + the metadata read hop to Main. The old code ran the whole block, including
        // the blocking pool permit wait, inside withContext(Main), so a leaked permit froze Main and
        // stalled every subsequent action.
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
                withContext(Dispatchers.Main) { webView.loadUrl(entry.url, SYNTHETIC_WEBVIEW_HEADERS) }
                delay(dwellMs)
                // #268: an error page still "finishes" loading. A main-frame failure (DNS blocked,
                // refused, HTTP 4xx/5xx) is not a successful visit, so don't log it as one. #353:
                // judged on the latest navigation, so a passed bot check counts as the visit it is.
                val loadFailure = webViewPool.lastLoadFailure(webView)
                failure = loadFailure
                visited = true
                // #73: read page metadata on Main, before release() wipes the document.
                metadata = withContext(Dispatchers.Main) {
                    webViewPool.captureMetadata(webView, entry.url, LogMetadata.LOAD_FAILURE to loadFailure?.describe())
                }
                if (loadFailure != null) Timber.d("Cookie harvest load failed [${loadFailure.describe()}]")
                if (loadFailure == null) {
                    // #322: a person reads on within a site; the landing page alone decides success.
                    val followed = siteBrowsing.browseOn(webView, sinceLoadMs = dwellMs)
                    metadata = LogMetadata.append(metadata, LogMetadata.SESSION_LINKS to followed.takeIf { it > 0 }?.toString())
                }
                loadFailure == null
            } catch (e: Exception) {
                // A user-added site (#328) stays out of the debug log; built-in URLs are corpus data.
                Timber.w("Failed to load ${if (entry.isCustom) "a custom site" else entry.url}: ${e.message}")
                false
            } finally {
                webViewPool.release(webView)
            }
        }
        if (visited) crawlOutcomes.report(entry, failure)

        return ActionLogEntity(
            actionType = ActionType.COOKIE_HARVEST,
            category = category,
            detail = entry.url,
            metadata = if (entry.isCustom) {
                LogMetadata.append(metadata, LogMetadata.SOURCE to LogMetadata.SOURCE_CUSTOM)
            } else {
                LogMetadata.append(metadata, LogMetadata.PURCHASE_INTENT to crawlListManager.describeIntent(entry))
            },
            success = success
        )
    }
}
