package com.fauxx.engine.modules

import timber.log.Timber
import com.fauxx.data.crawllist.CrawlListManager
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.model.ActionType
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.PoisonProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves diverse domain names to generate DNS query noise visible to ISP and
 * network-level trackers. Uses domains from the crawl URL corpus.
 */
@Singleton
class DnsNoiseModule @Inject constructor(
    private val crawlListManager: CrawlListManager,
    private val profileRepo: PoisonProfileRepository
) : Module {

    override suspend fun start() {}
    override suspend fun stop() {}

    override fun isEnabled(): Boolean = profileRepo.getProfile().dnsNoiseEnabled

    override suspend fun onAction(category: CategoryPool): ActionLogEntity {
        val pending = crawlListManager.nextUrlOrWait(category)
            ?: crawlListManager.nextUrlOrWait(null)

        if (pending == null) {
            return ActionLogEntity(
                actionType = ActionType.DNS_LOOKUP,
                category = category,
                detail = "No eligible domain",
                success = false
            )
        }

        if (pending.waitMs > 0) {
            delay(pending.waitMs)
            crawlListManager.markVisited(pending.entry.domain)
        }

        val entry = pending.entry

        val success = withContext(Dispatchers.IO) {
            try {
                InetAddress.getByName(entry.domain)
                true
            } catch (e: Exception) {
                Timber.d("DNS lookup failed for ${entry.domain}: ${e.message}")
                false
            }
        }

        return ActionLogEntity(
            actionType = ActionType.DNS_LOOKUP,
            category = category,
            detail = entry.domain,
            success = success
        )
    }
}
