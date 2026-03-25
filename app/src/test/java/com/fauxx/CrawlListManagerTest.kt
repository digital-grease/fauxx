package com.fauxx

import com.fauxx.data.crawllist.CrawlEntry
import com.fauxx.data.crawllist.CrawlListManager
import com.fauxx.data.crawllist.DomainBlocklist
import com.fauxx.data.querybank.CategoryPool
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CrawlListManagerTest {

    private lateinit var manager: CrawlListManager
    private val blocklist: DomainBlocklist = mockk()

    @Before
    fun setup() {
        every { blocklist.isUrlBlocked(any()) } returns false
    }

    @Test
    fun `isEligible returns true when domain never visited`() {
        val manager = createManagerWithUrls(emptyList())
        assertTrue(manager.isEligible("example.com"))
    }

    @Test
    fun `isEligible returns false within rate limit window`() {
        val manager = createManagerWithUrls(emptyList())
        val now = System.currentTimeMillis()
        manager.markVisited("example.com", now)
        assertTrue(!manager.isEligible("example.com", now + 1000)) // 1s later
    }

    @Test
    fun `isEligible returns true after rate limit window`() {
        val manager = createManagerWithUrls(emptyList())
        val now = System.currentTimeMillis()
        manager.markVisited("example.com", now)
        assertTrue(manager.isEligible("example.com", now + 5_001)) // 5s+ later
    }

    @Test
    fun `rate limit is per-domain not global`() {
        val manager = createManagerWithUrls(emptyList())
        val now = System.currentTimeMillis()
        manager.markVisited("a.com", now)
        assertTrue("Different domain should be eligible", manager.isEligible("b.com", now))
    }

    @Test
    fun `nextUrl enforces rate limit`() {
        val entries = listOf(
            CrawlEntry("https://only.com/page", "only.com", CategoryPool.GAMING)
        )
        val manager = createManagerWithUrls(entries)

        // First call should succeed
        val first = manager.nextUrl()
        assertNotNull("First request should return a URL", first)

        // Immediate second call to same domain should fail (rate limited)
        val second = manager.nextUrl()
        assertNull("Second request within rate window should return null", second)
    }

    @Test
    fun `nextUrl respects blocklist`() {
        every { blocklist.isUrlBlocked("https://blocked.com/page") } returns true
        val entries = listOf(
            CrawlEntry("https://blocked.com/page", "blocked.com", CategoryPool.GAMING)
        )
        val manager = createManagerWithUrls(entries)
        val result = manager.nextUrl()
        assertNull("Blocked URL should not be returned", result)
    }

    private fun createManagerWithUrls(urls: List<CrawlEntry>): CrawlListManager {
        val context: android.content.Context = mockk(relaxed = true)
        val manager = CrawlListManager(context, blocklist)
        // Use reflection to set the allUrls field since it's loaded from assets
        val field = CrawlListManager::class.java.getDeclaredField("allUrls\$delegate")
        field.isAccessible = true
        field.set(manager, lazy { urls })
        return manager
    }
}
