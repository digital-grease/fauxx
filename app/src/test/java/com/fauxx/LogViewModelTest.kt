package com.fauxx

import com.fauxx.data.db.ActionLogDao
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.model.ActionType
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.support.MainDispatcherRule
import com.fauxx.ui.viewmodels.LogViewModel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Safety tests for the action-log export. The export is a user-shareable artifact (it goes
 * through the share sheet into bug reports, email, cloud storage), so it must (a) not leak
 * PII and (b) not be vulnerable to CSV formula injection.
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
            "header row must be present",
            csv.startsWith("timestamp,action_type,category,detail,success")
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
}
