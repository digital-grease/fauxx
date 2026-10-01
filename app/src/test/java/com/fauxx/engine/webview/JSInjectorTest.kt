package com.fauxx.engine.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tripwires for [JSInjector].
 *
 * The injected bundle used to carry canvas noise, an OffscreenCanvas width jitter, fixed navigator
 * getters, and WebAssembly/Worker/eval blockers. FingerprintProbeInstrumentedTest measured every one
 * of them as a contradiction a page could read (see the [JSInjector] KDoc), so they were removed.
 * These tests fail if any of them comes back. They check the payload text; the on-device behaviour
 * is FingerprintProbeInstrumentedTest's job.
 */
class JSInjectorTest {

    private val script = JSInjector.PAGE_SCRIPT

    @Test
    fun `the page script sets the GPC signal`() {
        assertTrue(script.contains("globalPrivacyControl"))
        assertTrue(script.contains("value: true"))
    }

    @Test
    fun `GPC is a data property, so there is no getter whose source a page can read`() {
        assertFalse("GPC must not be an accessor", script.contains("get:"))
        assertFalse("GPC must not be an accessor", script.contains("get "))
    }

    @Test
    fun `none of the removed overrides has come back`() {
        for (marker in listOf(
            "WebAssembly", "Worker", "serviceWorker", "eval", "Function", "setTimeout", "setInterval",
            "getImageData", "getContext", "OffscreenCanvas", "Math.random",
            "hardwareConcurrency", "deviceMemory", "userAgentData", "userAgent",
        )) {
            assertFalse("the page script must not touch $marker", script.contains(marker))
        }
    }

    @Test
    fun `the payload contains no unresolved Kotlin template marker`() {
        assertFalse(script.contains("\${"))
    }
}
