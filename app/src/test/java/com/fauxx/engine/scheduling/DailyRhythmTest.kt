package com.fauxx.engine.scheduling

import java.time.LocalDate
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A persona's day and week (#322 item 5), and the budget accruing along them. */
class DailyRhythmTest {

    private val minute = 60_000L
    private val hour = 60 * minute

    /** Local [hour]:00 on [date]. */
    private fun at(date: LocalDate, hourOfDay: Int, minuteOfHour: Int = 0): Long = Calendar.getInstance().apply {
        clear()
        set(date.year, date.monthValue - 1, date.dayOfMonth, hourOfDay, minuteOfHour, 0)
    }.timeInMillis

    private val monday = LocalDate.of(2026, 1, 5)
    private val personas = (1..40).map { SessionHabits.derive("persona-$it") }

    @Test
    fun `every calendar week averages at most the displayed rate, and close to it`() {
        for (h in personas) {
            for (week in 0 until 8) {
                val days = (0 until 7).map { DailyRhythm.dayFactor(h, monday.plusDays(7L * week + it).toEpochDay()) }
                val mean = days.average()
                assertTrue("${h.key} week $week mean $mean", mean <= 1.0 + 1e-9 && mean > 0.95)
                assertTrue("${h.key} days within bounds: $days", days.all { it in DailyRhythm.MIN_DAY_FACTOR..DailyRhythm.MAX_DAY_FACTOR + 1e-9 })
            }
        }
    }

    @Test
    fun `days vary, and a persona leans to weekends or weekdays as drawn`() {
        val busyWeekends = personas.first { it.weekendRatio > 1.5 }
        val busyWeekdays = personas.first { it.weekendRatio < 0.8 }
        fun weekendOverWeekday(h: SessionHabits): Double {
            val days = (0 until 56).map { monday.plusDays(it.toLong()) }
            val weekend = days.filter { it.dayOfWeek.value >= 6 }.map { DailyRhythm.dayFactor(h, it.toEpochDay()) }.average()
            val weekday = days.filter { it.dayOfWeek.value < 6 }.map { DailyRhythm.dayFactor(h, it.toEpochDay()) }.average()
            return weekend / weekday
        }
        assertTrue(weekendOverWeekday(busyWeekends) > 1.2)
        assertTrue(weekendOverWeekday(busyWeekdays) < 0.95)
        val oneWeek = (0 until 7).map { DailyRhythm.dayFactor(personas[0], monday.plusDays(it.toLong()).toEpochDay()) }
        assertTrue("weekdays are not all alike: $oneWeek", oneWeek.take(5).distinct().size == 5)
    }

    @Test
    fun `the default habits give a flat pace`() {
        assertTrue(DailyRhythm.pace(SessionHabits.DEFAULT, 7, 23) === AccrualPace.FLAT)
    }

    @Test
    fun `nothing accrues before the wake time or outside the allowed hours`() {
        val h = personas.first { it.wakeDelayMs >= 30 * minute }
        val pace = DailyRhythm.pace(h, 7, 23)
        assertEquals(0.0, pace.at(at(monday, 7, 1)), 0.0)
        assertEquals(0.0, pace.at(at(monday, 3)), 0.0)
        assertEquals(0.0, pace.at(at(monday, 23, 30)), 0.0)
        assertTrue("awake by midday", pace.at(at(monday, 13)) > 0.0)
    }

    @Test
    fun `a day's accrual is its day factor times the window, unless the plateau is capped`() {
        for (h in personas.take(15)) {
            val pace = DailyRhythm.pace(h, 7, 23)
            val budget = ActionBudget.holding(0.0, at(monday, 7)).also { it.pace = pace }
            var total = 0.0
            var t = at(monday, 7)
            while (t < at(monday, 23)) {
                t += 10 * minute
                val before = budget.available
                budget.accrue(t, 60)
                total += budget.available - before
                // Spend as it accrues, so the one-hour capacity never clips what accrued.
                while (budget.canSpend()) budget.spend()
            }
            val expected = DailyRhythm.dayFactor(h, monday.toEpochDay()) * 60 * 16
            assertTrue("${h.key}: $total vs $expected", total <= expected * 1.02)
            assertTrue("${h.key}: $total vs $expected", total >= expected * 0.6)
        }
    }

    @Test
    fun `time until saved accounts for the wait before waking`() {
        val h = personas.first { it.wakeDelayMs >= 60 * minute }
        val budget = ActionBudget.holding(0.0, at(monday, 7)).also { it.pace = DailyRhythm.pace(h, 7, 23) }
        val wait = budget.msUntil(1.0, 60)
        assertTrue("waits past the wake time (${h.wakeDelayMs / minute} min): ${wait / minute} min", wait >= h.wakeDelayMs)
        assertTrue(wait < 6 * hour)
    }
}
