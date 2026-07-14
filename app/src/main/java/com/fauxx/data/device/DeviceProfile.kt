package com.fauxx.data.device

/**
 * Device form factor. A persona owns one [MOBILE] identity (emitted by the phone over the System
 * WebView's Android-Chromium TLS) and one [DESKTOP] identity (emitted by the desktop companion over
 * its real desktop Chromium TLS). Splitting by form factor is what keeps each device's UA coherent
 * with the TLS stack it is actually presented over (see issue #168).
 */
enum class FormFactor { MOBILE, DESKTOP }

/** A Sec-CH-UA / `navigator.userAgentData` brand entry. */
data class Brand(val name: String, val version: String)

/**
 * A coherent, stable device identity derived for a persona (issue #242). This is a bundle, not just
 * a UA string: the UA, client-hint metadata, screen, and fixed navigator values are mutually
 * consistent and appropriate to [model], so a site cannot catch a contradiction between them.
 *
 * Derived deterministically from the persona by [DeviceDeriver]; identical bytes are produced on the
 * desktop companion from the same persona, so the pair presents one coherent person browsing from a
 * phone and a laptop without syncing the device over the wire.
 */
data class DeviceProfile(
    val formFactor: FormFactor,
    /** Fully materialized User-Agent (the Chrome major already substituted). */
    val userAgent: String,
    /** `navigator.userAgentData.platform`: "Android" | "Windows" | "macOS" | "Linux". */
    val platform: String,
    val platformVersion: String,
    /** Device model ("Pixel 8" for mobile); empty for desktop, which does not report a model. */
    val model: String,
    /** `navigator.userAgentData.mobile`. */
    val isMobile: Boolean,
    val brands: List<Brand>,
    val screenWidth: Int,
    val screenHeight: Int,
    val devicePixelRatio: Float,
    /** Fixed, device-appropriate `navigator.hardwareConcurrency` (a real device never varies it). */
    val hardwareConcurrency: Int,
    /** Fixed, device-appropriate `navigator.deviceMemory` in GB. */
    val deviceMemory: Int,
)
