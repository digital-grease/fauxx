package com.fauxx

import com.fauxx.ui.format.personaAgeRangeRes
import com.fauxx.ui.format.personaProfessionRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the legacy-tolerance contract on the persona label helpers: enum names resolve
 * to label resources; anything else (personas persisted before the E7 enum-name
 * canonicalization, or template-only values with no enum entry) returns null so the UI
 * falls back to showing the raw stored string instead of crashing.
 */
class DemographicLabelsTest {

    @Test
    fun `enum names resolve to their label resources`() {
        assertEquals(R.string.age_35_44, personaAgeRangeRes("AGE_35_44"))
        assertEquals(R.string.age_65_plus, personaAgeRangeRes("AGE_65_PLUS"))
        assertEquals(R.string.profession_finance_prof, personaProfessionRes("FINANCE_PROF"))
        assertEquals(R.string.profession_other, personaProfessionRes("OTHER"))
    }

    @Test
    fun `legacy display strings return null for raw fallback`() {
        assertNull(personaAgeRangeRes("35-44"))
        assertNull(personaAgeRangeRes("65+"))
        assertNull(personaProfessionRes("Business Professional"))
        assertNull(personaProfessionRes("Professional"))
        assertNull(personaProfessionRes(""))
    }
}
