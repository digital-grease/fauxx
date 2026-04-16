package com.fauxx

import com.fauxx.data.model.ActionType
import com.fauxx.ui.format.label
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ActionType.label] — the presentation-layer mapping used by
 * filter chips in the Action Log screen.
 *
 * Guards against a regression where `LOCATION_SPOOF` rendered as "LOCATI" (via
 * `name.take(6)`), and ensures every action type has a readable label.
 */
class ActionTypeLabelTest {

    @Test
    fun locationSpoof_rendersAsLocation_notTruncated() {
        assertEquals("LOCATION", ActionType.LOCATION_SPOOF.label)
    }

    @Test
    fun everyActionType_hasNonEmptyLabel() {
        ActionType.values().forEach { type ->
            assertTrue(
                "${type.name} must have a non-blank label",
                type.label.isNotBlank()
            )
        }
    }

    @Test
    fun noLabel_containsUnderscoreOrExceedsTwelveChars() {
        ActionType.values().forEach { type ->
            val label = type.label
            assertFalse(
                "${type.name} label '$label' must not contain underscores",
                label.contains('_')
            )
            assertTrue(
                "${type.name} label '$label' must fit in a chip (<=12 chars)",
                label.length <= 12
            )
        }
    }

    @Test
    fun allLabels_areUnique() {
        val labels = ActionType.values().map { it.label }
        assertEquals(
            "Every ActionType must map to a distinct label",
            labels.size,
            labels.toSet().size
        )
    }
}
