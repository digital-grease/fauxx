package com.fauxx

import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.targeting.layer3.PersonaRateModulator
import com.fauxx.targeting.layer3.PersonaRotationLayer
import io.mockk.every
import io.mockk.mockk
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E8 (#174): persona-keyed daily rhythm. Each persona gets a deterministic activity
 * profile; the 24h mean must stay ~1.0 (rate honesty, issue #76); no persona -> neutral.
 */
class PersonaRateModulatorTest {

    private fun persona(id: String) = SyntheticPersona(
        id = id, name = "Test", ageRange = "AGE_25_34", profession = "ENGINEER",
        region = "US_WEST", interests = setOf(CategoryPool.COOKING),
        activeUntil = Long.MAX_VALUE
    )

    private fun modulatorFor(p: SyntheticPersona?): PersonaRateModulator {
        val layer = mockk<PersonaRotationLayer> { every { personaForChannel(any()) } returns p }
        return PersonaRateModulator(layer)
    }

    private fun dayProfile(m: PersonaRateModulator): List<Float> =
        (0..23).map { m.multiplier(it) }

    @Test
    fun `neutral when no persona is active`() {
        val profile = dayProfile(modulatorFor(null))
        profile.forEach { assertEquals(1f, it, 0f) }
    }

    @Test
    fun `24-hour mean stays at one - rate honesty`() {
        val profile = dayProfile(modulatorFor(persona("some-persona-id")))
        val mean = profile.sum() / profile.size
        assertTrue("mean $mean drifted from 1.0", abs(mean - 1f) < 1e-3f)
    }

    @Test
    fun `hourly swing stays within the documented amplitude`() {
        val profile = dayProfile(modulatorFor(persona("another-id")))
        profile.forEachIndexed { hour, m ->
            assertTrue("hour $hour multiplier $m outside 0.8..1.2", m in 0.8f..1.2f)
        }
        assertTrue("rhythm must not be flat", profile.max() > profile.min())
    }

    @Test
    fun `same persona keeps the same rhythm for its whole life`() {
        val a = dayProfile(modulatorFor(persona("stable-id")))
        val b = dayProfile(modulatorFor(persona("stable-id")))
        assertEquals(a, b)
    }

    @Test
    fun `peak hour stays inside the documented 11-17 window for any id hash sign`() {
        // Includes ids hashing negative (a `%` regression instead of `mod` would push
        // their peaks to 5..11) — "polygenelubricants" hashes to Int.MIN_VALUE.
        val ids = (0..99).map { "persona-$it" } + "polygenelubricants" + ""
        assertTrue("need negative-hash coverage", ids.any { it.hashCode() < 0 })
        ids.forEach { id ->
            val profile = dayProfile(modulatorFor(persona(id)))
            val peakHour = profile.indices.maxBy { profile[it] }
            assertTrue(
                "id '$id' (hash ${id.hashCode()}): peak at $peakHour outside 11..17",
                peakHour in 11..17
            )
        }
    }

    @Test
    fun `rotation can shift the rhythm - distinct personas may differ`() {
        // "a" and "b" hash to different peak offsets; locks the persona-keying seam.
        val a = dayProfile(modulatorFor(persona("a")))
        val b = dayProfile(modulatorFor(persona("b")))
        assertTrue("personas with different peak offsets must differ", a != b)
    }
}
