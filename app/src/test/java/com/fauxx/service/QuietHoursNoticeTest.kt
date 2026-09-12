package com.fauxx.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks the feedback rule for issue #198: tapping the notification's Start action outside
 * the configured active hours must explain itself instead of looking like a no-op, and must
 * stay silent when the engine is genuinely about to run.
 */
class QuietHoursNoticeTest {

    @Test
    fun `inside the active window produces no notice`() {
        // The engine will run immediately; the FGS notification is its own feedback.
        assertNull(quietHoursStartNotice(nowHour = 12, start = 7, end = 23))
    }

    @Test
    fun `at the exact start hour produces no notice`() {
        // The window is [start, end), so `start` itself is inside it.
        assertNull(quietHoursStartNotice(nowHour = 7, start = 7, end = 23))
    }

    @Test
    fun `at the exact end hour produces a notice`() {
        // `end` is exclusive, so 23:00 is already quiet hours.
        assertEquals(
            "Started. Paused until your active hours begin at 7:00.",
            quietHoursStartNotice(nowHour = 23, start = 7, end = 23)
        )
    }

    @Test
    fun `the reported repro produces a notice naming the start hour`() {
        // Issue #198's repro: active hours start at 19:00 while it is 18:33.
        assertEquals(
            "Started. Paused until your active hours begin at 19:00.",
            quietHoursStartNotice(nowHour = 18, start = 19, end = 23)
        )
    }

    @Test
    fun `a window wrapping midnight is respected`() {
        // 22:00-06:00. 02:00 is inside the window, 12:00 is not.
        assertNull(quietHoursStartNotice(nowHour = 2, start = 22, end = 6))
        assertEquals(
            "Started. Paused until your active hours begin at 22:00.",
            quietHoursStartNotice(nowHour = 12, start = 22, end = 6)
        )
    }

    @Test
    fun `a degenerate always-on window never notices`() {
        // start == end means "always allowed" per AllowedHours; never claim a pause.
        assertNull(quietHoursStartNotice(nowHour = 3, start = 0, end = 0))
    }
}
