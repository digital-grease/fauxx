package com.fauxx

import com.fauxx.data.crawllist.CrawlEntry
import com.fauxx.data.crawllist.CrawlListManager
import com.fauxx.data.crawllist.DomainBlocklist
import com.fauxx.data.querybank.CategoryPool
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
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

    @Test
    fun `nextUrlOrWait returns immediately when URL is eligible`() {
        val entries = listOf(
            CrawlEntry("https://a.com/page", "a.com", CategoryPool.GAMING)
        )
        val manager = createManagerWithUrls(entries)
        val result = manager.nextUrlOrWait(CategoryPool.GAMING)
        assertNotNull("Should return an entry", result)
        assertEquals("Wait should be 0 for an eligible URL", 0L, result!!.waitMs)
        assertEquals("a.com", result.entry.domain)
    }

    @Test
    fun `nextUrlOrWait returns wait time when all domains rate-limited`() {
        val entries = listOf(
            CrawlEntry("https://a.com/page", "a.com", CategoryPool.GAMING),
            CrawlEntry("https://b.com/page", "b.com", CategoryPool.GAMING)
        )
        val manager = createManagerWithUrls(entries)
        val now = System.currentTimeMillis()
        // Rate-limit both domains: a.com at now, b.com 2s before now
        manager.markVisited("a.com", now)
        manager.markVisited("b.com", now - 2_000)

        val result = manager.nextUrlOrWait(CategoryPool.GAMING)
        assertNotNull("Should return a pending entry even when all are rate-limited", result)
        assertTrue("Wait should be > 0", result!!.waitMs > 0)
        // b.com was visited 2s ago, so its wait should be ~3s (5s - 2s)
        assertTrue("Should pick b.com (shortest wait)", result.waitMs <= 3_100)
        assertEquals("b.com", result.entry.domain)
    }

    @Test
    fun `nextUrlOrWait returns null when corpus is empty for category`() {
        val entries = listOf(
            CrawlEntry("https://a.com/page", "a.com", CategoryPool.GAMING)
        )
        // Block the only URL
        every { blocklist.isUrlBlocked("https://a.com/page") } returns true
        val manager = createManagerWithUrls(entries)
        val result = manager.nextUrlOrWait(CategoryPool.GAMING)
        assertNull("Should return null when all URLs are blocked", result)
    }

    @Test
    fun `nextUrlOrWait falls back to any category`() {
        val entries = listOf(
            CrawlEntry("https://a.com/page", "a.com", CategoryPool.COOKING)
        )
        val manager = createManagerWithUrls(entries)
        // No GAMING URLs exist, but fallback to any category should find COOKING
        val result = manager.nextUrlOrWait(CategoryPool.GAMING)
        assertNotNull("Should fall back to any-category URL", result)
        assertEquals(CategoryPool.COOKING, result!!.entry.category)
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
