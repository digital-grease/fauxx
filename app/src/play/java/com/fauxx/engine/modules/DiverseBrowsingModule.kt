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
 * Play Store-safe alternative to AdPollutionModule.
 *
 * Visits diverse pages from [CrawlListManager] across varied categories to broaden
 * the user's browsing profile. Does NOT click ads, simulate CTR, or deliberately
 * visit ad preference dashboards for poisoning purposes.
 */
@Singleton
class DiverseBrowsingModule @Inject constructor(
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
        val pending = crawlListManager.nextUrlOrWait(category)
            ?: crawlListManager.nextUrlOrWait(null)

        if (pending == null) {
            return ActionLogEntity(
                actionType = ActionType.PAGE_VISIT,
                category = category,
                detail = "No eligible URL available",
                success = false
            )
        }

        if (pending.waitMs > 0) {
            delay(pending.waitMs)
            crawlListManager.markVisited(pending.entry.domain)
        }

        val url = pending.entry.url
        val dwellMs = Random.nextLong(3_000L, 15_000L)

        val success = withContext(Dispatchers.Main) {
            val webView = webViewPool.acquire()
            try {
                webView.loadUrl(url)
                delay(dwellMs)
                true
            } catch (e: Exception) {
                Timber.w("Diverse browsing failed: ${e.message}")
                false
            } finally {
                webViewPool.release(webView)
            }
        }

        return ActionLogEntity(
            actionType = ActionType.PAGE_VISIT,
            category = category,
            detail = url,
            success = success
        )
    }
}
