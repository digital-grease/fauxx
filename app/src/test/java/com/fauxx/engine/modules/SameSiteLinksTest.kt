package com.fauxx.engine.modules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/** Which link browsing on within a site follows (#322 item 2). */
class SameSiteLinksTest {

    private val page = "https://www.news.example/world/"

    private fun candidates(
        hrefs: List<String>,
        visited: Set<String> = emptySet(),
        blocked: (String) -> Boolean = { false },
        sensitive: (String) -> Boolean = { false },
    ) = SameSiteLinks.candidates(hrefs, page, visited, blocked, sensitive)

    @Test
    fun `only links on the page's own site qualify, with or without www`() {
        val links = candidates(
            listOf(
                "https://news.example/world/story-1",
                "https://www.news.example/sport/",
                "https://other.example/story",
                "https://cdn.news.example/asset",
                "mailto:desk@news.example",
                "javascript:void(0)",
            ),
        )
        assertEquals(listOf("https://news.example/world/story-1", "https://www.news.example/sport/"), links)
    }

    @Test
    fun `the page itself, fragments of it and pages already seen are skipped`() {
        val links = candidates(
            listOf(
                "https://www.news.example/world/#top",
                "https://www.news.example/world",
                "https://www.news.example/a",
                "https://www.news.example/b#comments",
                "https://www.news.example/b",
            ),
            visited = setOf(SameSiteLinks.normalize("https://www.news.example/a")),
        )
        assertEquals(listOf("https://www.news.example/b"), links)
    }

    @Test
    fun `account, cart, sharing and file links are skipped`() {
        val links = candidates(
            listOf(
                "https://www.news.example/account/login",
                "https://www.news.example/cart",
                "https://www.news.example/checkout/step-1",
                "https://www.news.example/subscribe",
                "https://www.news.example/world/story?share=facebook",
                "https://www.news.example/report.pdf",
                "https://www.news.example/photo.JPG",
                "https://www.news.example/world/story-2",
            ),
        )
        assertEquals(listOf("https://www.news.example/world/story-2"), links)
    }

    @Test
    fun `the domain blocklist and the harmful-phrase check both gate a link`() {
        val links = candidates(
            listOf(
                "https://www.news.example/blocked/page",
                "https://www.news.example/world/how-to-hurt-someone",
                "https://www.news.example/world/story-3",
            ),
            blocked = { it.contains("/blocked/") },
            sensitive = { it.contains("how to hurt") },
        )
        assertEquals(listOf("https://www.news.example/world/story-3"), links)
    }

    @Test
    fun `deeper pages are likelier`() {
        val shallow = "https://www.news.example/sport/"
        val deep = "https://www.news.example/sport/2026/11/story"
        val random = Random(3)
        val picks = (1..4000).map { SameSiteLinks.pick(listOf(shallow, deep), random) }
        val deepShare = picks.count { it == deep }.toDouble() / picks.size
        // Weights 1 + depth, capped: 2 for the section, 4 for the article.
        assertEquals(4.0 / 6.0, deepShare, 0.03)
        assertNull(SameSiteLinks.pick(emptyList(), random))
    }

    @Test
    fun `browsing on is capped by the tier, and off at Max`() {
        assertEquals(2, SiteBrowser.maxPages(12))
        assertEquals(3, SiteBrowser.maxPages(60))
        assertEquals(1, SiteBrowser.maxPages(200))
        assertEquals(0, SiteBrowser.maxPages(500))
    }
}
