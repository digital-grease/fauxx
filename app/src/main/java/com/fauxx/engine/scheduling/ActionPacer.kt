package com.fauxx.engine.scheduling

import com.fauxx.data.querybank.CategoryPool

/**
 * Decides when [com.fauxx.engine.PoisonEngine] acts and on which topic: the engine's pacing seam.
 * [SessionScheduler] is the real one; engine tests substitute a fixed pace.
 *
 * The engine calls [startRun] once per run, then for each action: [msUntilAllowed] (wait first if
 * above zero), [nextCategory], [searchChance] when picking the module, the module's action, and
 * [afterAction] for the wait before the next one. All calls come from the engine loop, one at a time.
 */
interface ActionPacer {

    /**
     * A new engine run begins at [nowMs]: rebuild the budget from the times of successful actions
     * logged in the last day, [successTimestamps], and start with a fresh session.
     */
    fun startRun(
        nowMs: Long,
        actionsPerHour: Int,
        allowedStart: Int,
        allowedEnd: Int,
        successTimestamps: List<Long>,
    )

    /** Milliseconds to wait before the next action may start at [actionsPerHour]; 0 when it may now. */
    fun msUntilAllowed(nowMs: Long, actionsPerHour: Int): Long

    /**
     * The topic for the next action: the current burst's, or a new one from [draw], which samples
     * the targeting weights. Drawing only when a burst starts leaves the long-run topic mix as the
     * weights set it.
     */
    fun nextCategory(draw: () -> CategoryPool): CategoryPool

    /**
     * Chance that the action [nextCategory] just gave a topic for should be a search, when
     * [moduleCount] modules can act and the search module is one of them. The engine otherwise
     * picks among the others evenly; an even chance of `1 / moduleCount` is the plain uniform pick.
     */
    fun searchChance(moduleCount: Int): Double

    /**
     * An action ran for [execMs] and was logged in [executed] ([success] when it counts toward the
     * rate). Returns the wait before the next action.
     */
    fun afterAction(
        actionsPerHour: Int,
        executed: CategoryPool,
        success: Boolean,
        execMs: Long,
        allowedStart: Int,
        allowedEnd: Int,
    ): Long

    /**
     * A fresh draw of the current wait at [actionsPerHour], for when the rate rises mid-wait (the
     * charging boost, #316). The engine only ever uses it to end a wait sooner.
     */
    fun redrawDelayMs(actionsPerHour: Int): Long
}
