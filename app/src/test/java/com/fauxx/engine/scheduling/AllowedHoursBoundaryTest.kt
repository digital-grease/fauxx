package com.fauxx.engine.scheduling

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** When the allowed-hours window containing a moment opened and when it closes (#322). */
class AllowedHoursBoundaryTest {

    private val hourMs = 3_600_000L

    /** Local time on 2026-01-07 (a Wednesday with no daylight-saving change) at [hour]:[minute], plus [days]. */
    private fun at(hour: Int, minute: Int = 0, days: Int = 0): Long = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.JANUARY, 7 + days, hour, minute, 0)
    }.timeInMillis

    @Test
    fun `a normal window opened today and closes today`() {
        val now = at(15, 30)
        assertEquals(at(7), AllowedHours.windowStartMs(now, 7, 23))
        assertEquals(7 * hourMs + 30 * 60_000L, AllowedHours.msUntilClose(now, 7, 23))
    }

    @Test
    fun `an end of 24 closes at the next midnight`() {
        val now = at(23, 0)
        assertEquals(hourMs, AllowedHours.msUntilClose(now, 7, 24))
    }

    @Test
    fun `a wrapping window in its evening part closes tomorrow`() {
        val now = at(23, 0)
        assertEquals(at(22), AllowedHours.windowStartMs(now, 22, 6))
        assertEquals(7 * hourMs, AllowedHours.msUntilClose(now, 22, 6))
    }

    @Test
    fun `a wrapping window after midnight opened the evening before`() {
        val now = at(2, 0)
        assertEquals(at(22, days = -1), AllowedHours.windowStartMs(now, 22, 6))
        assertEquals(4 * hourMs, AllowedHours.msUntilClose(now, 22, 6))
    }

    @Test
    fun `outside the window there is neither`() {
        val now = at(3, 0)
        assertNull(AllowedHours.windowStartMs(now, 7, 23))
        assertNull(AllowedHours.msUntilClose(now, 7, 23))
    }

    @Test
    fun `an all-day window never opens or closes`() {
        val now = at(12, 0)
        assertNull(AllowedHours.windowStartMs(now, 0, 0))
        assertNull(AllowedHours.msUntilClose(now, 13, 13))
        // 0-24 is also the whole day (#128): no close before midnight to guard.
        assertNull(AllowedHours.windowStartMs(at(23, 59), 0, 24))
        assertNull(AllowedHours.msUntilClose(at(23, 59), 0, 24))
    }
}
