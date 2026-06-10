package com.fauxx

import com.fauxx.engine.modules.SerpLinkSelector
import com.google.gson.Gson
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E5 (#175): the SERP link filter decides what the engine is willing to NAVIGATE to.
 * Engine/ad-infra hosts are never results; every candidate passes the caller's
 * (fail-closed) blocklist gate; one link per host.
 */
class SerpLinkSelectorTest {

    private val allowAll: (String) -> Boolean = { true }
    private val never: (String) -> Boolean = { false }

    private fun select(
        hrefs: List<String>,
        max: Int = 5,
        random: Random = Random(1),
        isAllowed: (String) -> Boolean = allowAll,
        isBlocked: (String) -> Boolean = never,
    ) = SerpLinkSelector.select(hrefs, random, max, isAllowed, isBlocked)

    @Test
    fun `engine and ad-infra hosts are never selected`() {
        val hrefs = listOf(
            "https://www.google.com/preferences",
            "https://accounts.google.com/signin",
            "https://www.googleadservices.com/pagead/x",
            "https://duckduckgo.com/settings",
            "https://r.bing.com/rp/abc",
            "https://yandex.com/support",
            "https://example.com/article",
        )
        assertEquals(listOf("https://example.com/article"), select(hrefs))
    }

    @Test
    fun `engine families match whole host labels not substrings`() {
        // "googleblog.com" contains "google" as a substring but not as a label.
        val hrefs = listOf("https://googleblog.com/post", "https://blog.google/post")
        assertEquals(listOf("https://googleblog.com/post"), select(hrefs))
    }

    @Test
    fun `only allow-listed hosts survive - the primary gate`() {
        val hrefs = listOf(
            "https://curated.example.com/article",
            "https://random-serp-result.net/page",
        )
        val picked = select(hrefs, isAllowed = { it == "curated.example.com" })
        assertEquals(listOf("https://curated.example.com/article"), picked)
    }

    @Test
    fun `non-http schemes and unparseable urls are dropped`() {
        val hrefs = listOf(
            "javascript:void(0)",
            "intent://foo#Intent;end",
            "ht!tp://broken",
            "https://ok.example.net/page",
        )
        assertEquals(listOf("https://ok.example.net/page"), select(hrefs))
    }

    @Test
    fun `one link per host and max respected`() {
        val hrefs = (1..10).map { "https://same.example.com/p$it" } +
            listOf("https://a.example.net/1", "https://b.example.org/2")
        val picked = select(hrefs, max = 2, random = Random(3))
        assertEquals(2, picked.size)
        assertEquals(2, picked.map { java.net.URI(it).host }.toSet().size)
    }

    @Test
    fun `blocklisted candidates are gated out`() {
        val hrefs = listOf("https://blocked.example.com/x", "https://fine.example.org/y")
        val picked = select(hrefs, max = 2, isBlocked = { it.contains("blocked") })
        assertEquals(listOf("https://fine.example.org/y"), picked)
    }

    @Test
    fun `selection is position-biased toward top results`() {
        val hrefs = listOf(
            "https://first.example.com/1",
            "https://second.example.net/2",
            "https://third.example.org/3",
        )
        val random = Random(11)
        val firstCounts = (1..600).count { select(hrefs, max = 1, random = random).single().contains("first") }
        assertTrue(
            "top result should dominate clicks, got $firstCounts/600",
            firstCounts in 250..450 // ~55% expected
        )
    }

    @Test
    fun `zero max yields nothing`() {
        assertTrue(select(listOf("https://example.com"), max = 0).isEmpty())
    }

    @Test
    fun `click js targets the exact href and escapes quotes`() {
        val js = SerpLinkSelector.buildClickJs("https://example.com/a?q=it's")
        assertTrue(js.contains("""https://example.com/a?q=it\'s"""))
        assertTrue(js.contains(".click()"))
    }

    @Test
    fun `parseHrefs decodes the double-encoded evaluateJavascript result`() {
        val inner = """["https://example.com/a","https://example.org/b"]"""
        val outer = Gson().toJson(inner)
        assertEquals(
            listOf("https://example.com/a", "https://example.org/b"),
            SerpLinkSelector.parseHrefs(outer)
        )
    }

    @Test
    fun `parseHrefs is safe on null garbage and mismatched json`() {
        assertTrue(SerpLinkSelector.parseHrefs(null).isEmpty())
        assertTrue(SerpLinkSelector.parseHrefs("null").isEmpty())
        assertTrue(SerpLinkSelector.parseHrefs("not json").isEmpty())
        assertTrue(SerpLinkSelector.parseHrefs("\"not an array\"").isEmpty())
    }
}
