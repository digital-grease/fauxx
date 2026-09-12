package com.fauxx.engine.modules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the engine opt-out contract for issue #281. The user asked to spare an engine they
 * trust from synthetic traffic; the module must honor that without ever leaving itself with
 * nothing to search on.
 */
class SearchEngineSelectionTest {

    private val all = listOf("google", "bing", "duckduckgo", "yahoo", "yandex")

    @Test
    fun `no exclusions keeps the whole pool`() {
        assertEquals(all, activeSearchEngines(all, emptySet()))
    }

    @Test
    fun `an excluded engine is dropped`() {
        // The headline request: spare DuckDuckGo, keep poisoning everything else.
        val kept = activeSearchEngines(all, setOf("duckduckgo"))
        assertEquals(listOf("google", "bing", "yahoo", "yandex"), kept)
    }

    @Test
    fun `pool order is preserved`() {
        assertEquals(listOf("google", "yahoo"), activeSearchEngines(all, setOf("bing", "duckduckgo", "yandex")))
    }

    @Test
    fun `an exclusion set that would empty the pool fails safe`() {
        // Defensive only: the UI pins two engines on. But a corrupt or stale preference
        // must never silently stop the search module altogether.
        assertEquals(all, activeSearchEngines(all, all.toSet()))
    }

    @Test
    fun `unknown engine names in the exclusion set are harmless`() {
        // A preference written by a future build that named an engine this one lacks.
        assertEquals(all, activeSearchEngines(all, setOf("kagi", "brave")))
    }

    @Test
    fun `every shipped engine id has a display name`() {
        // A missing entry would render a raw lowercase id in Settings.
        for (id in SEARCH_ENGINE_IDS) {
            val label = searchEngineDisplayName(id)
            assertTrue("display name for '$id' should be capitalized, got '$label'", label.first().isUpperCase())
        }
        assertEquals("DuckDuckGo", searchEngineDisplayName("duckduckgo"))
    }
}
