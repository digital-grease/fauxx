package com.fauxx.data.querybank

import android.content.Context
import android.content.res.AssetManager
import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks [QueryBankManager]'s half of issue #256: the $YEAR$ placeholder is resolved before
 * anything downstream sees a query, and a cached bank does not stay frozen at the year the
 * process happened to start in.
 */
class QueryBankManagerYearTest {

    private val bank = """["best smartphones ${'$'}YEAR${'$'}", "how to fix slow Wi-Fi"]"""

    private fun manager(
        year: () -> Int,
        blocked: (String) -> Boolean = { false },
        json: String = bank,
    ): QueryBankManager {
        val assets: AssetManager = mockk {
            every { open(any()) } answers { json.byteInputStream() }
        }
        val context: Context = mockk(relaxed = true) { every { getAssets() } returns assets }
        val blocklist: QueryBlocklist = mockk {
            every { isBlocked(any()) } answers { blocked(firstArg()) }
        }
        val localeManager: LocaleManager = mockk(relaxed = true) {
            every { currentLocale } returns SupportedLocale.EN
            every { currentLocaleFlow } returns MutableStateFlow(SupportedLocale.EN)
        }
        return QueryBankManager(context, blocklist, localeManager).apply { yearProvider = year }
    }

    @Test
    fun `the placeholder is resolved before the queries are served`() {
        val queries = manager(year = { 2031 }).getQueries(CategoryPool.TECHNOLOGY)
        assertEquals(listOf("best smartphones 2031", "how to fix slow Wi-Fi"), queries)
    }

    @Test
    fun `the blocklist sees the resolved text, not the placeholder`() {
        // The safety gate must run on what actually dispatches. If resolution happened
        // after filtering, a blocklist rule could never match a freshened query.
        val seen = mutableListOf<String>()
        manager(year = { 2031 }, blocked = { seen += it; false }).getQueries(CategoryPool.TECHNOLOGY)
        assertTrue(
            "Blocklist saw unresolved text: $seen",
            seen.contains("best smartphones 2031") && seen.none { it.contains(YEAR_TOKEN) }
        )
    }

    @Test
    fun `a cached bank is re-resolved after the calendar year rolls`() {
        // A phone can stay up across New Year. Without eviction the bank would keep
        // serving last year's query for as long as the process lives.
        var year = 2030
        val manager = manager(year = { year })

        assertEquals("best smartphones 2030", manager.getQueries(CategoryPool.TECHNOLOGY).first())
        year = 2031
        assertEquals("best smartphones 2031", manager.getQueries(CategoryPool.TECHNOLOGY).first())
    }

    @Test
    fun `the corpus generation advances on a year roll so consumers can invalidate`() {
        // MarkovQueryGenerator trains a bigram model from these banks and would otherwise
        // keep chaining last year's tokens after the eviction. It watches this counter.
        var year = 2030
        val manager = manager(year = { year })

        manager.getQueries(CategoryPool.TECHNOLOGY)
        val before = manager.corpusGeneration

        year = 2031
        manager.getQueries(CategoryPool.TECHNOLOGY)

        assertTrue(
            "generation must advance on a year roll (was $before, now ${manager.corpusGeneration})",
            manager.corpusGeneration > before
        )
    }

    @Test
    fun `the corpus generation holds steady across ordinary reads`() {
        // A counter that moved on every read would make consumers retrain constantly.
        val manager = manager(year = { 2031 })
        repeat(5) { manager.getQueries(CategoryPool.TECHNOLOGY) }
        assertEquals(0, manager.corpusGeneration)
    }

    @Test
    fun `the first read of a process is not treated as a year roll`() {
        // cachedYear starts at 0; that sentinel must not look like a rollover from year zero.
        val manager = manager(year = { 2031 })
        manager.getQueries(CategoryPool.TECHNOLOGY)
        assertEquals(0, manager.corpusGeneration)
    }

    @Test
    fun `the cache is not cleared while the year holds steady`() {
        // Eviction on every read would re-parse the asset on each action.
        var loads = 0
        val assets: AssetManager = mockk {
            every { open(any()) } answers { loads++; bank.byteInputStream() }
        }
        val context: Context = mockk(relaxed = true) { every { getAssets() } returns assets }
        val blocklist: QueryBlocklist = mockk { every { isBlocked(any()) } returns false }
        val localeManager: LocaleManager = mockk(relaxed = true) {
            every { currentLocale } returns SupportedLocale.EN
            every { currentLocaleFlow } returns MutableStateFlow(SupportedLocale.EN)
        }
        val manager = QueryBankManager(context, blocklist, localeManager)
            .apply { yearProvider = { 2031 } }

        repeat(5) { manager.getQueries(CategoryPool.TECHNOLOGY) }
        assertEquals("bank should be parsed once and cached", 1, loads)
    }
}
