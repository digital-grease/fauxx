package com.fauxx.engine.webview

/**
 * The JavaScript injected into phantom WebView pages.
 *
 * This used to carry a bundle of anti-fingerprinting overrides: per-read canvas noise, an
 * OffscreenCanvas width jitter, fixed `hardwareConcurrency`/`deviceMemory` getters, and WebAssembly,
 * Worker, ServiceWorker and `eval` blockers. Measured on WebView 151 (FingerprintProbeInstrumentedTest),
 * every one of them was a tell rather than a defence:
 * - `'WebAssembly' in window` was true while `typeof WebAssembly` was `undefined`, and `eval('1+1')`
 *   returned `undefined`: states no Chrome can be in.
 * - Two `getImageData` reads of one canvas disagreed in 1,500+ bytes, and `new OffscreenCanvas(300, 150)`
 *   was 301 wide about half the time.
 * - The injected `deviceMemory` (8) contradicted the `Device-Memory` request header (the real 2),
 *   which a WebView cannot rewrite on subresources.
 * - None of it reached iframes or workers, so a same-origin iframe reported the real
 *   `hardwareConcurrency` and a native WebAssembly next to the main frame's overridden values.
 * - The getters were own properties of `navigator` whose source was readable (`() => 8`).
 *
 * The page now sees the real engine, which is at least a device that exists. Spike s3
 * (`.devloop/spikes/s3-persona-device-coherence.md`) covers why none of these surfaces can be
 * impersonated from an unprivileged WebView. Persona identity travels through the User-Agent and
 * client hints instead (see [BrowserIdentity]), which the engine itself emits consistently.
 */
object JSInjector {

    /**
     * Global Privacy Control DOM signal, the in-page counterpart to the `Sec-GPC: 1` header in
     * [SYNTHETIC_WEBVIEW_HEADERS]. Chrome does not implement GPC, so this is shaped the way a
     * page-world GPC extension sets it: a plain data property on the `navigator` instance, with no
     * getter whose source a page could read. Fixed value, never randomized.
     */
    val PAGE_SCRIPT = """
        (function() {
            try {
                Object.defineProperty(navigator, 'globalPrivacyControl', {
                    value: true,
                    enumerable: true,
                    configurable: true
                });
            } catch (e) {}
        })();
    """.trimIndent()
}
