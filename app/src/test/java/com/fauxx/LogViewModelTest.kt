package com.fauxx

import com.fauxx.data.db.ActionLogDao
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.db.LogMetadata
import com.fauxx.data.model.ActionType
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.support.MainDispatcherRule
import com.fauxx.ui.viewmodels.LogListItem
import com.fauxx.ui.viewmodels.LogViewModel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Safety + behavior tests for the action-log export. The export is a user-shareable artifact (it
 * goes through the share sheet into bug reports, email, cloud storage), so it must (a) not leak
 * PII and (b) not be vulnerable to CSV formula injection. Also covers the issue #73 additions:
 * the metadata column in exports and the day-grouping of the list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val dao: ActionLogDao = mockk(relaxed = true)

    private fun entry(detail: String) = ActionLogEntity(
        actionType = ActionType.SEARCH_QUERY,
        category = CategoryPool.FINANCE,
        detail = detail
    )

    private fun entryAt(ts: Long, detail: String) = ActionLogEntity(
        timestamp = ts,
        actionType = ActionType.SEARCH_QUERY,
        category = CategoryPool.FINANCE,
        detail = detail
    )

    @Test
    fun `exportCsv redacts PII and neutralizes formula injection`() = runTest {
        coEvery { dao.getAllForExport() } returns listOf(
            entry("contact evil@example.com about it"),
            entry("=cmd|'/c calc'!A1"),
            entry("[FINANCE] best budgeting apps"),
        )
        var csv = ""
        LogViewModel(dao).exportCsv { csv = it }
        advanceUntilIdle()

        assertTrue(
            "header row must be present (with the metadata column)",
            csv.startsWith("timestamp,action_type,category,detail,metadata,success")
        )
        assertFalse("email must be redacted, not exported verbatim", csv.contains("evil@example.com"))
        assertTrue("redaction marker must appear", csv.contains("[REDACTED]"))
        assertTrue(
            "a formula-leading cell must be apostrophe-guarded inside its quotes",
            csv.contains("\"'=cmd")
        )
        assertTrue("non-sensitive detail is preserved", csv.contains("[FINANCE] best budgeting apps"))
    }

    @Test
    fun `exportJson redacts PII from detail`() = runTest {
        coEvery { dao.getAllForExport() } returns listOf(entry("reach evil@example.com now"))
        var json = ""
        LogViewModel(dao).exportJson { json = it }
        advanceUntilIdle()

        assertFalse(json.contains("evil@example.com"))
        assertTrue(json.contains("[REDACTED]"))
    }

    @Test
    fun `exportJson scrubs PII inside metadata`() = runTest {
        val meta = LogMetadata.toJson(LogMetadata.PAGE_TITLE to "mailto evil@example.com")
        coEvery { dao.getAllForExport() } returns listOf(entry("clean").copy(metadata = meta))
        var json = ""
        LogViewModel(dao).exportJson { json = it }
        advanceUntilIdle()

        assertFalse("metadata PII must be redacted", json.contains("evil@example.com"))
    }

    @Test
    fun `exportHtml escapes markup and includes scrubbed metadata`() = runTest {
        val meta = LogMetadata.toJson(
            LogMetadata.PAGE_TITLE to "<b>Deals</b> & more",
            LogMetadata.COOKIES_IN_JAR to "5",
        )
        coEvery { dao.getAllForExport() } returns listOf(
            ActionLogEntity(
                actionType = ActionType.COOKIE_HARVEST,
                category = CategoryPool.FINANCE,
                detail = "https://decoy.example/cart",
                metadata = meta,
            )
        )
        var html = ""
        LogViewModel(dao).exportHtml { html = it }
        advanceUntilIdle()

        assertTrue("emits an HTML table", html.contains("<table>"))
        assertTrue("metadata is HTML-escaped", html.contains("Page title: &lt;b&gt;Deals&lt;/b&gt; &amp; more"))
        assertFalse("raw markup must not survive escaping", html.contains("<b>Deals</b>"))
        assertTrue("cookie count rendered", html.contains("Cookies in jar: 5"))
    }

    @Test
    fun `formatEntry produces scrubbed single-entry text with metadata`() {
        val meta = LogMetadata.toJson(LogMetadata.USER_AGENT to "Mozilla/5.0 decoy")
        val text = LogViewModel(dao).formatEntry(
            entry("reach evil@example.com now").copy(metadata = meta)
        )
        assertFalse("PII scrubbed", text.contains("evil@example.com"))
        assertTrue("metadata included", text.contains("User-Agent: Mozilla/5.0 decoy"))
        assertTrue("status line present", text.contains("Status: success"))
    }

    @Test
    fun `groupByDay inserts one header per calendar day with entries following`() {
        fun at(daysAgo: Int, hour: Int): Long = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -daysAgo)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        // Most-recent-first, as dao.observeAll() returns: one entry on day 1, two on day 2.
        val entries = listOf(
            entryAt(at(1, 10), "recent"),
            entryAt(at(2, 14), "older-b"),
            entryAt(at(2, 9), "older-a"),
        )
        val items = LogViewModel(dao).groupByDay(entries)

        assertEquals(5, items.size)
        assertTrue(items[0] is LogListItem.DayHeader)
        assertTrue(items[1] is LogListItem.Entry)
        assertTrue(items[2] is LogListItem.DayHeader)
        assertTrue(items[3] is LogListItem.Entry)
        assertTrue(items[4] is LogListItem.Entry)
        assertEquals("recent", (items[1] as LogListItem.Entry).entity.detail)
        assertEquals(
            "two distinct day headers",
            2,
            items.filterIsInstance<LogListItem.DayHeader>().map { it.dateKey }.toSet().size
        )
    }
}
