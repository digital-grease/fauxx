package com.fauxx

import com.fauxx.data.model.ActionType
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guardrail (issue #166): Fauxx generates page visits and queries, never monetizable
 * conversions or forged click/install/attribution events. The action taxonomy must
 * therefore never contain a conversion-shaped action. This locks in the E12 retirement
 * of AD_CLICK and fails the build if such a type is reintroduced.
 */
class ConversionGuardrailTest {

    private val forbiddenSubstrings = listOf(
        "CLICK", "CONVERSION", "ATTRIBUTION", "INSTALL", "PURCHASE", "CHECKOUT", "ACQUISITION"
    )

    @Test
    fun `no ActionType carries click conversion or attribution semantics`() {
        val offenders = ActionType.entries.filter { type ->
            forbiddenSubstrings.any { type.name.contains(it) }
        }
        assertTrue(
            "ActionType must not contain conversion-shaped actions; found: $offenders",
            offenders.isEmpty()
        )
    }
}
