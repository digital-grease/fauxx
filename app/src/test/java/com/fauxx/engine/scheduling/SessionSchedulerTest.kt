package com.fauxx.engine.scheduling

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.support.FakeClock
import java.util.Calendar
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session scheduler (#322 item 1), driven the way the engine drives it: wait while
 * [SessionScheduler.msUntilAllowed] says so, take the topic from [SessionScheduler.nextCategory],
 * let the action run for a fixed time, then wait what [SessionScheduler.afterAction] returns.
 *
 * The engine's own checks (the hourly ceiling, the close guard) are left out, so these tests show
 * that the scheduler alone keeps the budget's promises. The stream-shape report measures the
 * whole engine.
 */
class SessionSchedulerTest {

    private val minuteMs = 60_000L
    private val hourMs = 60 * minuteMs

    /** Local midnight on Monday 2026-01-05, as in the stream-shape report. */
    private fun day0(): Long = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.JANUARY, 5, 0, 0, 0)
    }.timeInMillis

    private fun atHour(hour: Int): Long = day0() + hour * hourMs

    private class Event(val atMs: Long, val category: CategoryPool)

    private fun scheduler(
        clock: FakeClock,
        seed: Long = 20261122L,
        modulator: RateModulator = RateModulator.NEUTRAL,
    ) = SessionScheduler(clock, Random(seed), modulator, SessionHabitsSource.DEFAULT)

    /**
     * Run [hours] of actions at [rate] in an all-day window, each taking 3-20 s (a page load and its
     * dwell, as the modules' own dwell ranges give), with topics drawn by [draw]. Returns the start
     * time and topic of each action.
     */
    private fun simulate(
        rate: Int,
        hours: Int,
        seed: Long = 20261122L,
        draw: () -> CategoryPool = { CategoryPool.COOKING },
    ): List<Event> {
        val clock = FakeClock(atHour(7))
        val pacer = scheduler(clock, seed)
        val execRandom = Random(seed + 1)
        val end = clock.nowMs + hours * hourMs
        pacer.startRun(clock.nowMs, rate, 0, 0, emptyList())
        val events = mutableListOf<Event>()
        while (clock.nowMs < end) {
            val wait = pacer.msUntilAllowed(clock.nowMs, rate)
            if (wait > 0) {
                clock.nowMs += wait
                continue
            }
            val category = pacer.nextCategory(draw)
            events += Event(clock.nowMs, category)
            val execMs = execRandom.nextLong(3_000L, 20_000L)
            clock.nowMs += execMs
            clock.nowMs += pacer.afterAction(rate, category, true, execMs, 0, 0)
        }
        return events
    }

    private fun gaps(events: List<Event>): List<Long> = events.zipWithNext { a, b -> b.atMs - a.atMs }

    private fun median(values: List<Long>): Long = values.sorted()[values.size / 2]

    @Test
    fun `the count never runs ahead of the rate`() {
        for (rate in listOf(12, 60, 200)) {
            val hours = 48
            val events = simulate(rate, hours)
            // Starting full, a run can spend at most one hour's worth more than accrued.
            assertTrue(
                "rate $rate: ${events.size} actions in $hours h",
                events.size <= rate * hours + rate + 1,
            )
        }
    }

    @Test
    fun `no sliding hour holds more than twice the rate`() {
        for (rate in listOf(12, 60, 200)) {
            val times = simulate(rate, 48).map { it.atMs }
            var from = 0
            for (to in times.indices) {
                while (times[to] - times[from] >= hourMs) from++
                val inHour = to - from + 1
                assertTrue("rate $rate: $inHour actions within an hour", inHour <= 2 * rate + 1)
            }
        }
    }

    @Test
    fun `the calmer tiers keep close to their rate`() {
        for ((rate, floor) in listOf(12 to 0.85, 60 to 0.85, 200 to 0.75)) {
            val hours = 72
            val perHour = simulate(rate, hours).size.toDouble() / hours
            assertTrue("rate $rate: $perHour per hour", perHour >= rate * floor)
        }
    }

    @Test
    fun `medium browses in sessions with breaks between them`() {
        val events = simulate(rate = 60, hours = 48)
        val gaps = gaps(events)
        val breaks = gaps.count { it > 30 * minuteMs }
        val inSession = gaps.filter { it <= 30 * minuteMs }
        assertTrue("breaks over 30 min in two days: $breaks", breaks in 6..30)
        assertTrue("median gap inside a session: ${median(inSession)} ms", median(inSession) < 30_000L)
        assertTrue("some breaks run an hour or more", gaps.any { it >= hourMs })
    }

    @Test
    fun `a change of topic waits at least the cross-niche floor`() {
        val random = Random(7)
        val events = simulate(rate = 60, hours = 24) {
            if (random.nextBoolean()) CategoryPool.COOKING else CategoryPool.LEGAL
        }
        val floor = SessionScheduler.crossNicheFloorMs(60)
        for ((a, b) in events.zipWithNext()) {
            if (a.category != b.category) {
                assertTrue("topic changed after ${b.atMs - a.atMs} ms", b.atMs - a.atMs >= floor)
            }
        }
    }

    @Test
    fun `gaps do not pile up at one value`() {
        // #382: about 40% of cross-topic gaps sat at exactly the 30 s floor.
        val random = Random(11)
        val gaps = gaps(
            simulate(rate = 60, hours = 48) { CategoryPool.entries[random.nextInt(CategoryPool.entries.size)] },
        )
        val modal = gaps.groupingBy { it / 1000 }.eachCount().maxOf { it.value }
        assertTrue("the most common one-second bucket holds $modal of ${gaps.size}", modal < gaps.size / 10)
    }

    @Test
    fun `drawing topics only at burst starts keeps the weights' mix`() {
        val random = Random(3)
        val events = simulate(rate = 60, hours = 96) {
            if (random.nextDouble() < 0.7) CategoryPool.COOKING else CategoryPool.LEGAL
        }
        val share = events.count { it.category == CategoryPool.COOKING }.toDouble() / events.size
        assertTrue("cooking share $share", share in 0.64..0.76)
    }

    @Test
    fun `searches open bursts and keep close to an even share`() {
        // Five modules, search among them: an even pick gives searches 20% of actions. Only a
        // burst's first action may be a search, which keeps them near that share while ending
        // searches every few seconds.
        val clock = FakeClock(atHour(7))
        val pacer = scheduler(clock)
        val pick = Random(5)
        pacer.startRun(clock.nowMs, 60, 0, 0, emptyList())
        var actions = 0
        var searches = 0
        var noSearchAllowed = 0
        while (actions < 5_000) {
            val wait = pacer.msUntilAllowed(clock.nowMs, 60)
            if (wait > 0) {
                clock.nowMs += wait
                continue
            }
            pacer.nextCategory { CategoryPool.COOKING }
            val chance = pacer.searchChance(5)
            if (chance == 0.0) noSearchAllowed++
            if (pick.nextDouble() < chance) searches++
            actions++
            clock.nowMs += 10_000L
            clock.nowMs += pacer.afterAction(60, CategoryPool.COOKING, true, 10_000L, 0, 0)
        }
        val share = searches.toDouble() / actions
        assertTrue("search share $share", share in 0.14..0.21)
        // Bursts average three actions, so about two in three actions continue one.
        val continuing = noSearchAllowed.toDouble() / actions
        assertTrue("share of actions that cannot be a search: $continuing", continuing in 0.5..0.8)
    }

    @Test
    fun `outside the window the wait runs to the window's start`() {
        val clock = FakeClock(atHour(3))
        val pacer = scheduler(clock)
        pacer.startRun(clock.nowMs, 60, 7, 23, emptyList())
        pacer.nextCategory { CategoryPool.COOKING }
        assertEquals(4 * hourMs, pacer.afterAction(60, CategoryPool.COOKING, true, 0L, 7, 23))
    }

    @Test
    fun `an all-day window never waits for an hour boundary`() {
        // #124: 0-0 and 13-13 mean "always on"; a scheduler that read them as empty slept 9-21 h.
        for (window in listOf(0 to 0, 13 to 13)) {
            for (hour in 0..23) {
                val clock = FakeClock(atHour(hour))
                val pacer = scheduler(clock, seed = hour.toLong())
                pacer.startRun(clock.nowMs, 60, window.first, window.second, emptyList())
                repeat(50) {
                    pacer.nextCategory { CategoryPool.COOKING }
                    val wait = pacer.afterAction(60, CategoryPool.COOKING, true, 10_000L, window.first, window.second)
                    assertTrue("window $window at $hour:00 waited $wait ms", wait < 3 * hourMs)
                }
            }
        }
    }

    @Test
    fun `a run that opens with nothing saved starts with a break`() {
        val clock = FakeClock(atHour(7))
        val pacer = scheduler(clock)
        pacer.startRun(clock.nowMs, 12, 7, 23, emptyList())
        assertTrue(
            "at least the five minutes one Low action takes to save",
            pacer.msUntilAllowed(clock.nowMs, 12) >= 5 * minuteMs,
        )
    }

    @Test
    fun `a run with savings starts at once`() {
        val clock = FakeClock(atHour(12))
        val pacer = scheduler(clock)
        pacer.startRun(clock.nowMs, 60, 7, 23, emptyList())
        assertEquals(0L, pacer.msUntilAllowed(clock.nowMs, 60))
    }

    @Test
    fun `quiet hours of the rhythm lengthen breaks`() {
        fun meanBreak(multiplier: Float): Double {
            val clock = FakeClock(atHour(7))
            val pacer = scheduler(clock, modulator = { multiplier })
            pacer.startRun(clock.nowMs, 12, 7, 23, emptyList())
            return (1..400).map { pacer.redrawDelayMs(12) }.average()
        }
        val quiet = meanBreak(0.5f)
        val busy = meanBreak(1.5f)
        assertTrue("quiet ${quiet / minuteMs} min vs busy ${busy / minuteMs} min", quiet > busy * 1.3)
    }
}
