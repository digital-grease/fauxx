package com.fauxx

import com.fauxx.data.SensitiveAttributes
import com.fauxx.data.querybank.CategoryPool
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guardrail (issue #167): the targeting taxonomy must never include a sensitive
 * attribute. [CategoryPool] is the root taxonomy; persona interests in
 * persona_templates.json are drawn from CategoryPool values, so scanning the enum
 * transitively covers persona interests. Query-content scanning is intentionally not
 * done here: harmful query content is handled by QueryBlocklist, and substring
 * scanning a large query corpus would false-positive on topical words.
 */
class SensitiveAttributeGuardrailTest {

    @Test
    fun `no CategoryPool value targets a sensitive attribute`() {
        val offenders = CategoryPool.entries.filter { SensitiveAttributes.matches(it.name) }
        assertTrue(
            "CategoryPool must not target sensitive attributes; found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `denylist matcher is word-boundary aware`() {
        // Sanity check the matcher itself so the guardrail above cannot silently
        // pass because the matcher is broken.
        assertTrue("a standalone sensitive term must match", SensitiveAttributes.matches("what race am I"))
        assertFalse("a substring must not match (racing is not race)", SensitiveAttributes.matches("racing cars"))
        assertFalse("an unrelated category must not match", SensitiveAttributes.matches("AUTOMOTIVE"))
    }
}
