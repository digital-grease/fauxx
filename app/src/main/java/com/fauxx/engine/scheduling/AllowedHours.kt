package com.fauxx.engine.scheduling

import java.util.Calendar

/**
 * Single source of truth for the active-hours predicate, shared by
 * [com.fauxx.engine.PoisonEngine] (the constraint gate that decides whether an action may
 * execute right now) and [SessionScheduler] (the next-action delay computation).
 *
 * Window semantics:
 * - `start == end` — degenerate window, treated as ALWAYS active (a 24h window). This is
 *   how a user expresses "midnight to midnight" (0-0) on the sliders, and it is the
 *   engine's long-documented and unit-tested behavior.
 * - `start < end` — active during `[start, end)`, e.g. 7-23 means 07:00 through 22:59.
 *   The End slider reaches 24 (issue #128), so 0-24 is also a full-day window.
 * - `start > end` — wraps midnight, e.g. 22-6 means 22:00 through 05:59.
 *
 * Issue #124: the engine and scheduler previously carried separate private copies of this
 * predicate that disagreed on `start == end`. The engine treated it as always active, so
 * actions executed at any hour, while the scheduler's `hour in start until end` saw an
 * empty range (never active) and routed every call through its quiet-hours branch,
 * scheduling the next action at the next `start` hour boundary. For the 0-0 window users
 * set to mean "always on", that surfaced in the field as exactly one action per engine
 * start followed by a delay landing at local midnight (the attached debug logs show
 * scheduled delays of 9h-21h, every one expiring at 24:00:00). A single shared
 * implementation makes that divergence structurally impossible.
 */
object AllowedHours {

    /** Returns true when [hour] (0-23) falls inside the window `[start, end)`. */
    fun isWithin(hour: Int, start: Int, end: Int): Boolean = when {
        start == end -> true // degenerate: treat as always allowed
        start < end -> hour in start until end
        else -> hour >= start || hour < end // wraps midnight
    }

    /** Whether the window covers the whole day and so never opens or closes: `start == end`, or 0-24. */
    fun isAllDay(start: Int, end: Int): Boolean = start == end || (start == 0 && end == HOURS_PER_DAY)

    /**
     * Local time at which the window containing [nowMs] opened, or null when [nowMs] is outside
     * the window or the window is all day ([isAllDay]). A window that wraps midnight and is in its
     * after-midnight part opened the previous evening.
     */
    fun windowStartMs(nowMs: Long, start: Int, end: Int): Long? {
        if (isAllDay(start, end)) return null
        val hour = hourOf(nowMs)
        if (!isWithin(hour, start, end)) return null
        val daysBack = if (start > end && hour < end) -1 else 0
        return atHour(nowMs, start, daysBack)
    }

    /**
     * Milliseconds from [nowMs] until the window containing it closes, or null when [nowMs] is
     * outside the window or the window is all day. An end of 24 closes at the next midnight.
     */
    fun msUntilClose(nowMs: Long, start: Int, end: Int): Long? {
        if (isAllDay(start, end)) return null
        val hour = hourOf(nowMs)
        if (!isWithin(hour, start, end)) return null
        val daysAhead = if (start > end && hour >= start) 1 else 0
        return atHour(nowMs, end, daysAhead) - nowMs
    }

    private fun hourOf(nowMs: Long): Int =
        Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.HOUR_OF_DAY)

    /**
     * Local wall-clock [hour]:00 on the day [dayOffset] days from [nowMs]'s; an hour of 24 is the
     * next midnight. Set as a wall-clock field, as [com.fauxx.engine.PoisonEngine] does for the
     * window start, so a daylight-saving change does not move the boundary by an hour.
     */
    private fun atHour(nowMs: Long, hour: Int, dayOffset: Int): Long =
        Calendar.getInstance().apply {
            timeInMillis = nowMs
            add(Calendar.DAY_OF_MONTH, dayOffset + hour / HOURS_PER_DAY)
            set(Calendar.HOUR_OF_DAY, hour % HOURS_PER_DAY)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private const val HOURS_PER_DAY = 24
}
