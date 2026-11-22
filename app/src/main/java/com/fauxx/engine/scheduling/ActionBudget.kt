package com.fauxx.engine.scheduling

import kotlin.math.ceil

/**
 * The action budget behind sessions (#322 item 1): actions accrue at the intensity's rate and are
 * spent one per successful action, with at most [CAPACITY_HOURS] of them saved up.
 *
 * Replaces the old rule that no sliding hour may hold more than the displayed rate. That rule made
 * every hour carry about the same volume, so the stream could not go quiet for long and then
 * browse for a while, which is how people browse. With a budget, a session spends what the break
 * before it saved, so hours can be busier or empty while the count still cannot run ahead of the
 * displayed rate: every action is paid for by time that has already passed at that rate. Because
 * at most one hour's worth can be saved, no sliding hour can hold more than twice the rate
 * (owner ruling on #322, 2026-11-22). The engine also checks that ceiling directly, since the
 * rate can change between Wi-Fi and mobile data.
 *
 * The budget never carries over from one allowed-hours window to the next: [replay] rebuilds it
 * from the action log, starting empty when the window opened, so a day's total stays at or under
 * the rate times the window's length however often the engine is stopped and started.
 *
 * Not thread-safe; the engine loop is its only user.
 */
class ActionBudget private constructor(
    private var balance: Double,
    private var updatedAtMs: Long,
) {

    /** Actions available now, as a fraction (for logs and tests). */
    val available: Double get() = balance

    /**
     * Add what has accrued since the last update at [actionsPerHour], capped at the capacity for
     * that rate. A lower rate than before also lowers the cap, so leaving Wi-Fi for a lower mobile
     * tier does not carry Wi-Fi's savings onto mobile data.
     */
    fun accrue(nowMs: Long, actionsPerHour: Int) {
        val elapsed = (nowMs - updatedAtMs).coerceAtLeast(0L)
        updatedAtMs = maxOf(updatedAtMs, nowMs)
        if (actionsPerHour <= 0) return
        balance = minOf(capacity(actionsPerHour), balance + actionsPerHour * elapsed / HOUR_MS)
    }

    /** Whether one action is paid for. */
    fun canSpend(): Boolean = balance >= 1.0 - EPSILON

    /** Pay for one action. The balance may go below zero after a rate drop; it is then repaid first. */
    fun spend() {
        balance -= 1.0
    }

    /** Milliseconds until [actions] are saved at [actionsPerHour]; 0 when they already are. */
    fun msUntil(actions: Double, actionsPerHour: Int): Long {
        val missing = minOf(actions, capacity(actionsPerHour)) - balance
        if (missing <= EPSILON) return 0L
        if (actionsPerHour <= 0) return Long.MAX_VALUE
        return ceil(missing * HOUR_MS / actionsPerHour).toLong()
    }

    /** Milliseconds until the budget is full at [actionsPerHour], after which saving stops. */
    fun msUntilFull(actionsPerHour: Int): Long = msUntil(capacity(actionsPerHour), actionsPerHour)

    companion object {
        /** How much can be saved, in hours of the rate. One hour caps any sliding hour at twice the rate. */
        const val CAPACITY_HOURS = 1.0

        private const val HOUR_MS = 3_600_000.0
        private const val EPSILON = 1e-9

        /** How far back [replay] looks when the window is all day and never opened. */
        const val ALL_DAY_LOOKBACK_MS = 3L * 60 * 60 * 1000

        /** Capacity at [actionsPerHour]: [CAPACITY_HOURS] of actions, and never less than one. */
        fun capacity(actionsPerHour: Int): Double = maxOf(1.0, actionsPerHour * CAPACITY_HOURS)

        /**
         * Rebuild the budget at [nowMs] from the times of logged successful actions, so stopping and
         * starting the engine cannot hand out a fresh allowance.
         *
         * Replays from [windowStartMs], when the window opened, starting empty. With no opening
         * (an all-day window) it replays the last [ALL_DAY_LOOKBACK_MS] starting full, the most that
         * could have been saved; three hours is long enough for the actions in it to decide the
         * balance. The current rate stands in for whatever rate applied earlier.
         */
        fun replay(
            nowMs: Long,
            actionsPerHour: Int,
            windowStartMs: Long?,
            successTimestamps: List<Long>,
        ): ActionBudget {
            val from = windowStartMs ?: (nowMs - ALL_DAY_LOOKBACK_MS)
            val start = if (windowStartMs != null) 0.0 else capacity(actionsPerHour)
            val budget = ActionBudget(start, from)
            for (at in successTimestamps.filter { it in from..nowMs }.sorted()) {
                budget.accrue(at, actionsPerHour)
                // Logged actions can outnumber the budget when the rate was higher earlier; the
                // debt is bounded so a long high-rate run does not silence the next hours.
                budget.balance = maxOf(budget.balance - 1.0, -capacity(actionsPerHour))
            }
            budget.accrue(nowMs, actionsPerHour)
            return budget
        }

        /** A budget holding [actions] at [nowMs], for tests and for runs with no log to replay. */
        fun holding(actions: Double, nowMs: Long): ActionBudget = ActionBudget(actions, nowMs)
    }
}
