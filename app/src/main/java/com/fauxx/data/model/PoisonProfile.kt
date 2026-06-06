package com.fauxx.data.model

import com.fauxx.ui.theme.ThemeMode

/**
 * Runtime configuration for the Fauxx poison engine. Persisted via Jetpack DataStore.
 *
 * @property enabled Whether the engine is actively running.
 * @property intensity Action rate setting.
 * @property wifiOnly Only execute actions when connected to Wi-Fi.
 * @property batteryThreshold Pause when battery level drops below this percentage (0-100).
 * @property ignoreBatteryThresholdWhileCharging When true, [batteryThreshold] is bypassed while
 *   the device is plugged in — the engine keeps running even at low charge as long as it's
 *   actively charging. Defaults to false to preserve historical behavior.
 * @property allowedHoursStart Hour of day (0-23) when activity is permitted to start.
 * @property allowedHoursEnd Hour of day (0-23) when activity must stop.
 * @property logRetentionDays Days of action-log history to keep; the retention worker prunes
 *   entries older than this (1-90, default 7). Keeps the on-device log bounded.
 * @property searchPoisonEnabled Whether the SearchPoisonModule is active.
 * @property adPollutionEnabled Whether the AdPollutionModule is active.
 * @property locationSpoofEnabled Whether the LocationSpoofModule is active.
 * @property fingerprintEnabled Whether the FingerprintModule is active.
 * @property cookieSaturationEnabled Whether the CookieSaturationModule is active.
 * @property appSignalEnabled Whether the AppSignalModule is active.
 * @property dnsNoiseEnabled Whether the DnsNoiseModule is active.
 * @property layer1Enabled Whether Layer 1 (self-report targeting) is active.
 * @property layer2Enabled Whether Layer 2 (adversarial scraper) is active.
 * @property layer3Enabled Whether Layer 3 (persona rotation) is active.
 * @property themeMode UI theme preference (system / light / dark).
 * @property resumeOnBoot When true, show a "tap to resume" notification after device
 *   reboot if the engine was enabled pre-reboot. True FGS auto-start is blocked by
 *   Android 14+ for our FGS types.
 * @property customUserAgent When non-null/non-blank, used as the User-Agent for ALL
 *   synthetic traffic (OkHttp + WebView) instead of randomizing across the
 *   user_agents.json pool. Lets users match the synthetic-traffic UA to their
 *   real browser so the noise blends with their actual activity (issue #7).
 *   Null/blank = default per-request UA rotation.
 */
data class PoisonProfile(
    val enabled: Boolean = false,
    val intensity: IntensityLevel = IntensityLevel.MEDIUM,
    val wifiOnly: Boolean = true,
    val batteryThreshold: Int = 20,
    val ignoreBatteryThresholdWhileCharging: Boolean = false,
    val allowedHoursStart: Int = 7,
    val allowedHoursEnd: Int = 23,
    val logRetentionDays: Int = 7,
    val searchPoisonEnabled: Boolean = true,
    val adPollutionEnabled: Boolean = true,
    val locationSpoofEnabled: Boolean = false,
    val fingerprintEnabled: Boolean = true,
    val cookieSaturationEnabled: Boolean = true,
    val appSignalEnabled: Boolean = false,
    val dnsNoiseEnabled: Boolean = true,
    val layer1Enabled: Boolean = false,
    val layer2Enabled: Boolean = false,
    val layer3Enabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val resumeOnBoot: Boolean = true,
    val customUserAgent: String? = null
)
