package com.fauxx

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.scheduling.PoissonScheduler
import com.fauxx.engine.scheduling.RateModulator
import com.fauxx.support.FakeClock
import java.util.Calendar
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E8 (#174): the rate-modulation seam in [PoissonScheduler]. With identical Random
 * seeds, two schedulers consume identical random sequences and take identical branch
 * decisions, so delays are pairwise comparable: only the Poisson rate scaling differs.
 */
class RateModulatorSchedulerTest {

    private fun epochAtLocalHour(hour: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun delays(
        modulator: RateModulator,
        seed: Long,
        n: Int = 200,
        hour: Int = 12,
    ): List<Long> {
        val scheduler = PoissonScheduler(
            FakeClock(epochAtLocalHour(hour)), Random(seed), modulator
        )
        return (1..n).map {
            scheduler.nextDelayMs(
                actionsPerHour = 60,
                prev = CategoryPool.COOKING,
                next = CategoryPool.COOKING
            )
        }
    }

    @Test
    fun `a higher multiplier never lengthens any delay - same-seed pairwise`() {
        val slow = delays(RateModulator { 0.5f }, seed = 7)
        val fast = delays(RateModulator { 1.5f }, seed = 7)
        slow.zip(fast).forEachIndexed { i, (s, f) ->
            assertTrue("sample $i: fast delay $f > slow delay $s", f <= s)
        }
        assertTrue(
            "rate modulation must actually change the schedule",
            fast.sum() < slow.sum()
        )
    }

    @Test
    fun `multipliers are clamped to the documented bounds`() {
        assertEquals(
            delays(RateModulator { 1.5f }, seed = 11),
            delays(RateModulator { 10f }, seed = 11)
        )
        assertEquals(
            delays(RateModulator { 0.5f }, seed = 11),
            delays(RateModulator { 0.01f }, seed = 11)
        )
    }

    @Test
    fun `scheduler passes the true hour of day to the modulator`() {
        // Hour-sensitive modulator: behaves like the 1.5 constant ONLY at hour 12 and
        // like the 0.5 constant elsewhere. If the scheduler passed a wrong hour source
        // (e.g. Calendar.HOUR, which is 0 at noon), the persona rhythm would silently
        // become a constant rate bias and break the 24h-mean honesty contract (#76).
        val hourSensitive = RateModulator { hour -> if (hour == 12) 1.5f else 0.5f }

        assertEquals(
            delays(RateModulator { 1.5f }, seed = 5, hour = 12),
            delays(hourSensitive, seed = 5, hour = 12)
        )
        assertEquals(
            delays(RateModulator { 0.5f }, seed = 5, hour = 18),
            delays(hourSensitive, seed = 5, hour = 18)
        )
    }

    @Test
    fun `omitting the modulator is exactly NEUTRAL - pre-E8 behavior preserved`() {
        val implicit = PoissonScheduler(FakeClock(epochAtLocalHour(12)), Random(3))
        val explicit = PoissonScheduler(
            FakeClock(epochAtLocalHour(12)), Random(3), RateModulator.NEUTRAL
        )
        repeat(100) {
            assertEquals(
                explicit.nextDelayMs(60, prev = CategoryPool.COOKING, next = CategoryPool.COOKING),
                implicit.nextDelayMs(60, prev = CategoryPool.COOKING, next = CategoryPool.COOKING)
            )
        }
    }
}
