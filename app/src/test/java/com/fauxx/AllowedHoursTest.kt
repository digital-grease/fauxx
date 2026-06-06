package com.fauxx

import com.fauxx.engine.scheduling.AllowedHours
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the shared active-hours predicate (issue #124).
 *
 * [AllowedHours] is the single implementation behind both the engine's constraint gate
 * and the scheduler's quiet-hours branch; these tests pin the three window shapes so a
 * future "simplification" of one branch can't silently reintroduce the divergence.
 */
class AllowedHoursTest {

    @Test
    fun `normal window is active from start inclusive to end exclusive`() {
        assertTrue(AllowedHours.isWithin(hour = 7, start = 7, end = 23))
        assertTrue(AllowedHours.isWithin(hour = 15, start = 7, end = 23))
        assertTrue(AllowedHours.isWithin(hour = 22, start = 7, end = 23))
        assertFalse(AllowedHours.isWithin(hour = 23, start = 7, end = 23))
        assertFalse(AllowedHours.isWithin(hour = 3, start = 7, end = 23))
        assertFalse(AllowedHours.isWithin(hour = 6, start = 7, end = 23))
    }

    @Test
    fun `midnight wrap window is active across the boundary`() {
        assertTrue(AllowedHours.isWithin(hour = 22, start = 22, end = 6))
        assertTrue(AllowedHours.isWithin(hour = 23, start = 22, end = 6))
        assertTrue(AllowedHours.isWithin(hour = 0, start = 22, end = 6))
        assertTrue(AllowedHours.isWithin(hour = 5, start = 22, end = 6))
        assertFalse(AllowedHours.isWithin(hour = 6, start = 22, end = 6))
        assertFalse(AllowedHours.isWithin(hour = 12, start = 22, end = 6))
        assertFalse(AllowedHours.isWithin(hour = 21, start = 22, end = 6))
    }

    @Test
    fun `equal start and end means every hour is active - issue 124`() {
        // The degenerate window is how a user expresses "always on" (0-0 = midnight to
        // midnight). The scheduler's old private copy evaluated it as `hour in 0 until 0`,
        // an empty range, and slept until the next start-hour boundary.
        (0..24).forEach { boundary ->
            (0..23).forEach { hour ->
                assertTrue(
                    "hour $hour must be active in a $boundary-$boundary window",
                    AllowedHours.isWithin(hour = hour, start = boundary, end = boundary)
                )
            }
        }
    }

    @Test
    fun `full 0 to 24 window is always active`() {
        // Issue #128: the End slider reaches 24, the sanctioned full-day window.
        (0..23).forEach { hour ->
            assertTrue(
                "hour $hour must be active in a 0-24 window",
                AllowedHours.isWithin(hour = hour, start = 0, end = 24)
            )
        }
    }
}
