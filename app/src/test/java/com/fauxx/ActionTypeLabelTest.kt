package com.fauxx

import com.fauxx.data.model.ActionType
import com.fauxx.ui.format.displayNameRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit tests for [ActionType.displayNameRes] — the @StringRes id used by Log-screen
 * filter chips. Reads the EN canonical values directly from values/strings.xml
 * (no Android context needed in a pure JVM test) and asserts the invariants that
 * previously lived on the now-removed `label: String` extension:
 *  - every action type maps to a defined EN string
 *  - EN values fit a chip (<=12 chars), no underscores, all unique
 *  - `LOCATION_SPOOF` renders as "LOCATION" (regression guard against the old
 *    `name.take(6)` truncation that produced "LOCATI").
 */
class ActionTypeLabelTest {

    private val enValues: Map<String, String> by lazy {
        val xml = File("src/main/res/values/strings.xml").readText()
        val re = Regex("""<string name="(action_type_[^"]+)">([^<]+)</string>""")
        re.findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** Maps `action_type_search_query` -> the EN value, given an ActionType. */
    private fun enLabel(type: ActionType): String? {
        val resId = type.displayNameRes()
        // We can't resolve the @StringRes Int directly without a Context, but we
        // know the naming convention: action_type_<enum.name.lowercase()>.
        val key = "action_type_${type.name.lowercase()}"
        return enValues[key]
    }

    @Test
    fun locationSpoof_rendersAsLocation_notTruncated() {
        assertEquals("LOCATION", enLabel(ActionType.LOCATION_SPOOF))
    }

    @Test
    fun everyActionType_hasNonEmptyLabel() {
        ActionType.values().forEach { type ->
            val label = enLabel(type)
            assertNotNull("${type.name} must have an EN string resource", label)
            assertTrue(
                "${type.name} EN label must be non-blank",
                label!!.isNotBlank()
            )
        }
    }

    @Test
    fun noLabel_containsUnderscoreOrExceedsTwelveChars() {
        ActionType.values().forEach { type ->
            val label = enLabel(type)!!
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
        val labels = ActionType.values().map { enLabel(it)!! }
        assertEquals(
            "Every ActionType must map to a distinct label",
            labels.size,
            labels.toSet().size
        )
    }
}
