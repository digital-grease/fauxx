package com.fauxx.engine.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PersonaJarStore]'s naming contract and its unsupported-device behaviour (issue #242).
 *
 * Robolectric's WebView is a stub, so `MULTI_PROFILE` reports unsupported here. That is the
 * point: this fixture exercises the fallback tier that every handset on an older WebView APK
 * runs, where the store must degrade to the process-global jar rather than throw. The supported
 * path needs a real device and is not reachable from a JVM test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PersonaJarStoreTest {

    private val store = PersonaJarStore()

    @Test
    fun `jar keys are deterministic so a persona's storage survives a process restart`() {
        assertEquals(store.jarKeyFor("persona-a"), PersonaJarStore().jarKeyFor("persona-a"))
    }

    @Test
    fun `distinct personas get distinct jars`() {
        assertNotEquals(store.jarKeyFor("persona-a"), store.jarKeyFor("persona-b"))
    }

    @Test
    fun `a jar key never leaks the persona id it belongs to`() {
        // WebView persists profile names in its own pref store in plaintext, inside the app
        // sandbox but outside SQLCipher, and the directories are enumerable by anything with
        // filesystem access to the app data dir. The name must disclose nothing.
        val personaId = "persona-with-a-telling-name"
        val key = store.jarKeyFor(personaId)

        assertFalse("jar key must not embed the persona id", key.contains(personaId))
        assertTrue("jar key must be marked as ours so the sweep skips foreign jars", key.startsWith("p"))
        assertEquals("jar key should stay short and fixed-width", 13, key.length)
    }

    @Test
    fun `an unsupported device falls back to the shared jar instead of throwing`() {
        assertFalse("Robolectric's WebView stub must report MULTI_PROFILE unsupported", store.isSupported)

        // Every entry point has to survive this, because it is a whole population of real
        // devices, not an error case.
        assertEquals(0, runBlocking { store.deleteJarsExcept(setOf("anything")) })
        // Resolves to the process-global CookieManager rather than throwing or returning null.
        store.cookieManagerFor(store.jarKeyFor("persona-a"))
        store.cookieManagerFor(null)
    }
}
