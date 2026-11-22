package com.fauxx.support

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.scheduling.ActionPacer

/**
 * An [ActionPacer] for engine tests that are not about pacing: every action may start at once, the
 * topic is a fresh draw each time, modules are picked evenly, and the wait after every action is
 * [delayMs]. That is the
 * fixed-delay scheduler mock these tests used before sessions (#322). The pacing itself is
 * covered by SessionSchedulerTest and the stream-shape report.
 */
class FixedPacer(
    private val delayMs: Long,
    /** What [redrawDelayMs] returns, for the charging-boost tests (#316). */
    private val redrawMs: Long = delayMs,
) : ActionPacer {
    override fun startRun(
        nowMs: Long,
        actionsPerHour: Int,
        allowedStart: Int,
        allowedEnd: Int,
        successTimestamps: List<Long>,
    ) = Unit

    override fun msUntilAllowed(nowMs: Long, actionsPerHour: Int): Long = 0L

    override fun nextCategory(draw: () -> CategoryPool): CategoryPool = draw()

    /** The plain uniform module pick. */
    override fun searchChance(moduleCount: Int): Double = 1.0 / moduleCount.coerceAtLeast(1)

    override fun afterAction(
        actionsPerHour: Int,
        executed: CategoryPool,
        success: Boolean,
        execMs: Long,
        allowedStart: Int,
        allowedEnd: Int,
    ): Long = delayMs

    override fun redrawDelayMs(actionsPerHour: Int): Long = redrawMs
}
