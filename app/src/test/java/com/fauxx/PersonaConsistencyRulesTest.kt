package com.fauxx

import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.targeting.layer3.PersonaConsistencyRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class PersonaConsistencyRulesTest {

    private fun makePersona(
        ageRange: String = "35-44",
        interests: Set<CategoryPool> = setOf(CategoryPool.COOKING, CategoryPool.TRAVEL, CategoryPool.SPORTS),
        profession: String = "Professional"
    ) = SyntheticPersona(
        id = "test",
        name = "Test User",
        ageRange = ageRange,
        profession = profession,
        region = "US_MIDWEST",
        interests = interests,
        activeUntil = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(7)
    )

    @Test
    fun `valid persona passes consistency check`() {
        val persona = makePersona()
        assertTrue(PersonaConsistencyRules.isValid(persona))
    }

    @Test
    fun `persona with empty interests fails check`() {
        val persona = makePersona(interests = emptySet())
        assertFalse(PersonaConsistencyRules.isValid(persona))
    }

    @Test
    fun `persona with blank name fails check`() {
        val persona = makePersona().copy(name = "")
        assertFalse(PersonaConsistencyRules.isValid(persona))
    }

    @Test
    fun `overlap fraction computed correctly`() {
        val a = makePersona(interests = setOf(CategoryPool.GAMING, CategoryPool.MUSIC, CategoryPool.TRAVEL))
        val b = makePersona(interests = setOf(CategoryPool.GAMING, CategoryPool.MUSIC, CategoryPool.COOKING))
        // Intersection: GAMING, MUSIC = 2; Union: GAMING, MUSIC, TRAVEL, COOKING = 4
        val overlap = PersonaConsistencyRules.overlapFraction(a, b)
        assertTrue(Math.abs(overlap - 0.5f) < 0.01f)
    }

    @Test
    fun `zero overlap when no shared interests`() {
        val a = makePersona(interests = setOf(CategoryPool.GAMING, CategoryPool.MUSIC))
        val b = makePersona(interests = setOf(CategoryPool.MEDICAL, CategoryPool.LEGAL))
        val overlap = PersonaConsistencyRules.overlapFraction(a, b)
        assertTrue(overlap == 0f)
    }

    @Test
    fun `full overlap when same interests`() {
        val interests = setOf(CategoryPool.GAMING, CategoryPool.MUSIC, CategoryPool.TRAVEL)
        val a = makePersona(interests = interests)
        val b = makePersona(interests = interests)
        val overlap = PersonaConsistencyRules.overlapFraction(a, b)
        assertTrue(Math.abs(overlap - 1f) < 0.01f)
    }
}
