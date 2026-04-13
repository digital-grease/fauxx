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
    private val profileRepo: PoisonProfileRepository
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

        val dwellMs = Random.nextLong(2_000L, 10_000L) // 2-10 second dwell

        val success = withContext(Dispatchers.Main) {
            val webView = webViewPool.acquire()
            try {
                webView.loadUrl(entry.url)
                delay(dwellMs)
                true
            } catch (e: Exception) {
                Timber.w("Failed to load ${entry.url}: ${e.message}")
                false
            } finally {
                webViewPool.release(webView)
            }
        }

        return ActionLogEntity(
            actionType = ActionType.COOKIE_HARVEST,
            category = category,
            detail = entry.url,
            success = success
        )
    }
}
