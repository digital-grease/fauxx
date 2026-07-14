package com.fauxx.engine.webview

import com.fauxx.data.device.Brand
import com.fauxx.data.device.DeviceProfile
import com.fauxx.data.device.FormFactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Payload structure / composition guard for [JSInjector].
 *
 * The static payloads (canvas, WASM, eval, font, GPC) are string constants; the navigator override
 * is now a function of the persona [DeviceProfile] (issue #242), emitting FIXED, device-consistent
 * values instead of per-read random ones. This suite is a tripwire over the shape and composition of
 * the payloads and over the navigator override's device coherence; it does NOT execute the JS.
 */
class JSInjectorTest {

    /** The five individual payloads that [JSInjector.allScripts] is built from (device-less form). */
    private val individualScripts = listOf(
        JSInjector.CANVAS_NOISE_SCRIPT,
        JSInjector.navigatorOverrideScript(null),
        JSInjector.FONT_SPOOF_SCRIPT,
        JSInjector.WASM_WORKER_BLOCK_SCRIPT,
        JSInjector.EVAL_BLOCK_SCRIPT
    )

    // --- Composition of allScripts ----------------------------------------------------------

    @Test
    fun `allScripts contains every individual payload`() {
        val all = JSInjector.allScripts(null)
        for (script in individualScripts) {
            assertTrue(all.contains(script))
        }
    }

    @Test
    fun `allScripts joins payloads with a blank-line separator`() {
        assertTrue(JSInjector.allScripts(null).contains("})();\n\n(function()"))
    }

    @Test
    fun `allScripts orders payloads WASM EVAL CANVAS NAVIGATOR FONT`() {
        val all = JSInjector.allScripts(null)
        val wasm = all.indexOf(JSInjector.WASM_WORKER_BLOCK_SCRIPT)
        val eval = all.indexOf(JSInjector.EVAL_BLOCK_SCRIPT)
        val canvas = all.indexOf(JSInjector.CANVAS_NOISE_SCRIPT)
        val navigator = all.indexOf(JSInjector.navigatorOverrideScript(null))
        val font = all.indexOf(JSInjector.FONT_SPOOF_SCRIPT)

        assertTrue(wasm >= 0)
        assertTrue(eval >= 0)
        assertTrue(canvas >= 0)
        assertTrue(navigator >= 0)
        assertTrue(font >= 0)

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
        assertTrue(script.contains("imageData.data[i] += Math.floor(Math.random() * 3) - 1;"))
    }

    @Test
    fun `navigator payload redefines hardwareConcurrency and deviceMemory`() {
        val script = JSInjector.navigatorOverrideScript(null)
        assertTrue(script.contains("hardwareConcurrency"))
        assertTrue(script.contains("deviceMemory"))
        assertTrue(script.contains("Object.defineProperty"))
    }

    @Test
    fun `navigator payload emits the device's FIXED values and never per-read randomness`() {
        val device = DeviceProfile(
            formFactor = FormFactor.MOBILE, userAgent = "ua", platform = "Android",
            platformVersion = "14.0.0", model = "Pixel 6a", isMobile = true,
            brands = listOf(Brand("Chromium", "142")), screenWidth = 412, screenHeight = 915,
            devicePixelRatio = 2.625f, hardwareConcurrency = 6, deviceMemory = 8,
        )
        val script = JSInjector.navigatorOverrideScript(device)
        // The #242 regression: a real device never varies these per read.
        assertFalse("must not vary per read", script.contains("Math.random"))
        assertTrue("hardwareConcurrency must be the device's value", script.contains("hardwareConcurrency', { get: () => 6 }"))
        assertTrue("deviceMemory must be the device's value", script.contains("deviceMemory', { get: () => 8 }"))
    }

    @Test
    fun `navigator payload falls back to fixed defaults with no device`() {
        val script = JSInjector.navigatorOverrideScript(null)
        assertFalse(script.contains("Math.random"))
        assertTrue(script.contains("hardwareConcurrency', { get: () => ${JSInjector.DEFAULT_HARDWARE_CONCURRENCY} }"))
        assertTrue(script.contains("deviceMemory', { get: () => ${JSInjector.DEFAULT_DEVICE_MEMORY_GB} }"))
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
        assertTrue(script.contains("typeof fn === 'string'"))
    }

    @Test
    fun `FONT payload wraps OffscreenCanvas`() {
        assertTrue(JSInjector.FONT_SPOOF_SCRIPT.contains("OffscreenCanvas"))
    }

    // --- Interpolation canary ---------------------------------------------------------------

    @Test
    fun `no payload contains an unresolved Kotlin template marker`() {
        // The static payloads have no interpolation; the navigator override interpolates only bounded
        // Ints (which render as digits). A stray unresolved "${" must never survive into any payload.
        val marker = "\${"
        for (script in individualScripts) {
            assertFalse(script.contains(marker))
        }
        assertFalse(JSInjector.allScripts(null).contains(marker))
    }
}
