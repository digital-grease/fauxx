package com.fauxx.data.model

/**
 * Runtime configuration for the Fauxx poison engine. Persisted via Jetpack DataStore.
 *
 * @property enabled Whether the engine is actively running.
 * @property intensity Action rate setting.
 * @property wifiOnly Only execute actions when connected to Wi-Fi.
 * @property batteryThreshold Pause when battery level drops below this percentage (0-100).
 * @property allowedHoursStart Hour of day (0-23) when activity is permitted to start.
 * @property allowedHoursEnd Hour of day (0-23) when activity must stop.
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
 */
data class PoisonProfile(
    val enabled: Boolean = false,
    val intensity: IntensityLevel = IntensityLevel.MEDIUM,
    val wifiOnly: Boolean = true,
    val batteryThreshold: Int = 20,
    val allowedHoursStart: Int = 7,
    val allowedHoursEnd: Int = 23,
    val searchPoisonEnabled: Boolean = true,
    val adPollutionEnabled: Boolean = true,
    val locationSpoofEnabled: Boolean = false,
    val fingerprintEnabled: Boolean = true,
    val cookieSaturationEnabled: Boolean = true,
    val appSignalEnabled: Boolean = false,
    val dnsNoiseEnabled: Boolean = true,
    val layer1Enabled: Boolean = false,
    val layer2Enabled: Boolean = false,
    val layer3Enabled: Boolean = true
)
