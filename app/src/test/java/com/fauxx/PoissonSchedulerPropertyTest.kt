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
 *  - a cross-niche transition (prev != next) never returns below the tier-derived dwell floor
 *    (half the mean inter-arrival, clamped to [5s, 30s]) — too-fast switches between disparate
 *    content niches are the heuristic bot signal the scheduler exists to soften, and the floor
 *    relaxes on the aggressive tiers so their actions/hour targets stay achievable.
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
    fun `a cross-niche transition never fires below the tier-derived dwell floor`() = runBlocking<Unit> {
        val categories = CategoryPool.values().toList()
        // Range spans LOW (12) through EXTREME (500) so every floor regime is exercised:
        // the 30s clamp (<=60/hr), the sloped middle, and the 5s floor (>360/hr).
        checkAll(1_000, Arb.long(), Arb.int(min = 1, max = 600)) { seed, actionsPerHour ->
            // prev != next => cross-niche; allowedStart=0/allowedEnd=24 => never quiet hours.
            val delay = scheduler(seed).nextDelayMs(
                actionsPerHour = actionsPerHour,
                prev = categories[0],
                next = categories[1],
                allowedStart = 0,
                allowedEnd = 24,
            )
            // Mirrors PoissonScheduler.crossNicheFloorMs: half the mean inter-arrival,
            // clamped to [5s, 30s].
            val expectedFloor = (1_800_000L / actionsPerHour).coerceIn(5_000L, 30_000L)
            assertTrue(
                "cross-niche delay $delay below the tier floor $expectedFloor (aph=$actionsPerHour seed=$seed)",
                delay >= expectedFloor,
            )
        }
    }
}
