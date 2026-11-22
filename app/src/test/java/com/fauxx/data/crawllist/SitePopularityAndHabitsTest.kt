package com.fauxx.data.crawllist

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
import com.fauxx.util.Clock
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** Site popularity (#322 item 8) and per-persona favourite sites (#322 item 3), and how the crawl list draws with them. */
class SitePopularityAndHabitsTest {

    private val popularity = SitePopularity.parse(
        """{"source": "test", "ranks": {"big.example": 50, "mid.example": 5000, "small.example": 500000, "bbc.co.uk": 300}}"""
    )

    @Test
    fun `a host belongs to the longest ranked suffix, or to itself without www`() {
        assertEquals("bbc.co.uk", popularity.siteOf("www.bbc.co.uk"))
        assertEquals("big.example", popularity.siteOf("news.big.example"))
        assertEquals("unknown.example", popularity.siteOf("www.unknown.example"))
    }

    @Test
    fun `weight falls with rank, the top is capped, and unranked sites weigh least`() {
        val big = popularity.weightOf("big.example")
        val mid = popularity.weightOf("mid.example")
        val small = popularity.weightOf("small.example")
        val unranked = popularity.weightOf("unknown.example")
        assertTrue(big > mid && mid > small && small > unranked)
        assertEquals(Math.pow(300.0, -SitePopularity.ALPHA), popularity.weightOf("bbc.co.uk"), 1e-12)
        assertEquals("rank 50 counts as the cap of 100", Math.pow(SitePopularity.RANK_CAP.toDouble(), -SitePopularity.ALPHA), big, 1e-12)
    }

    @Test
    fun `an unreadable asset draws evenly`() {
        val even = SitePopularity.parse("not json")
        assertEquals(even.weightOf("a.example"), even.weightOf("b.example"), 0.0)
    }

    @Test
    fun `the shipped asset ranks most of the crawl list`() {
        val asset = File("src/main/assets/site_popularity.json")
        assertTrue("run from the app module root", asset.exists())
        val shipped = SitePopularity.parse(asset.readText())
        assertTrue(shipped.rankOf("google.com") == 1)
        assertTrue(shipped.rankOf("webmd.com") != null)
    }

    // --- favourites ---

    private fun entry(host: String, category: CategoryPool, path: String = "/") =
        CrawlEntry("https://$host$path", host, category)

    private val corpus = buildList {
        for (i in 1..40) add(entry("cook$i.example", CategoryPool.COOKING))
        for (i in 1..40) add(entry("law$i.example", CategoryPool.LEGAL))
        add(entry("big.example", CategoryPool.COOKING))
        add(entry("big.example", CategoryPool.COOKING, "/recipes"))
    }

    @Test
    fun `favourites are stable, five to fifteen, from the persona's interests`() {
        val a = HabitualSites.derive("persona-1", setOf(CategoryPool.COOKING), corpus, popularity, "en")
        assertEquals(a, HabitualSites.derive("persona-1", setOf(CategoryPool.COOKING), corpus, popularity, "en"))
        assertTrue("count ${a.sites.size}", a.sites.size in 5..15)
        assertTrue("all from the interest categories", a.sites.all { it.startsWith("cook") || it == "big.example" })
        assertTrue("share ${a.share}", a.share in 0.3..0.6)
        assertEquals("one site per domain", a.sites.size, a.sites.distinct().size)
    }

    @Test
    fun `personas get different favourites`() {
        val sets = (1..20).map { HabitualSites.derive("persona-$it", setOf(CategoryPool.COOKING), corpus, popularity, "en").sites.toSet() }
        assertTrue(sets.distinct().size > 15)
    }

    @Test
    fun `a popular site is more often a favourite`() {
        val withBig = (1..200).count {
            "big.example" in HabitualSites.derive("p$it", setOf(CategoryPool.COOKING), corpus, popularity, "en").sites
        }
        assertTrue("big.example a favourite for $withBig of 200 personas", withBig > 150)
    }

    // --- the crawl list's draw ---

    private class FixedClock : Clock {
        override fun currentTimeMillis() = 1_000_000_000_000L
        override fun elapsedRealtime() = 1_000_000_000_000L
    }

    private fun manager(habits: SiteHabitsSource, entries: List<CrawlEntry> = corpus, seed: Int = 1): CrawlListManager {
        val localeManager: LocaleManager = mockk(relaxed = true) { every { currentLocale } returns SupportedLocale.EN }
        val blocklist: DomainBlocklist = mockk { every { isUrlBlocked(any()) } returns false }
        return CrawlListManager(
            mockk(relaxed = true), blocklist, localeManager, FixedClock(), Random(seed),
            siteHabits = habits,
        ).apply {
            replaceUrlsForTest(SupportedLocale.EN, entries)
            replacePopularityForTest(popularity)
        }
    }

    /** Picks over many calls; the per-domain rate limit is cleared before each so every site stays eligible. */
    private fun picks(manager: CrawlListManager, category: CategoryPool, n: Int = 4000): List<CrawlEntry> =
        (1..n).mapNotNull {
            manager.nextUrl(category)?.also { e -> manager.markVisited(e.domain, 0L) }
        }

    @Test
    fun `sites are drawn by popularity, and a site's pages share its weight`() {
        val ranked = listOf(
            entry("big.example", CategoryPool.COOKING), entry("big.example", CategoryPool.COOKING, "/a"),
            entry("big.example", CategoryPool.COOKING, "/b"), entry("small.example", CategoryPool.COOKING),
        )
        val drawn = picks(manager(SiteHabitsSource.NONE, ranked), CategoryPool.COOKING)
        val bigShare = drawn.count { it.domain == "big.example" }.toDouble() / drawn.size
        val expected = popularity.weightOf("big.example") /
            (popularity.weightOf("big.example") + popularity.weightOf("small.example"))
        assertEquals("site-level share, not page-level", expected, bigShare, 0.03)
    }

    @Test
    fun `a share of visits goes to the persona's favourites`() {
        val key = SiteHabitsKey("persona-7", setOf(CategoryPool.COOKING))
        val favourites = HabitualSites.derive(key.key, key.interests, corpus, popularity, "en")
        val drawn = picks(manager({ key }), CategoryPool.COOKING)
        val toFavourites = drawn.count { popularity.siteOf(it.domain) in favourites.sites }.toDouble() / drawn.size
        assertTrue("favourites took $toFavourites with a share of ${favourites.share}", toFavourites >= favourites.share)
        val first = drawn.count { popularity.siteOf(it.domain) == favourites.sites.first() }
        val last = drawn.count { popularity.siteOf(it.domain) == favourites.sites.last() }
        assertTrue("Zipf: the first favourite is visited more than the last ($first vs $last)", first > last)
    }

    @Test
    fun `a category without favourites draws by popularity only`() {
        val key = SiteHabitsKey("persona-7", setOf(CategoryPool.COOKING))
        val favourites = HabitualSites.derive(key.key, key.interests, corpus, popularity, "en")
        val drawn = picks(manager({ key }), CategoryPool.LEGAL, n = 500)
        assertTrue(drawn.none { popularity.siteOf(it.domain) in favourites.sites })
        assertTrue(drawn.all { it.category == CategoryPool.LEGAL })
    }

    @Test
    fun `favourites follow the persona`() {
        var key = SiteHabitsKey("persona-a", setOf(CategoryPool.COOKING))
        val manager = manager({ key })
        picks(manager, CategoryPool.COOKING, n = 100)
        key = SiteHabitsKey("persona-b", setOf(CategoryPool.COOKING))
        val b = HabitualSites.derive(key.key, key.interests, corpus, popularity, "en")
        assertNotEquals(HabitualSites.derive("persona-a", key.interests, corpus, popularity, "en").sites, b.sites)
        val drawn = picks(manager, CategoryPool.COOKING)
        val toB = drawn.count { popularity.siteOf(it.domain) in b.sites }.toDouble() / drawn.size
        assertTrue("the new persona's favourites took $toB with a share of ${b.share}", toB >= b.share)
    }
}
