package com.fauxx.engine.scheduling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The action budget behind sessions (#322): accrual, the one-hour cap, and the replay from the log. */
class ActionBudgetTest {

    private val hour = 3_600_000L
    private val minute = 60_000L

    @Test
    fun `accrues at the rate and stops at one hour's worth`() {
        val budget = ActionBudget.holding(0.0, 0L)
        budget.accrue(30 * minute, actionsPerHour = 60)
        assertEquals(30.0, budget.available, 1e-9)
        budget.accrue(3 * hour, actionsPerHour = 60)
        assertEquals(60.0, budget.available, 1e-9)
    }

    @Test
    fun `an action needs one saved`() {
        val budget = ActionBudget.holding(0.0, 0L)
        assertFalse(budget.canSpend())
        assertEquals(minute, budget.msUntil(1.0, actionsPerHour = 60))
        budget.accrue(minute, actionsPerHour = 60)
        assertTrue(budget.canSpend())
        budget.spend()
        assertFalse(budget.canSpend())
    }

    @Test
    fun `time until full counts from the balance`() {
        val budget = ActionBudget.holding(20.0, 0L)
        assertEquals(40 * minute, budget.msUntilFull(actionsPerHour = 60))
        assertEquals(0L, ActionBudget.holding(60.0, 0L).msUntilFull(actionsPerHour = 60))
    }

    @Test
    fun `a lower rate lowers the cap at once`() {
        // Leaving Wi-Fi at High for mobile Low must not carry Wi-Fi's savings onto mobile data.
        val budget = ActionBudget.holding(200.0, 0L)
        budget.accrue(0L, actionsPerHour = 12)
        assertEquals(12.0, budget.available, 1e-9)
    }

    @Test
    fun `the capacity is never below one action`() {
        assertEquals(1.0, ActionBudget.capacity(0), 0.0)
        assertEquals(12.0, ActionBudget.capacity(12), 0.0)
    }

    @Test
    fun `replay starts empty when the window opened and pays for every logged action`() {
        val opened = 0L
        val now = 2 * hour
        // 90 actions in the first 90 minutes at 60/h: the budget ran dry and only refills from then.
        val logged = (1..90).map { it * minute }
        val budget = ActionBudget.replay(now, actionsPerHour = 60, windowStartMs = opened, successTimestamps = logged)
        // 120 accrued, 90 spent, never above 60 at any point: 30 left.
        assertEquals(30.0, budget.available, 1e-6)
    }

    @Test
    fun `replay of a fresh window with nothing logged waits for the first action`() {
        val budget = ActionBudget.replay(nowMs = 0L, actionsPerHour = 12, windowStartMs = 0L, successTimestamps = emptyList())
        assertFalse(budget.canSpend())
        assertEquals(5 * minute, budget.msUntil(1.0, actionsPerHour = 12))
    }

    @Test
    fun `replay with no window opening starts full three hours back`() {
        val now = 10 * hour
        val budget = ActionBudget.replay(now, actionsPerHour = 60, windowStartMs = null, successTimestamps = emptyList())
        assertEquals(60.0, budget.available, 1e-9)
        val busy = (1..60).map { now - it * 1_000L }
        val spent = ActionBudget.replay(now, actionsPerHour = 60, windowStartMs = null, successTimestamps = busy)
        assertTrue("60 actions in the last minute leave about one accrued, got ${spent.available}", spent.available < 2.0)
    }

    @Test
    fun `replay ignores actions before the window opened`() {
        val opened = 5 * hour
        val now = opened + 10 * minute
        val yesterday = (1..500).map { opened - it * minute }
        val budget = ActionBudget.replay(now, actionsPerHour = 60, windowStartMs = opened, successTimestamps = yesterday)
        assertEquals(10.0, budget.available, 1e-6)
    }

    @Test
    fun `replay bounds the debt from a higher earlier rate`() {
        val opened = 0L
        val now = hour
        // 400 actions logged at a high rate, replayed at Low: the debt stops at one hour's worth.
        val logged = (1..400).map { it * 1_000L }
        val budget = ActionBudget.replay(now, actionsPerHour = 12, windowStartMs = opened, successTimestamps = logged)
        assertTrue("debt is bounded, got ${budget.available}", budget.available >= -12.0)
    }
}
