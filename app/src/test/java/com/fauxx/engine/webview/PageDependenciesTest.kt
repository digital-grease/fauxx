package com.fauxx.engine.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** What a site's pages load, learned from visits for DNS bursts (#322 item 6). */
class PageDependenciesTest {

    @Test
    fun `a visit's hosts are kept in the order first asked for, without the page's own host`() {
        val store = PageDependencies()
        store.record("www.news.example", listOf("cdn.news.example", "www.news.example", "fonts.gstatic.com", "cdn.news.example"))
        assertEquals(listOf("cdn.news.example", "fonts.gstatic.com"), store.dependenciesOf("WWW.news.example", Random(1)))
    }

    @Test
    fun `a site not visited yet gets a few typical hosts`() {
        val hosts = PageDependencies().dependenciesOf("unvisited.example", Random(2))
        assertTrue(hosts.size in 3..8)
        assertTrue(hosts.all { it in PageDependencies.TYPICAL })
    }

    @Test
    fun `sets are capped, and the least recently used site is dropped first`() {
        val store = PageDependencies()
        store.record("big.example", (1..60).map { "h$it.example" })
        assertEquals(PageDependencies.MAX_HOSTS, store.dependenciesOf("big.example", Random(3)).size)

        for (i in 0 until PageDependencies.MAX_SITES) store.record("site$i.example", listOf("dep$i.example"))
        store.dependenciesOf("site0.example", Random(4)) // used, so kept
        store.record("one-more.example", listOf("x.example"))
        assertEquals(listOf("dep0.example"), store.dependenciesOf("site0.example", Random(5)))
        assertTrue("site1 was the least recently used", store.dependenciesOf("site1.example", Random(6)).all { it in PageDependencies.TYPICAL })
    }

    @Test
    fun `a visit is recorded when the next page starts and at release`() {
        val recorded = mutableListOf<Pair<String, List<String>>>()
        val visit = VisitHosts(object : PageDependencySink {
            override fun record(pageHost: String, hosts: Collection<String>) { recorded += pageHost to hosts.toList() }
        })
        visit.onRequest("cdn.ignored.example", mainFrame = false) // before any page: nothing to belong to
        visit.onRequest("a.example", mainFrame = true)
        visit.onRequest("cdn.a.example", mainFrame = false)
        visit.onRequest("fonts.gstatic.com", mainFrame = false)
        visit.onRequest("b.example", mainFrame = true)
        visit.onRequest("cdn.b.example", mainFrame = false)
        visit.flush()
        visit.flush() // nothing left
        assertEquals(
            listOf("a.example" to listOf("cdn.a.example", "fonts.gstatic.com"), "b.example" to listOf("cdn.b.example")),
            recorded,
        )
    }
}
