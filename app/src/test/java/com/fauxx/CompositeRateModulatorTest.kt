package com.fauxx

import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.scheduling.CircadianRateModulator
import com.fauxx.engine.scheduling.CompositeRateModulator
import com.fauxx.engine.scheduling.RateModulator
import com.fauxx.engine.scheduling.UsageHistogram
import com.fauxx.targeting.layer3.PersonaRateModulator
import com.fauxx.targeting.layer3.PersonaRotationLayer
import io.mockk.every
import io.mockk.mockk
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E10 (#177): the single seam composing the persona rhythm (E8) with the observed circadian
 * rhythm (E10). Verifies the combined curve stays honest (24h mean exactly 1.0, issue #76) and
 * within the clamp band, that each input degrades to neutral independently, and that the
 * composite reduces to whichever signal is present.
 */
class CompositeRateModulatorTest {

    private fun persona(id: String?) = id?.let {
        SyntheticPersona(
            id = it, name = "Test", ageRange = "AGE_25_34", profession = "ENGINEER",
            region = "US_WEST", interests = setOf(CategoryPool.COOKING), activeUntil = Long.MAX_VALUE
        )
    }

    private fun personaModulator(id: String?): PersonaRateModulator {
        val layer = mockk<PersonaRotationLayer> { every { personaForChannel(any()) } returns persona(id) }
        return PersonaRateModulator(layer)
    }

    private fun circadianModulator(counts: LongArray): CircadianRateModulator =
        CircadianRateModulator(object : UsageHistogram {
            override fun hourlyCounts(): LongArray = counts
        })

    private val matureEvening = LongArray(24).apply {
        for (h in 0..23) this[h] = 2L
        for (h in 18..22) this[h] = 40L
    }
    private val sparse = LongArray(24) // below maturity -> circadian neutral

    private fun curve(m: CompositeRateModulator): List<Float> = (0..23).map { m.multiplier(it) }

    @Test
    fun `neutral when neither signal is present`() {
        val c = curve(CompositeRateModulator(personaModulator(null), circadianModulator(sparse)))
        c.forEach { assertEquals(1f, it, 1e-6f) }
    }

    @Test
    fun `reduces to the circadian curve when no persona is active`() {
        val circadian = circadianModulator(matureEvening)
        val composite = CompositeRateModulator(personaModulator(null), circadian)
        (0..23).forEach { h ->
            assertEquals("hour $h", circadian.multiplier(h), composite.multiplier(h), 1e-5f)
        }
    }

    @Test
    fun `reduces to the persona curve when circadian has too little data`() {
        val persona = personaModulator("stable-id")
        val composite = CompositeRateModulator(persona, circadianModulator(sparse))
        (0..23).forEach { h ->
            assertEquals("hour $h", persona.multiplier(h), composite.multiplier(h), 1e-5f)
        }
    }

    @Test
    fun `both signals - 24h mean stays at one and stays within the band`() {
        val c = curve(CompositeRateModulator(personaModulator("stable-id"), circadianModulator(matureEvening)))
        val mean = c.sum() / c.size
        assertTrue("mean $mean drifted from 1.0", abs(mean - 1f) < 1e-3f)
        c.forEachIndexed { h, v ->
            assertTrue(
                "hour $h multiplier $v outside band",
                v in RateModulator.MIN_MULTIPLIER..RateModulator.MAX_MULTIPLIER
            )
        }
    }

    @Test
    fun `both signals combine - composite differs from either input alone`() {
        val persona = personaModulator("stable-id")
        val circadian = circadianModulator(matureEvening)
        val composite = CompositeRateModulator(persona, circadian)
        val comp = curve(composite)
        val personaOnly = (0..23).map { persona.multiplier(it) }
        val circadianOnly = (0..23).map { circadian.multiplier(it) }
        assertTrue("composite must not equal persona alone", comp != personaOnly)
        assertTrue("composite must not equal circadian alone", comp != circadianOnly)
    }

    @Test
    fun `out-of-range hours are neutral`() {
        val m = CompositeRateModulator(personaModulator("stable-id"), circadianModulator(matureEvening))
        assertEquals(1f, m.multiplier(-1), 0f)
        assertEquals(1f, m.multiplier(24), 0f)
    }
}
