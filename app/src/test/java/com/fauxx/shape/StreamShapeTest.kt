package com.fauxx.shape

import com.fauxx.data.model.ActionType
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the metric definitions [StreamShape] reports (#322 step 0) on small hand-built streams, so
 * a number in the shape report means what its row says.
 */
class StreamShapeTest {

    /** Local midnight on Monday 2026-01-05. */
    private val start: Long = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.JANUARY, 5, 0, 0, 0)
    }.timeInMillis

    private fun at(day: Int, hour: Int, minute: Int = 0, second: Int = 0): Long =
        Calendar.getInstance().apply {
            timeInMillis = start
            add(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
        }.timeInMillis

    private fun page(atMs: Long, url: String, fromSearch: Boolean = false) =
        StreamEvent(atMs, LoadKind.PAGE, java.net.URI(url).host, url, fromSearch = fromSearch)

    private fun serp(atMs: Long, engine: String = "google") =
        StreamEvent(atMs, LoadKind.SERP, "www.google.com", "https://www.google.com/search?q=x", engine)

    private fun dns(atMs: Long, host: String = "example.com") = StreamEvent(atMs, LoadKind.DNS, host)

    private fun measure(
        events: List<StreamEvent>,
        days: Int = 1,
        actions: List<ActionRecord> = emptyList(),
    ) = StreamShape.measure(StreamRecording(events, actions, start, days, allowedStart = 7, allowedEnd = 23))

    @Test
    fun `sessions split at an idle gap longer than thirty minutes`() {
        val shape = measure(
            listOf(
                page(at(0, 9, 0), "https://www.a.com/x"),
                page(at(0, 9, 1), "https://a.com/y"),
                page(at(0, 9, 2), "https://b.com/"),
                // 31 minutes later: a new session.
                page(at(0, 9, 33), "https://c.com/"),
            ),
        )
        assertEquals(2.0, shape.sessionsPerDayMedian, 0.0)
        assertEquals("sizes 3 and 1: nearest-rank median is 1", 1.0, shape.sessionSizeMedian, 0.0)
        assertEquals(3.0, shape.sessionSizeP90, 0.0)
        // www.a.com -> a.com is the same site; a.com -> b.com is not.
        assertEquals(0.5, shape.sameSiteNavShare, 1e-9)
    }

    @Test
    fun `gap metrics count every gap between page loads and ignore dns lookups`() {
        val base = at(0, 10)
        val shape = measure(
            listOf(
                page(base, "https://a.com/"),
                dns(base + 1_000),
                page(base + 5_000, "https://b.com/"),
                page(base + 35_000, "https://c.com/"),
                page(base + 65_500, "https://d.com/"),
            ),
        )
        assertEquals("one of three gaps is under 10 s", 1.0 / 3, shape.gapsUnder10s, 1e-9)
        assertEquals("two gaps fall in the 30-31 s bucket", 2.0 / 3, shape.modalGapShare, 1e-9)
        assertEquals(30.0, shape.modalGapS, 0.0)
    }

    @Test
    fun `a search counts as clicked only when a result page follows it before the next search`() {
        val base = at(0, 12)
        val shape = measure(
            listOf(
                serp(base),
                serp(base + 60_000, engine = "bing"),
                page(base + 70_000, "https://a.com/story", fromSearch = true),
                // A crawl visit after a search is not a click.
                serp(base + 600_000),
                page(base + 610_000, "https://b.com/"),
            ),
        )
        assertEquals("only the second search was clicked", 2.0 / 3, shape.searchNoClickShare, 1e-9)
        assertEquals("only the first search is followed by another within 2 min", 1.0 / 3, shape.reSearchShare, 1e-9)
        assertEquals(2.0 / 3, shape.topEngineShare, 1e-9)
    }

    @Test
    fun `site concentration can leave search pages out`() {
        val base = at(0, 9)
        val events = (0 until 12).flatMap { i ->
            listOf(serp(base + i * 10_000L), page(base + i * 10_000L + 5_000, "https://site$i.com/"))
        }
        val shape = measure(events)
        // All loads: google's 12 plus nine single-visit sites, of 24.
        assertEquals(21.0 / 24, shape.top10Share, 1e-9)
        // Search pages left out: ten of the twelve single-visit sites.
        assertEquals(10.0 / 12, shape.top10NonSearchShare, 1e-9)
    }

    @Test
    fun `the action cadence counts successful actions only`() {
        val base = at(0, 11)
        val actions = listOf(0L, 30_000L, 45_000L, 60_000L, 95_000L).mapIndexed { i, offset ->
            ActionRecord(base + offset, ActionType.SEARCH_QUERY, success = i != 2)
        }
        val shape = measure(emptyList(), actions = actions)
        // Successful gaps: 30 s, 30 s, 35 s.
        assertEquals(2.0 / 3, shape.modalActionGapShare, 1e-9)
        assertEquals(30.0, shape.modalActionGapS, 0.0)
    }

    @Test
    fun `revisits look back over earlier days only`() {
        val shape = measure(
            listOf(
                page(at(0, 9), "https://a.com/"),
                page(at(1, 9), "https://a.com/"),
                page(at(1, 10), "https://b.com/"),
                page(at(2, 9), "https://b.com/"),
            ),
            days = 3,
        )
        // Day 1: a.com seen on day 0, b.com new. Day 2: b.com seen on day 1. Two of three.
        assertEquals(2.0 / 3, shape.revisitShare, 1e-9)
    }

    @Test
    fun `url shape metrics read the path and query of every page load`() {
        val base = at(0, 9)
        val shape = measure(
            listOf(
                page(base, "https://a.com/"),
                page(base + 1_000, "https://a.com"),
                page(base + 2_000, "https://a.com/news/2026/story?id=1"),
                serp(base + 3_000),
            ),
        )
        assertEquals(0.5, shape.homePageShare, 1e-9)
        assertEquals("the story and the results page carry a query", 0.5, shape.queryStringShare, 1e-9)
        // Depths 0, 0, 3, 1: nearest-rank median is 0.
        assertEquals(0.0, shape.pathDepthMedian, 0.0)
    }

    @Test
    fun `day starts are measured from the window opening`() {
        val shape = measure(
            listOf(
                page(at(0, 7, 0, 20), "https://a.com/"),
                page(at(1, 9, 30), "https://a.com/"),
            ),
            days = 2,
        )
        assertEquals("one of two days starts within a minute of 07:00", 0.5, shape.daysStartingOnTheHour, 1e-9)
    }

    @Test
    fun `weekend ratio compares mean daily volume`() {
        // Day 5 is Saturday 2026-01-10.
        val events = (0 until 7).flatMap { day ->
            val count = if (day == 5 || day == 6) 4 else 2
            (0 until count).map { page(at(day, 10, it), "https://a.com/") }
        }
        assertEquals(2.0, measure(events, days = 7).weekendToWeekdayRatio, 1e-9)
    }

    @Test
    fun `a dns lookup within two seconds of a page load is not standalone`() {
        val base = at(0, 15)
        val shape = measure(
            listOf(
                page(base, "https://a.com/"),
                dns(base + 1_500),
                dns(base + 60_000),
            ),
        )
        assertEquals(0.5, shape.standaloneDnsShare, 1e-9)
    }

    @Test
    fun `actual rate is successful actions per allowed hour`() {
        val actions = (0 until 32).map { ActionRecord(at(0, 10), ActionType.PAGE_VISIT, success = it % 2 == 0) }
        // 16 successes over the 16-hour 07-23 window.
        assertEquals(1.0, measure(emptyList(), actions = actions).actionsPerActiveHour, 1e-9)
    }

    @Test
    fun `requests after the close are timed from the latest close`() {
        assertEquals(null, StreamShape.msSinceClose(at(0, 22, 59, 59), 7, 23))
        assertEquals(30_000L, StreamShape.msSinceClose(at(0, 23, 0, 30), 7, 23))
        // Before the window opens, the latest close was the evening before.
        assertEquals((7L * 60 + 59) * 60_000, StreamShape.msSinceClose(at(1, 6, 59), 7, 23))
        // A window ending at 24 closes at midnight.
        assertEquals(10_000L, StreamShape.msSinceClose(at(1, 0, 0, 10), 7, 24))
        // A window across midnight, 22-6, closes at 06:00.
        assertEquals(3_600_000L, StreamShape.msSinceClose(at(1, 7), 22, 6))

        val shape = measure(listOf(page(at(0, 22, 59, 50), "https://a.com/"), page(at(0, 23, 0, 40), "https://a.com/")))
        assertEquals(1, shape.afterCloseCount)
        assertEquals(40.0, shape.afterCloseMaxS, 0.0)
    }

    @Test
    fun `quantiles use the nearest rank`() {
        val values = listOf(5.0, 1.0, 3.0, 2.0, 4.0)
        assertEquals(3.0, StreamShape.quantile(values, 0.5), 0.0)
        assertEquals(5.0, StreamShape.quantile(values, 0.9), 0.0)
        assertTrue(StreamShape.quantile(emptyList(), 0.5).isNaN())
    }
}
