package com.fauxx.engine.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Payload structure / composition guard for [JSInjector].
 *
 * These scripts are static string constants with NO runtime interpolation, so there is
 * nothing to escape and no need to mock a WebView. This suite is therefore framed honestly
 * as a tripwire over the shape and composition of the payloads: it verifies that the
 * combined script contains every individual payload in the expected order, that each
 * payload is a well-formed IIFE with balanced delimiters, and that the security-relevant
 * API names each payload is supposed to override are actually present. It does NOT execute
 * or parse the JavaScript.
 */
class JSInjectorTest {

    /** The five individual payloads that [JSInjector.ALL_SCRIPTS] is built from. */
    private val individualScripts = listOf(
        JSInjector.CANVAS_NOISE_SCRIPT,
        JSInjector.NAVIGATOR_OVERRIDE_SCRIPT,
        JSInjector.FONT_SPOOF_SCRIPT,
        JSInjector.WASM_WORKER_BLOCK_SCRIPT,
        JSInjector.EVAL_BLOCK_SCRIPT
    )

    // --- Composition of ALL_SCRIPTS ---------------------------------------------------------

    @Test
    fun `ALL_SCRIPTS contains every individual payload`() {
        for (script in individualScripts) {
            assertTrue(JSInjector.ALL_SCRIPTS.contains(script))
        }
    }

    @Test
    fun `ALL_SCRIPTS joins payloads with a blank-line separator`() {
        // joinToString("\n\n") puts exactly one blank line between adjacent payloads.
        assertTrue(JSInjector.ALL_SCRIPTS.contains("})();\n\n(function()"))
    }

    @Test
    fun `ALL_SCRIPTS orders payloads WASM EVAL CANVAS NAVIGATOR FONT`() {
        // The listOf order in ALL_SCRIPTS differs from the source declaration order;
        // assert the combined order via ascending index of each payload.
        val all = JSInjector.ALL_SCRIPTS
        val wasm = all.indexOf(JSInjector.WASM_WORKER_BLOCK_SCRIPT)
        val eval = all.indexOf(JSInjector.EVAL_BLOCK_SCRIPT)
        val canvas = all.indexOf(JSInjector.CANVAS_NOISE_SCRIPT)
        val navigator = all.indexOf(JSInjector.NAVIGATOR_OVERRIDE_SCRIPT)
        val font = all.indexOf(JSInjector.FONT_SPOOF_SCRIPT)

        // every payload is actually present
        assertTrue(wasm >= 0)
        assertTrue(eval >= 0)
        assertTrue(canvas >= 0)
        assertTrue(navigator >= 0)
        assertTrue(font >= 0)

        // and they appear in the listOf order: WASM < EVAL < CANVAS < NAVIGATOR < FONT
        assertTrue(wasm < eval)
        assertTrue(eval < canvas)
        assertTrue(canvas < navigator)
        assertTrue(navigator < font)
    }

    // --- IIFE shape -------------------------------------------------------------------------

    @Test
    fun `each payload is an IIFE wrapper`() {
        for (script in individualScripts) {
            val trimmed = script.trim()
            assertTrue(trimmed.startsWith("(function()"))
            assertTrue(trimmed.endsWith("})();"))
        }
    }

    @Test
    fun `each payload has balanced parentheses and braces`() {
        // Syntactic tripwire, NOT a real JS parser: this only compares total open/close
        // character counts. It catches a dropped delimiter from a future edit, but does
        // not validate nesting or string-literal context.
        for (script in individualScripts) {
            assertEquals(script.count { it == '(' }, script.count { it == ')' })
            assertEquals(script.count { it == '{' }, script.count { it == '}' })
        }
    }

    // --- API names each payload must override ----------------------------------------------

    @Test
    fun `CANVAS payload overrides canvas image APIs and applies pixel noise`() {
        val script = JSInjector.CANVAS_NOISE_SCRIPT
        assertTrue(script.contains("HTMLCanvasElement.prototype.getContext"))
        assertTrue(script.contains("getImageData"))
        // exact perturbation noise call from the canvas payload
        assertTrue(script.contains("imageData.data[i] += Math.floor(Math.random() * 3) - 1;"))
    }

    @Test
    fun `NAVIGATOR payload redefines hardwareConcurrency and deviceMemory`() {
        val script = JSInjector.NAVIGATOR_OVERRIDE_SCRIPT
        assertTrue(script.contains("hardwareConcurrency"))
        assertTrue(script.contains("deviceMemory"))
        assertTrue(script.contains("Object.defineProperty"))
    }

    @Test
    fun `WASM payload blocks WebAssembly and worker primitives`() {
        val script = JSInjector.WASM_WORKER_BLOCK_SCRIPT
        assertTrue(script.contains("WebAssembly"))
        assertTrue(script.contains("window.Worker"))
        assertTrue(script.contains("window.SharedWorker"))
        assertTrue(script.contains("navigator.serviceWorker"))
    }

    @Test
    fun `EVAL payload blocks eval Function and string-arg timers`() {
        val script = JSInjector.EVAL_BLOCK_SCRIPT
        assertTrue(script.contains("window.eval"))
        assertTrue(script.contains("window.Function"))
        assertTrue(script.contains("setTimeout"))
        assertTrue(script.contains("setInterval"))
        // the string-argument guard that defeats implicit eval via timers
        assertTrue(script.contains("typeof fn === 'string'"))
    }

    @Test
    fun `FONT payload wraps OffscreenCanvas`() {
        assertTrue(JSInjector.FONT_SPOOF_SCRIPT.contains("OffscreenCanvas"))
    }

    // --- Interpolation canary ---------------------------------------------------------------

    @Test
    fun `no payload contains an unresolved Kotlin template marker`() {
        // These are raw string constants with no interpolation. If a future edit introduces
        // a Kotlin string template, the literal "${" two-character sequence would only survive
        // into the payload if it were escaped/unresolved. Guard against that leaking out.
        val marker = "\${"
        for (script in individualScripts) {
            assertFalse(script.contains(marker))
        }
        assertFalse(JSInjector.ALL_SCRIPTS.contains(marker))
    }
}
