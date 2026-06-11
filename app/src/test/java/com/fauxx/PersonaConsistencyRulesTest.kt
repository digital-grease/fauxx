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
        ageRange: String = "AGE_35_44",
        interests: Set<CategoryPool> = setOf(CategoryPool.COOKING, CategoryPool.TRAVEL, CategoryPool.SPORTS),
        profession: String = "OTHER"
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

    // The incompatible-trait rules match on AgeRange enum names — the canonical
    // SyntheticPersona format. Before the E7 canonicalization they compared display
    // strings ("65+"), which template-sourced personas never carried, so the rules
    // were silently inert.

    @Test
    fun `retiree-age persona with narrow academic interests fails check`() {
        val persona = makePersona(
            ageRange = "AGE_65_PLUS",
            interests = setOf(CategoryPool.ACADEMIC, CategoryPool.COOKING)
        )
        assertFalse(PersonaConsistencyRules.isValid(persona))
    }

    @Test
    fun `retiree-age persona with broad interests including academic passes check`() {
        val persona = makePersona(
            ageRange = "AGE_65_PLUS",
            interests = setOf(CategoryPool.ACADEMIC, CategoryPool.COOKING, CategoryPool.TRAVEL)
        )
        assertTrue(PersonaConsistencyRules.isValid(persona))
    }

    @Test
    fun `young persona with retirement interest but no finance context fails check`() {
        val persona = makePersona(
            ageRange = "AGE_18_24",
            interests = setOf(CategoryPool.RETIREMENT, CategoryPool.GAMING)
        )
        assertFalse(PersonaConsistencyRules.isValid(persona))
    }

    @Test
    fun `young persona with retirement interest and finance context passes check`() {
        val persona = makePersona(
            ageRange = "AGE_18_24",
            interests = setOf(CategoryPool.RETIREMENT, CategoryPool.FINANCE)
        )
        assertTrue(PersonaConsistencyRules.isValid(persona))
    }

    @Test
    fun `young persona whose sole interest is parenting fails check`() {
        val persona = makePersona(
            ageRange = "AGE_18_24",
            interests = setOf(CategoryPool.PARENTING)
        )
        assertFalse(PersonaConsistencyRules.isValid(persona))
    }

    @Test
    fun `young persona with parenting among other interests passes check`() {
        val persona = makePersona(
            ageRange = "AGE_18_24",
            interests = setOf(CategoryPool.PARENTING, CategoryPool.COOKING)
        )
        assertTrue(PersonaConsistencyRules.isValid(persona))
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
