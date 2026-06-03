package com.fauxx

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.scheduling.PoissonScheduler
import com.fauxx.support.FakeClock
import com.fauxx.support.seededRandom
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property tests for [PoissonScheduler]'s timing invariants across seeded RNG and intensity:
 *  - poissonDelay never returns below the 1s floor.
 *  - a cross-niche transition (prev != next) never returns below the 30s dwell floor — a
 *    sub-30s switch between disparate content niches is the exact heuristic bot signal the
 *    scheduler exists to avoid.
 *
 * Allowed-hours are set to the full day (0..24) so the quiet-hours branch never fires and the
 * clock value is irrelevant; the seeded Random + FakeClock make every case deterministic.
 */
class PoissonSchedulerPropertyTest {

    private fun scheduler(seed: Long) = PoissonScheduler(FakeClock(0L), seededRandom(seed))

    @Test
    fun `poissonDelay never returns below the 1s floor`() = runBlocking<Unit> {
        checkAll(1_000, Arb.long(), Arb.int(min = 1, max = 300)) { seed, actionsPerHour ->
            val delay = scheduler(seed).poissonDelay(actionsPerHour.toFloat())
            assertTrue("poissonDelay below the 1s floor: $delay (aph=$actionsPerHour)", delay >= 1_000L)
        }
    }

    @Test
    fun `a cross-niche transition never fires below the 30s dwell floor`() = runBlocking<Unit> {
        val categories = CategoryPool.values().toList()
        checkAll(1_000, Arb.long(), Arb.int(min = 1, max = 300)) { seed, actionsPerHour ->
            // prev != next => cross-niche; allowedStart=0/allowedEnd=24 => never quiet hours.
            val delay = scheduler(seed).nextDelayMs(
                actionsPerHour = actionsPerHour,
                prev = categories[0],
                next = categories[1],
                allowedStart = 0,
                allowedEnd = 24,
            )
            assertTrue(
                "cross-niche delay below the 30s floor: $delay (aph=$actionsPerHour seed=$seed)",
                delay >= 30_000L,
            )
        }
    }
}
