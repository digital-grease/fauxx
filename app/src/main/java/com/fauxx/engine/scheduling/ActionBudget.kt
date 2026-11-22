package com.fauxx.engine.scheduling

import kotlin.math.ceil

/**
 * The action budget behind sessions (#322 item 1): actions accrue at the intensity's rate and are
 * spent one per successful action, with at most [CAPACITY_HOURS] of them saved up.
 *
 * Replaces the old rule that no sliding hour may hold more than the displayed rate. That rule made
 * every hour carry about the same volume, so the stream could not go quiet for long and then
 * browse for a while, which is how people browse. With a budget, a session spends what the break
 * before it saved, so hours can be busier or empty while the count still cannot run ahead of what
 * has accrued: every action is paid for by time that has already passed. Because at most one
 * hour's worth can be saved, no sliding hour can hold more than twice the rate
 * (owner ruling on #322, 2026-11-22). The engine also checks that ceiling directly, since the
 * rate can change between Wi-Fi and mobile data.
 *
 * The budget never carries over from one allowed-hours window to the next: [replay] rebuilds it
 * from the action log, starting empty when the window opened, so a day's total stays at or under
 * what that day accrues however often the engine is stopped and started. With a [pace] (the
 * persona's daily rhythm, #322 item 5) a day accrues its day factor times the rate times the
 * window's length, and those factors average at most 1 over each week: the displayed rate holds as
 * a weekly average (owner ruling on #322).
 *
 * Not thread-safe; the engine loop is its only user.
 */
class ActionBudget private constructor(
    private var balance: Double,
    private var updatedAtMs: Long,
) {

    /**
     * How fast the budget accrues at each moment, as a multiple of the rate (#322 item 5): the
     * persona's day shape and day volume, from [DailyRhythm]. Flat unless set.
     */
    var pace: AccrualPace = AccrualPace.FLAT

    /** Actions available now, as a fraction (for logs and tests). */
    val available: Double get() = balance

    /**
     * Add what has accrued since the last update at [actionsPerHour], capped at the capacity for
     * that rate. A lower rate than before also lowers the cap, so leaving Wi-Fi for a lower mobile
     * tier does not carry Wi-Fi's savings onto mobile data.
     */
    fun accrue(nowMs: Long, actionsPerHour: Int) {
        val from = updatedAtMs
        updatedAtMs = maxOf(updatedAtMs, nowMs)
        if (actionsPerHour <= 0) return
        val gained = if (nowMs > from) actionsPerHour * paced(from, nowMs) / HOUR_MS else 0.0
        // The cap applies even with nothing gained, so a rate drop lowers it at once.
        balance = minOf(capacity(actionsPerHour), balance + gained)
    }

    /** Milliseconds of accrual at the rate between [from] and [to], weighted by [pace]. */
    private fun paced(from: Long, to: Long): Double {
        val pace = pace
        if (pace === AccrualPace.FLAT) return (to - from).toDouble()
        var total = 0.0
        var t = from
        while (t < to) {
            val step = minOf(PACE_STEP_MS, to - t)
            total += step * pace.at(t + step / 2).coerceAtLeast(0.0)
            t += step
        }
        return total
    }

    /** Whether one action is paid for. */
    fun canSpend(): Boolean = balance >= 1.0 - EPSILON

    /** Pay for one action. The balance may go below zero after a rate drop; it is then repaid first. */
    fun spend() {
        balance -= 1.0
    }

    /**
     * Milliseconds from the last update until [actions] are saved at [actionsPerHour]; 0 when they
     * already are. With a [pace], up to [PACE_LOOKAHEAD_MS], beyond which it says that.
     */
    fun msUntil(actions: Double, actionsPerHour: Int): Long {
        val missing = minOf(actions, capacity(actionsPerHour)) - balance
        if (missing <= EPSILON) return 0L
        if (actionsPerHour <= 0) return Long.MAX_VALUE
        val pace = pace
        if (pace === AccrualPace.FLAT) return ceil(missing * HOUR_MS / actionsPerHour).toLong()
        val needMs = missing * HOUR_MS / actionsPerHour
        var gained = 0.0
        var t = updatedAtMs
        while (t - updatedAtMs < PACE_LOOKAHEAD_MS) {
            val rate = pace.at(t + PACE_STEP_MS / 2).coerceAtLeast(0.0)
            if (gained + PACE_STEP_MS * rate >= needMs) {
                return t - updatedAtMs + ceil((needMs - gained) / rate).toLong()
            }
            gained += PACE_STEP_MS * rate
            t += PACE_STEP_MS
        }
        return PACE_LOOKAHEAD_MS
    }

    /** Milliseconds until the budget is full at [actionsPerHour], after which saving stops. */
    fun msUntilFull(actionsPerHour: Int): Long = msUntil(capacity(actionsPerHour), actionsPerHour)

    companion object {
        /** How much can be saved, in hours of the rate. One hour caps any sliding hour at twice the rate. */
        const val CAPACITY_HOURS = 1.0

        private const val HOUR_MS = 3_600_000.0
        private const val EPSILON = 1e-9

        /** Resolution of the integral over a [pace], and how far ahead [msUntil] looks. */
        private const val PACE_STEP_MS = 60_000L
        const val PACE_LOOKAHEAD_MS = 48L * 60 * 60 * 1000

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
            pace: AccrualPace = AccrualPace.FLAT,
        ): ActionBudget {
            val from = windowStartMs ?: (nowMs - ALL_DAY_LOOKBACK_MS)
            val start = if (windowStartMs != null) 0.0 else capacity(actionsPerHour)
            val budget = ActionBudget(start, from).also { it.pace = pace }
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
