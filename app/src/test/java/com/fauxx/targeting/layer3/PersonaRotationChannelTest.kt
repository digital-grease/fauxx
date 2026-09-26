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

    /**
     * A mid-range persona lifetime for the current 30-90 day model. Lags are a percentage of
     * this, so it has to be a realistic value rather than a token one.
     */
    private val lifetimeMs = TimeUnit.DAYS.toMillis(60)

    /** The lag ceiling implied by [lifetimeMs]. */
    private val maxLagMs = lifetimeMs * PersonaRotationLayer.CHANNEL_MAX_LAG_PERCENT / 100

    private fun persona(
        id: String,
        createdAt: Long,
        interests: Set<CategoryPool> = setOf(CategoryPool.COOKING),
    ) = SyntheticPersona(
        id = id, name = "Test", ageRange = "AGE_25_34", profession = "ENGINEER",
        region = "US_WEST", interests = interests,
        createdAt = createdAt, activeUntil = createdAt + lifetimeMs
    )

    /**
     * A persona whose channel lags are all >1h and pairwise distinct, found deterministically
     * so the stagger assertions can't be defeated by a lag of 0.
     */
    private fun staggeredPersona(layer: PersonaRotationLayer, createdAt: Long): SyntheticPersona =
        (0..999).asSequence().map { persona("persona-$it", createdAt) }.first { candidate ->
            val lags = PersonaChannel.entries.map { layer.adoptionLagMs(candidate, it) }
            lags.all { it > TimeUnit.HOURS.toMillis(1) } && lags.toSet().size == lags.size
        }

    @Test
    fun `disabled layer yields null for every channel even with personas present`() {
        val layer = layer()
        layer.setPersonasForTest(
            current = persona("current", clock.nowMs),
            previous = persona("previous", clock.nowMs - lifetimeMs)
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
        val rotatedAt = clock.nowMs
        val old = persona("old", rotatedAt - lifetimeMs)
        val fresh = staggeredPersona(layer, rotatedAt)
        layer.setPersonasForTest(current = fresh, previous = old)
        layer.setEnabled(true)

        PersonaChannel.entries.forEach { channel ->
            val lag = layer.adoptionLagMs(fresh, channel)

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
        val fresh = staggeredPersona(layer, clock.nowMs)
        val lags = PersonaChannel.entries.map { layer.adoptionLagMs(fresh, it) }

        assertEquals(lags, PersonaChannel.entries.map { layer.adoptionLagMs(fresh, it) })
        lags.forEach { assertTrue("lag $it out of bounds", it in 0 until maxLagMs) }
        assertEquals("channels must not adopt simultaneously", lags.size, lags.toSet().size)
    }

    @Test
    fun `adoption lag scales with the persona's own lifetime, not a fixed duration`() {
        // The regression this pins: a fixed 48h ceiling was ~22% of the old ~9-day lifetime but
        // only ~2% of a 90-day one, which would collapse the stagger into the synchronized
        // change-point it exists to prevent. Lags must widen as the lifetime widens.
        val layer = layer()
        val createdAt = clock.nowMs

        fun lagsFor(days: Long): List<Long> {
            val p = SyntheticPersona(
                id = "scale-probe", name = "Test", ageRange = "AGE_25_34",
                profession = "ENGINEER", region = "US_WEST",
                interests = setOf(CategoryPool.COOKING),
                createdAt = createdAt, activeUntil = createdAt + TimeUnit.DAYS.toMillis(days)
            )
            return PersonaChannel.entries.map { layer.adoptionLagMs(p, it) }
        }

        val short = lagsFor(30)
        val long = lagsFor(90)

        assertTrue(
            "a 90-day persona must stagger over a wider window than a 30-day one",
            long.max() > short.max()
        )
        short.forEach {
            assertTrue("30-day lag $it exceeds its own ceiling", it < TimeUnit.DAYS.toMillis(30) * 20 / 100)
        }
        long.forEach {
            assertTrue("90-day lag $it exceeds its own ceiling", it < TimeUnit.DAYS.toMillis(90) * 20 / 100)
        }
    }

    @Test
    fun `an absurd lifetime is clamped rather than overflowing into a huge lag`() {
        // A LAN-synced persona (#234) carries whatever activeUntil the peer sent, and sync
        // only validates that the field is PRESENT (SyncMessage.kt required-field list), not
        // that it is plausible. An unclamped `lifetime * percent` overflows for such values.
        //
        // The second case is the one that matters and the reason this test names an exact
        // number. Long.MAX_VALUE happens to wrap NEGATIVE, which the non-positive guard
        // catches by luck, so testing only that would pass with or without the clamp. The
        // lifetime below wraps POSITIVE to a ~11,574-day lag that the guard sails straight
        // past, pinning every channel on the previous persona effectively forever.
        val layer = layer()
        val createdAt = clock.nowMs
        val ceiling =
            PersonaGenerator.MAX_LIFETIME_MS * PersonaRotationLayer.CHANNEL_MAX_LAG_PERCENT / 100

        fun probe(label: String, activeUntil: Long) {
            val p = SyntheticPersona(
                id = "absurd", name = "Test", ageRange = "AGE_25_34", profession = "ENGINEER",
                region = "US_WEST", interests = setOf(CategoryPool.COOKING),
                createdAt = createdAt, activeUntil = activeUntil
            )
            PersonaChannel.entries.forEach { channel ->
                val lag = layer.adoptionLagMs(p, channel)
                assertTrue("$label: lag $lag must be non-negative", lag >= 0L)
                assertTrue("$label: lag $lag must not exceed clamped ceiling $ceiling", lag < ceiling)
            }
        }

        probe("wraps negative", Long.MAX_VALUE)
        probe("wraps positive", createdAt + 922_342_203_685_477_580L)
    }

    @Test
    fun `a persona with a non-positive lifetime gets no lag instead of a spurious one`() {
        val layer = layer()
        val createdAt = clock.nowMs
        val malformed = SyntheticPersona(
            id = "malformed", name = "Test", ageRange = "AGE_25_34", profession = "ENGINEER",
            region = "US_WEST", interests = setOf(CategoryPool.COOKING),
            createdAt = createdAt, activeUntil = createdAt
        )

        PersonaChannel.entries.forEach {
            assertEquals(0L, layer.adoptionLagMs(malformed, it))
        }
    }

    @Test
    fun `weights flow staggers adoption and emits the live E9 blend`() = runBlocking {
        // Pins two review findings at once: the Layer 3 category distribution honors
        // staggered adoption like every other bound channel (no synchronized
        // change-point at rotation), and the REAL getWeights() flow emits values from
        // weightsFor — severing the computeWeights delegation turns this red.
        val layer = layer()
        val rotatedAt = clock.nowMs
        val old = persona("old", rotatedAt - lifetimeMs,
            interests = setOf(CategoryPool.COOKING))
        // Interests don't enter the lag derivation (id, channel and lifetime do), so copying
        // them onto the staggered persona keeps the lags the helper just verified.
        val fresh = staggeredPersona(layer, rotatedAt).copy(interests = setOf(CategoryPool.GAMING))
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
        clock.nowMs = rotatedAt + maxLagMs
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
