package com.fauxx.engine.modules

import timber.log.Timber
import com.fauxx.data.crawllist.CrawlListManager
import com.fauxx.data.crawllist.DomainBlocklist
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.db.LogMetadata
import com.fauxx.data.model.ActionType
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.di.IoDispatcher
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.dns.CustomDns
import com.fauxx.engine.webview.PageDependencySource
import com.fauxx.network.dns.Resolution
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Resolves diverse domain names to generate DNS query noise visible to ISP and
 * network-level trackers. Uses domains from the crawl URL corpus.
 *
 * Each action is a burst shaped like a page load (#322 item 6): the site, then the hosts its
 * pages load ([PageDependencySource]: learned from Fauxx's own visits to it, or ones pages commonly
 * load), within about a second, as a browser asks for them. A lookup of one domain on its own is
 * easy to tell from browsing; a burst with a page's dependency set reads as one.
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
    /** The hosts a site's pages load, looked up after it (#322 item 6). None: the site alone. */
    private val dependencies: PageDependencySource = PageDependencySource.NONE,
    /** Gates the dependency hosts too; the site itself comes from the filtered crawl list. */
    private val blocklist: DomainBlocklist? = null,
    private val random: Random = Random.Default,
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

        val ipCount = lookup(entry.domain, entry.isCustom)
        val success = ipCount != null

        // #322 item 6: the rest of the page's lookups follow the document's, as a browser makes
        // them: a short wait while the page starts to parse, then one after another, all within
        // about a second.
        val others = runCatching { dependencies.dependenciesOf(entry.domain, random) }.getOrDefault(emptyList())
            .filterNot { blocklist?.isBlocked(it) == true }
            .take(MAX_BURST_LOOKUPS)
        if (success && others.isNotEmpty()) {
            delay(random.nextLong(PARSE_DELAY_MS.first, PARSE_DELAY_MS.last + 1))
            for (host in others) {
                lookup(host, isCustom = false)
                delay(random.nextLong(LOOKUP_GAP_MS.first, LOOKUP_GAP_MS.last + 1))
            }
        }

        return ActionLogEntity(
            actionType = ActionType.DNS_LOOKUP,
            category = category,
            detail = entry.domain,
            metadata = LogMetadata.toJson(
                LogMetadata.DNS_IPS to ipCount?.toString(),
                LogMetadata.DNS_LOOKUPS to (1 + others.size).takeIf { success && others.isNotEmpty() }?.toString(),
                LogMetadata.SOURCE to LogMetadata.SOURCE_CUSTOM.takeIf { entry.isCustom },
            ),
            success = success
        )
    }

    /**
     * Look [host] up through the custom resolver when one is set for noise, else the device's;
     * the number of addresses, or null when it did not resolve.
     */
    private suspend fun lookup(host: String, isCustom: Boolean): Int? = withContext(ioDispatcher) {
        val custom = customDns.noiseResolver()
        if (custom != null) {
            when (val r = custom.resolve(host)) {
                is Resolution.Addresses -> r.addresses.size
                Resolution.NoSuchHost, is Resolution.Failure -> null
            }
        } else {
            try {
                InetAddress.getAllByName(host).size
            } catch (e: Exception) {
                // A user-added site (#328) stays out of the debug log.
                Timber.d("DNS lookup failed for ${if (isCustom) "a custom site" else host}: ${e.message}")
                null
            }
        }
    }

    private companion object {
        /** At most this many dependency lookups follow the site's. */
        const val MAX_BURST_LOOKUPS = 15

        /** Wait after the site's lookup while the page starts to parse. */
        val PARSE_DELAY_MS = 50L..250L

        /** Between one dependency lookup and the next. */
        val LOOKUP_GAP_MS = 5L..120L
    }
}
