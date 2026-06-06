package com.fauxx.engine.scheduling

/**
 * Single source of truth for the active-hours predicate, shared by
 * [com.fauxx.engine.PoisonEngine] (the constraint gate that decides whether an action may
 * execute right now) and [PoissonScheduler] (the next-action delay computation).
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
}
