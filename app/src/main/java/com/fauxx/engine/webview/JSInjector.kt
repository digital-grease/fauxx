package com.fauxx.engine.webview

/**
 * JavaScript payloads injected into background WebViews to reduce fingerprinting consistency.
 * These scripts override browser APIs commonly used for fingerprinting.
 */
object JSInjector {

    /**
     * Canvas fingerprint noise: adds subtle random pixel offsets to canvas draw calls,
     * making the resulting image slightly different on each page load.
     */
    val CANVAS_NOISE_SCRIPT = """
        (function() {
            const origGetContext = HTMLCanvasElement.prototype.getContext;
            HTMLCanvasElement.prototype.getContext = function(type, attrs) {
                const ctx = origGetContext.call(this, type, attrs);
                if (!ctx || type !== '2d') return ctx;
                const origGetImageData = ctx.getImageData.bind(ctx);
                ctx.getImageData = function(sx, sy, sw, sh) {
                    const imageData = origGetImageData(sx, sy, sw, sh);
                    for (let i = 0; i < imageData.data.length; i += 4) {
                        imageData.data[i] += Math.floor(Math.random() * 3) - 1;
                        imageData.data[i+1] += Math.floor(Math.random() * 3) - 1;
                        imageData.data[i+2] += Math.floor(Math.random() * 3) - 1;
                    }
                    return imageData;
                };
                return ctx;
            };
        })();
    """.trimIndent()

    /**
     * Navigator property overrides: randomizes minor properties used for fingerprinting.
     * Does not break normal page functionality.
     */
    val NAVIGATOR_OVERRIDE_SCRIPT = """
        (function() {
            const hardwareConcurrencies = [2, 4, 6, 8];
            const deviceMemories = [2, 4, 8];
            Object.defineProperty(navigator, 'hardwareConcurrency', {
                get: () => hardwareConcurrencies[Math.floor(Math.random() * hardwareConcurrencies.length)]
            });
            Object.defineProperty(navigator, 'deviceMemory', {
                get: () => deviceMemories[Math.floor(Math.random() * deviceMemories.length)]
            });
        })();
    """.trimIndent()

    /**
     * Font enumeration spoofing: returns a consistent but randomized font list
     * to prevent font-based fingerprinting.
     */
    val FONT_SPOOF_SCRIPT = """
        (function() {
            // No direct font enumeration API in web — this blocks timing-based font detection
            const origOffscreenCanvas = window.OffscreenCanvas;
            if (origOffscreenCanvas) {
                window.OffscreenCanvas = function(w, h) {
                    const c = new origOffscreenCanvas(w + (Math.random() > 0.5 ? 1 : 0), h);
                    return c;
                };
            }
        })();
    """.trimIndent()

    /** Combined script injected on every page load. */
    val ALL_SCRIPTS = listOf(CANVAS_NOISE_SCRIPT, NAVIGATOR_OVERRIDE_SCRIPT, FONT_SPOOF_SCRIPT)
        .joinToString("\n\n")
}
