package com.fauxx.engine.webview

import com.fauxx.data.device.DeviceProfile

/** One `Sec-CH-UA` brand entry, independent of the androidx type so the logic stays JVM-testable. */
data class BrandEntry(val brand: String, val majorVersion: String, val fullVersion: String)

/**
 * Everything a page can read about the browser's identity: the User-Agent and the client-hint
 * metadata behind `Sec-CH-UA*` and `navigator.userAgentData`. The two MUST agree, and both must
 * match the engine that actually speaks TLS.
 */
data class ChromeIdentity(
    val userAgent: String,
    val brands: List<BrandEntry>,
    val fullVersion: String,
    val platformVersion: String,
    val model: String,
)

/**
 * Builds the identity the phantom pool presents: Chrome for Android, at the installed WebView's
 * version, on the persona's handset.
 *
 * Measured on WebView 151 (FingerprintProbeInstrumentedTest) before this existed: with only the UA
 * overridden, every request still carried `Sec-CH-UA: "Android WebView";v="151"`, the high-entropy
 * hints went out EMPTY (`Sec-CH-UA-Model: ""`, an empty full-version list), and the UA itself was a
 * pre-reduction `Android 13; Pixel 7` form that real Chrome stopped sending at version 110.
 *
 * The fix derives everything from the WebView's own defaults rather than from a catalog:
 * - **Brands**: Chromium generates the GREASE entry and the brand order from the major version, so
 *   taking the WebView's list and renaming its "Android WebView" entry to "Google Chrome" yields
 *   exactly the list Chrome of the same version sends.
 * - **Version**: the installed WebView's, because that is the engine every feature-detection probe
 *   and the TLS handshake actually report. A calendar-derived major could claim a Chrome the device
 *   cannot be (spike s3, gap 9).
 * - **UA**: the reduced form (`Android 10; K`, `.0.0.0`), which is what Chrome for Android sends.
 *   The handset model is not in it; like real Chrome, it travels only in the `Sec-CH-UA-Model`
 *   high-entropy hint.
 */
object BrowserIdentity {

    const val WEBVIEW_BRAND = "Android WebView"
    const val CHROME_BRAND = "Google Chrome"

    /**
     * Handset used when no persona device is bound (Layer 3 off). Mirrors the first mobile template
     * in `device_templates.json`, so the no-persona path presents a catalog device rather than the
     * user's real handset model.
     */
    const val DEFAULT_MODEL = "Pixel 8"
    const val DEFAULT_PLATFORM_VERSION = "14.0.0"

    /** Chrome for Android's reduced User-Agent for [major]. */
    fun reducedMobileUserAgent(major: Int): String =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$major.0.0.0 Mobile Safari/537.36"

    /**
     * The WebView's brand list with its product brand renamed to Chrome's, order and GREASE entry
     * untouched. Null when the list carries no WebView brand, because then there is nothing known
     * to rename and guessing a GREASE value would be worse than leaving the defaults alone.
     */
    fun chromeBrands(webViewBrands: List<BrandEntry>): List<BrandEntry>? {
        if (webViewBrands.none { it.brand == WEBVIEW_BRAND }) return null
        return webViewBrands.map { if (it.brand == WEBVIEW_BRAND) it.copy(brand = CHROME_BRAND) else it }
    }

    /**
     * The identity to present for [device] (or the default handset when null), or null when the
     * WebView's own metadata is unusable. Null means: do not override anything, because a Chrome UA
     * over client hints that still say "Android WebView" is a contradiction, while the untouched
     * WebView identity is at least a real browser's.
     */
    fun forDevice(device: DeviceProfile?, webViewBrands: List<BrandEntry>, webViewFullVersion: String): ChromeIdentity? {
        val brands = chromeBrands(webViewBrands) ?: return null
        val major = webViewFullVersion.substringBefore('.').toIntOrNull() ?: return null
        return ChromeIdentity(
            userAgent = reducedMobileUserAgent(major),
            brands = brands,
            fullVersion = webViewFullVersion,
            platformVersion = device?.platformVersion ?: DEFAULT_PLATFORM_VERSION,
            model = device?.model ?: DEFAULT_MODEL,
        )
    }
}
