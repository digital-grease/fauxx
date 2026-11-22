package com.fauxx.engine.scheduling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-persona session habits (#322): deterministic, in range, and varied across personas. */
class SessionHabitsTest {

    private val keys = (1..200).map { "persona-$it" }

    @Test
    fun `the same key always gives the same habits`() {
        assertEquals(SessionHabits.derive("persona-a"), SessionHabits.derive("persona-a"))
    }

    @Test
    fun `every value stays in its range`() {
        for (key in keys) {
            val h = SessionHabits.derive(key)
            assertTrue(h.sessionPace in 1.8..2.6)
            assertTrue(h.burstMean in 2.5..4.0)
            assertTrue(h.thinkMedianMs in 1_000L..3_000L)
            assertTrue(h.stayOnTopic in 0.35..0.65)
            assertTrue(h.breakMedianMs in 30L * 60_000..60L * 60_000)
            assertTrue(h.endEarly in 0.1..0.3)
        }
    }

    @Test
    fun `personas do not share one rhythm`() {
        val habits = keys.map { SessionHabits.derive(it) }
        assertTrue(habits.map { it.sessionPace }.distinct().size > 50)
        assertTrue(habits.map { it.breakMedianMs }.distinct().size > 20)
        assertTrue(habits.distinct().size == habits.size)
    }
}
