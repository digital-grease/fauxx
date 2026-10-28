package com.fauxx.engine.modules

import timber.log.Timber
import com.fauxx.data.crawllist.CrawlListManager
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.db.LogMetadata
import com.fauxx.data.model.ActionType
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.di.IoDispatcher
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.dns.CustomDns
import com.fauxx.network.dns.Resolution
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves diverse domain names to generate DNS query noise visible to ISP and
 * network-level trackers. Uses domains from the crawl URL corpus.
 *
 * Lookups go to the device's resolver, whose logs (the ISP's, the network's) are the point. When
 * the user has custom DNS active and turned on "also use it for DNS noise" (#227), they go to the
 * custom resolver instead, so only that resolver's logs see them.
 */
@Singleton
class DnsNoiseModule @Inject constructor(
    private val crawlListManager: CrawlListManager,
    private val profileRepo: PoisonProfileRepository,
    private val customDns: CustomDns = CustomDns.NONE,
    /** Where the blocking lookup runs. Tests pass their own, so a lookup keeps to virtual time (#322). */
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
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

        var ipCount: Int? = null
        val success = withContext(ioDispatcher) {
            val custom = customDns.noiseResolver()
            if (custom != null) {
                when (val r = custom.resolve(entry.domain)) {
                    is Resolution.Addresses -> { ipCount = r.addresses.size; true }
                    Resolution.NoSuchHost, is Resolution.Failure -> false
                }
            } else {
                try {
                    ipCount = InetAddress.getAllByName(entry.domain).size
                    true
                } catch (e: Exception) {
                    // A user-added site (#328) stays out of the debug log.
                    Timber.d("DNS lookup failed for ${if (entry.isCustom) "a custom site" else entry.domain}: ${e.message}")
                    false
                }
            }
        }

        return ActionLogEntity(
            actionType = ActionType.DNS_LOOKUP,
            category = category,
            detail = entry.domain,
            metadata = LogMetadata.toJson(
                LogMetadata.DNS_IPS to ipCount?.toString(),
                LogMetadata.SOURCE to LogMetadata.SOURCE_CUSTOM.takeIf { entry.isCustom },
            ),
            success = success
        )
    }
}
