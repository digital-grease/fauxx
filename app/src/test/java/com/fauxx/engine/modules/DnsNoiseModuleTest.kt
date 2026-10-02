package com.fauxx.engine.modules

import com.fauxx.data.crawllist.CrawlEntry
import com.fauxx.data.crawllist.CrawlListManager
import com.fauxx.data.crawllist.PendingCrawlEntry
import com.fauxx.data.model.ActionType
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.PoisonProfileRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DnsNoiseModule.onAction] resolves a domain from the crawl corpus to emit DNS-query noise.
 * It always logs a DNS_LOOKUP action.
 *
 * Plain JVM: only [CrawlListManager] (mocked) and java.net.InetAddress are exercised — no
 * android.* framework — so no Robolectric runner. The real resolution path
 * ([InetAddress.getByName]) has no test seam and CI may be offline, so the resolve-branch
 * test deliberately asserts only the action type / detail / category and NOT the success flag.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DnsNoiseModuleTest {

    private val crawlListManager: CrawlListManager = mockk(relaxed = true)
    private val profileRepo: PoisonProfileRepository = mockk(relaxed = true)

    private fun newModule() = DnsNoiseModule(
        crawlListManager = crawlListManager,
        profileRepo = profileRepo,
    )

    // (i) Corpus exhausted: nextUrlOrWait returns null for both category and null fallback ->
    // a failed DNS_LOOKUP with the "No eligible domain" sentinel and no resolution attempt.
    @Test
    fun `onAction reports no eligible domain when the corpus is empty`() = runTest {
        every { crawlListManager.nextUrlOrWait(any()) } returns null

        val result = newModule().onAction(CategoryPool.GAMING)

        assertEquals(ActionType.DNS_LOOKUP, result.actionType)
        assertFalse("an exhausted corpus must report failure", result.success)
        assertEquals("No eligible domain", result.detail)
    }

    // (ii) A domain is available (waitMs=0, so no delay/markVisited) -> resolve path runs.
    // We assert only the type/detail/category; the success boolean depends on a live DNS
    // lookup (InetAddress.getByName) which has no seam and may be offline on CI.
    @Test
    fun `onAction logs the resolved domain as a DNS_LOOKUP for the category`() = runTest {
        val pending = PendingCrawlEntry(
            entry = CrawlEntry("https://example.com/page", "example.com", CategoryPool.GAMING),
            waitMs = 0L
        )
        every { crawlListManager.nextUrlOrWait(any()) } returns pending

        val result = newModule().onAction(CategoryPool.GAMING)

        assertEquals(ActionType.DNS_LOOKUP, result.actionType)
        assertEquals("detail must be the resolved domain", "example.com", result.detail)
        assertEquals(CategoryPool.GAMING, result.category)
    }

    private fun customDns(resolver: com.fauxx.network.dns.HostResolver?) = object : com.fauxx.engine.dns.CustomDns {
        override val health = kotlinx.coroutines.flow.MutableStateFlow<com.fauxx.network.dns.DnsHealth>(com.fauxx.network.dns.DnsHealth.Off)
        override suspend fun start() {}
        override suspend fun stop() {}
        override fun noiseResolver() = resolver
    }

    @Test
    fun `with the noise toggle on, lookups go to the custom resolver (#227)`() = runTest {
        every { crawlListManager.nextUrlOrWait(any()) } returns
            PendingCrawlEntry(CrawlEntry("https://example.com/page", "example.com", CategoryPool.GAMING), waitMs = 0L)
        val asked = mutableListOf<String>()
        val resolver = com.fauxx.network.dns.HostResolver { host ->
            asked += host
            com.fauxx.network.dns.Resolution.Addresses(listOf(java.net.InetAddress.getByName("192.0.2.1"), java.net.InetAddress.getByName("192.0.2.2")))
        }

        val result = DnsNoiseModule(crawlListManager, profileRepo, customDns(resolver)).onAction(CategoryPool.GAMING)

        assertEquals(listOf("example.com"), asked)
        assertTrue(result.success)
        assertTrue("the address count is logged", result.metadata!!.contains("2"))
    }

    @Test
    fun `a no-such-host answer from the custom resolver is a failed lookup`() = runTest {
        every { crawlListManager.nextUrlOrWait(any()) } returns
            PendingCrawlEntry(CrawlEntry("https://blocked.example/page", "blocked.example", CategoryPool.GAMING), waitMs = 0L)
        val resolver = com.fauxx.network.dns.HostResolver { com.fauxx.network.dns.Resolution.NoSuchHost }

        val result = DnsNoiseModule(crawlListManager, profileRepo, customDns(resolver)).onAction(CategoryPool.GAMING)

        assertFalse(result.success)
    }
}
