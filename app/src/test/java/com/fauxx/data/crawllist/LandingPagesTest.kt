package com.fauxx.data.crawllist

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.modules.SameSiteLinks
import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
import com.fauxx.util.Clock
import com.google.gson.JsonParser
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI
import kotlin.random.Random

/** Advertisers' landing pages (#322 item 7): how they are drawn, and what they may never be. */
class LandingPagesTest {

    // --- what a landing page may never be ---

    @Test
    fun `sensitive purchases are caught in every language, as whole words, with or without accents`() {
        val sensitive = listOf(
            "https://shop.example/c/baby/strollers",
            "https://shop.example/health-wellness/vitamins",
            "https://shop.example/category?dept=wine",
            "https://tienda.example/bebe/carritos",
            "https://tienda.example/parafarmacia",
            "https://boutique.example/b%C3%A9b%C3%A9/poussettes",
            "https://boutique.example/vins-et-spiritueux",
            "https://magazin.example/catalog/detskoe-pitanie/",
            "https://magazin.example/catalog/%D0%B0%D0%BF%D1%82%D0%B5%D0%BA%D0%B0/",
            "https://shop.example/hunting/rifles",
            "https://genealogy.example/dna/kits",
        )
        for (url in sensitive) assertTrue(url, SensitivePurchases.mentions(url))
        val fine = listOf(
            "https://shop.example/c/camping-tents",
            "https://shop.example/unisex-hoodies",
            "https://shop.example/graphics-cards/amd-radeon-rx-7600",
            "https://shop.example/tools/hot-glue-gun",
            "https://shop.example/paris-travel-guide-book",
            "https://shop.example/kids/toys/lego",
            "not a url",
        )
        for (url in fine) assertFalse(url, SensitivePurchases.mentions(url))
    }

    @Test
    fun `purchase intents are named from the asset, by id when unknown or unreadable`() {
        val intents = PurchaseIntents.parse("""{"nodes": {"1625": {"name": "Sporting Goods > Camping and Hiking", "ancestors": [1618]}}}""")
        assertEquals("Sporting Goods > Camping and Hiking (IAB 1625)", intents.describe(1625))
        assertEquals("IAB 9", intents.describe(9))
        assertTrue(1625 in intents)
        assertEquals("IAB 1625", PurchaseIntents.parse("not json").describe(1625))
    }

    @Test
    fun `browsing on skips carts and accounts in every language`() {
        val page = "https://shop.example/c/tents"
        val links = listOf(
            "https://shop.example/bag", "https://shop.example/fr/panier", "https://shop.example/es/carrito",
            "https://shop.example/personal/korzina/", "https://shop.example/mon-compte/commandes",
            "https://shop.example/p/ultralight-tent-123",
        )
        assertEquals(
            listOf("https://shop.example/p/ultralight-tent-123"),
            SameSiteLinks.candidates(links, page, emptySet(), isBlocked = { false }, isSensitive = { false }),
        )
    }

    // --- the shipped landing pages ---

    private val assets = File("src/main/assets")
    private val corpusFiles = listOf(File(assets, "crawl_urls.json")) + (File(assets, "crawl_urls").listFiles()?.sorted() ?: emptyList())
    private val intentsAsset by lazy { JsonParser.parseString(File(assets, PurchaseIntents.ASSET_PATH).readText()).asJsonObject }

    private data class Landing(val file: String, val url: String, val category: String, val intent: Int)

    private val landing: List<Landing> by lazy {
        corpusFiles.flatMap { file ->
            JsonParser.parseString(file.readText()).asJsonArray.map { it.asJsonObject }
                .filter { it.has("intent") }
                .map { Landing(file.name, it.get("url").asString, it.get("category").asString, it.get("intent").asInt) }
        }
    }

    @Test
    fun `every locale has landing pages, and every landing page is tagged with a known node outside the exclusions`() {
        assertTrue("run from the app module root", assets.exists())
        for (file in corpusFiles) assertTrue("${file.name} has landing pages", landing.any { it.file == file.name })
        val nodes = intentsAsset.getAsJsonObject("nodes")
        val excluded = intentsAsset.getAsJsonObject("excluded").keySet().map { it.toInt() }.toSet()
        for (page in landing) {
            val node = nodes.getAsJsonObject(page.intent.toString())
            assertTrue("${page.url}: node ${page.intent} not in ${PurchaseIntents.ASSET_PATH}", node != null)
            val lineage = node.getAsJsonArray("ancestors").map { it.asInt } + page.intent
            assertTrue("${page.url}: node ${page.intent} is excluded", lineage.none { it in excluded })
        }
    }

    @Test
    fun `no landing page names a sensitive purchase, or sits in a category that gets none`() {
        val none = setOf("MEDICAL", "WELLNESS_ALTERNATIVE", "LEGAL", "POLITICS", "MILITARY_DEFENSE")
        for (page in landing) {
            assertFalse("${page.file}: ${page.url} names a sensitive purchase", SensitivePurchases.mentions(page.url))
            assertFalse("${page.file}: ${page.url} is in ${page.category}", page.category in none)
        }
    }

    @Test
    fun `landing pages are clean https addresses, listed once`() {
        for (page in landing) {
            val uri = URI(page.url)
            assertEquals(page.url, "https", uri.scheme)
            val query = uri.rawQuery.orEmpty().lowercase()
            assertFalse("${page.url} carries tracking", listOf("utm_", "gclid", "fbclid", "ref=", "affid").any { it in query })
        }
        // The editorial lists repeat some pages under two categories; a landing page is listed once.
        for (file in corpusFiles) {
            val urls = JsonParser.parseString(file.readText()).asJsonArray.map { it.asJsonObject.get("url").asString.trimEnd('/') }
            val counts = urls.groupingBy { it }.eachCount()
            for (page in landing.filter { it.file == file.name }) {
                assertEquals("${file.name} lists ${page.url} more than once", 1, counts[page.url.trimEnd('/')])
            }
        }
    }

    // --- how the crawl list draws them ---

    private val editorial = buildList {
        for (i in 1..20) add(CrawlEntry("https://cook$i.example/recipes", "cook$i.example", CategoryPool.COOKING))
        for (i in 1..20) add(CrawlEntry("https://news$i.example/politics", "news$i.example", CategoryPool.POLITICS))
    }
    private val shops = (1..5).map { CrawlEntry("https://pans$it.example/c/skillets", "pans$it.example", CategoryPool.COOKING, intent = 1300) }

    private class FixedClock : Clock {
        override fun currentTimeMillis() = 1_000_000_000_000L
        override fun elapsedRealtime() = 1_000_000_000_000L
    }

    private fun manager(habits: SiteHabitsSource, entries: List<CrawlEntry> = editorial + shops): CrawlListManager {
        val localeManager: LocaleManager = mockk(relaxed = true) { every { currentLocale } returns SupportedLocale.EN }
        val blocklist: DomainBlocklist = mockk { every { isUrlBlocked(any()) } returns false }
        return CrawlListManager(mockk(relaxed = true), blocklist, localeManager, FixedClock(), Random(7), siteHabits = habits).apply {
            replaceUrlsForTest(SupportedLocale.EN, entries)
            replacePopularityForTest(SitePopularity.EVEN)
        }
    }

    private fun picks(manager: CrawlListManager, category: CategoryPool, n: Int = 4000): List<CrawlEntry> =
        (1..n).mapNotNull { manager.nextUrlOrWait(category)?.entry?.also { e -> manager.markVisited(e.domain, 0L) } }

    @Test
    fun `a persona's landing share of draws goes to landing pages, the rest to editorial pages`() {
        val key = SiteHabitsKey("persona-3", setOf(CategoryPool.COOKING))
        val share = HabitualSites.derive(key.key, key.interests, editorial + shops, SitePopularity.EVEN, "en").landingShare
        assertTrue("share $share", share in 0.125..0.285)
        val drawn = picks(manager({ key }), CategoryPool.COOKING)
        assertEquals(share, drawn.count { it.isLanding }.toDouble() / drawn.size, 0.025)
        assertTrue(drawn.all { it.category == CategoryPool.COOKING })
    }

    @Test
    fun `a category without landing pages, or no persona, draws editorial pages only`() {
        val key = SiteHabitsKey("persona-3", setOf(CategoryPool.COOKING))
        assertTrue(picks(manager({ key }), CategoryPool.POLITICS, n = 500).none { it.isLanding })
        assertTrue(picks(manager(SiteHabitsSource.NONE), CategoryPool.COOKING, n = 500).none { it.isLanding })
    }

    @Test
    fun `landing shares are drawn per persona, and favourites stay editorial`() {
        val shares = (1..200).map { HabitualSites.derive("p$it", emptySet(), editorial + shops, SitePopularity.EVEN, "en").landingShare }
        assertTrue(shares.all { it in 0.125..0.285 })
        assertTrue("personas differ", shares.distinct().size > 50)
        val favourites = (1..50).flatMap { HabitualSites.derive("p$it", setOf(CategoryPool.COOKING), editorial + shops, SitePopularity.EVEN, "en").sites }
        assertTrue(favourites.none { it.startsWith("pans") })
    }

    @Test
    fun `shop hosts are the landing pages' sites, and only their sensitive pages are flagged`() {
        val manager = manager(SiteHabitsSource.NONE)
        assertTrue(manager.isShopHost("www.pans1.example"))
        assertFalse(manager.isShopHost("cook1.example"))
        assertTrue(manager.isSensitiveShopPage("https://pans1.example/c/baby"))
        assertFalse(manager.isSensitiveShopPage("https://pans1.example/c/dutch-ovens"))
        assertFalse("an editorial site keeps its health pages", manager.isSensitiveShopPage("https://cook1.example/health"))
        // The test context has no purchase_intents.json, so the node is named by its id.
        assertEquals("IAB 1300", manager.describeIntent(shops.first()))
        assertNull(manager.describeIntent(editorial.first()))
    }
}
