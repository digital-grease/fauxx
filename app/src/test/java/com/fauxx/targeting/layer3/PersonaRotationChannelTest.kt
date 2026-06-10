package com.fauxx.targeting.layer3

import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.support.FakeClock
import io.mockk.mockk
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E8 (#174): exercises the REAL [PersonaRotationLayer.personaForChannel] — the single
 * binding point for location, app-signal, and rhythm channels. Pins the two safety
 * properties the module tests can't (they all mock this layer):
 *  1. Kill switch: Layer 3 disabled means NO channel sees a persona, even a stale one.
 *  2. Staggered adoption: after rotation, channels phase the new persona in at
 *     distinct, deterministic lags instead of stepping together (the synchronized
 *     multi-channel change-point a longitudinal observer could segment on).
 */
class PersonaRotationChannelTest {

    private val clock = FakeClock(1_000_000_000_000L)

    private fun layer(): PersonaRotationLayer =
        PersonaRotationLayer(mockk(relaxed = true), mockk(relaxed = true), clock)

    private fun persona(
        id: String,
        createdAt: Long,
        interests: Set<CategoryPool> = setOf(CategoryPool.COOKING),
    ) = SyntheticPersona(
        id = id, name = "Test", ageRange = "AGE_25_34", profession = "ENGINEER",
        region = "US_WEST", interests = interests,
        createdAt = createdAt, activeUntil = createdAt + TimeUnit.DAYS.toMillis(7)
    )

    /**
     * A persona id whose three channel lags are all >1h and pairwise distinct, found
     * deterministically so the stagger assertions can't be defeated by a lag of 0.
     */
    private fun staggeredId(layer: PersonaRotationLayer): String =
        (0..999).asSequence().map { "persona-$it" }.first { id ->
            val lags = PersonaChannel.entries.map { layer.adoptionLagMs(id, it) }
            lags.all { it > TimeUnit.HOURS.toMillis(1) } && lags.toSet().size == lags.size
        }

    @Test
    fun `disabled layer yields null for every channel even with personas present`() {
        val layer = layer()
        layer.setPersonasForTest(
            current = persona("current", clock.nowMs),
            previous = persona("previous", clock.nowMs - TimeUnit.DAYS.toMillis(8))
        )

        PersonaChannel.entries.forEach { assertNull(layer.personaForChannel(it)) }

        layer.setEnabled(true)
        PersonaChannel.entries.forEach {
            assertTrue(layer.personaForChannel(it) != null)
        }

        layer.setEnabled(false)
        PersonaChannel.entries.forEach { assertNull(layer.personaForChannel(it)) }
    }

    @Test
    fun `channels keep the previous persona during their lag and adopt after it`() {
        val layer = layer()
        val id = staggeredId(layer)
        val rotatedAt = clock.nowMs
        val old = persona("old", rotatedAt - TimeUnit.DAYS.toMillis(8))
        val fresh = persona(id, rotatedAt)
        layer.setPersonasForTest(current = fresh, previous = old)
        layer.setEnabled(true)

        PersonaChannel.entries.forEach { channel ->
            val lag = layer.adoptionLagMs(id, channel)

            clock.nowMs = rotatedAt + lag - 1
            assertEquals(
                "channel $channel must serve the previous persona inside its lag",
                old.id, layer.personaForChannel(channel)!!.id
            )

            clock.nowMs = rotatedAt + lag
            assertEquals(
                "channel $channel must adopt the new persona once its lag elapses",
                fresh.id, layer.personaForChannel(channel)!!.id
            )
        }
    }

    @Test
    fun `adoption lags are deterministic, bounded, and channel-distinct`() {
        val layer = layer()
        val id = staggeredId(layer)
        val lags = PersonaChannel.entries.map { layer.adoptionLagMs(id, it) }

        assertEquals(lags, PersonaChannel.entries.map { layer.adoptionLagMs(id, it) })
        lags.forEach { assertTrue("lag $it out of bounds", it in 0 until PersonaRotationLayer.CHANNEL_MAX_LAG_MS) }
        assertEquals("channels must not adopt simultaneously", lags.size, lags.toSet().size)
    }

    @Test
    fun `weights flow staggers adoption and emits the live E9 blend`() = runBlocking {
        // Pins two review findings at once: the Layer 3 category distribution honors
        // staggered adoption like every other bound channel (no synchronized
        // change-point at rotation), and the REAL getWeights() flow emits values from
        // weightsFor — severing the computeWeights delegation turns this red.
        val layer = layer()
        val id = staggeredId(layer)
        val rotatedAt = clock.nowMs
        val old = persona("old", rotatedAt - TimeUnit.DAYS.toMillis(8),
            interests = setOf(CategoryPool.COOKING))
        val fresh = persona(id, rotatedAt, interests = setOf(CategoryPool.GAMING))
        layer.setPersonasForTest(current = fresh, previous = old)
        layer.setEnabled(true)

        val aligned = PersonaRotationLayer
            .weightsFor(setOf(CategoryPool.COOKING)).getValue(CategoryPool.COOKING)
        val neutral = 1.0f

        // Inside the WEIGHTS lag: the blend still follows the PREVIOUS persona.
        val during = withTimeout(5_000) {
            layer.getWeights().first { it.getValue(CategoryPool.COOKING) != neutral }
        }
        assertEquals(aligned, during.getValue(CategoryPool.COOKING), 1e-6f)
        assertTrue(
            "new persona's interest must still be misaligned during the lag",
            during.getValue(CategoryPool.GAMING) < 1f
        )

        // Past every lag: the blend follows the NEW persona.
        clock.nowMs = rotatedAt + PersonaRotationLayer.CHANNEL_MAX_LAG_MS
        layer.reevaluateWeightsForTest()
        val after = withTimeout(5_000) {
            layer.getWeights().first { it.getValue(CategoryPool.GAMING) == aligned }
        }
        assertTrue(after.getValue(CategoryPool.COOKING) < 1f)
    }

    @Test
    fun `first persona ever - no previous - is adopted immediately on all channels`() {
        val layer = layer()
        val fresh = persona("first", clock.nowMs)
        layer.setPersonasForTest(current = fresh, previous = null)
        layer.setEnabled(true)

        PersonaChannel.entries.forEach { channel ->
            assertEquals(
                "process-restart degradation: no previous persona means immediate adoption",
                fresh.id, layer.personaForChannel(channel)!!.id
            )
        }
    }
}
