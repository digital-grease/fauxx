package com.fauxx.data.model

import com.fauxx.ui.theme.ThemeMode

/**
 * Runtime configuration for the Fauxx poison engine. Persisted via Jetpack DataStore.
 *
 * @property enabled Whether the engine is actively running.
 * @property intensity Action rate while on Wi-Fi (or other unmetered transports like ethernet).
 * @property mobileIntensity Action rate while on mobile data, or null to pause on mobile
 *   (the legacy "Wi-Fi only" behavior, and still the default). Issue #62: lets users run
 *   e.g. HIGH on Wi-Fi but LOW on mobile instead of the old all-or-nothing toggle. The
 *   legacy `wifi_only` preference key migrates lazily: true → null, false → mirror
 *   [intensity] (see PoisonProfileRepository.prefsToProfile).
 * @property batteryThresholdBattery Pause when battery level drops below this percentage while NOT charging (0-100).
 * @property batteryThresholdCharging Pause when battery level drops below this percentage while charging (0-100).
 * @property pauseOnBatterySaver Pause while Android's Battery Saver is on (issue #313), resuming as
 *   soon as it turns off. Off by default.
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
 * @property adversarialAllocationEnabled Whether the adversarial allocation stage (E4 #180) is
 *   active. A post-combine optimization step, off by default; only has an effect when Layer 1
 *   and/or Layer 2 are enabled (they supply the protected-interest signal it optimizes against).
 * @property themeMode UI theme preference (system / light / dark).
 * @property resumeOnBoot When true, show a "tap to resume" notification after device
 *   reboot if the engine was enabled pre-reboot. True FGS auto-start is blocked by
 *   Android 14+ for our FGS types.
 * @property loadImages When true, synthetic page loads fetch images like a real browser, so
 *   `<img>` tracking pixels fire and a page view looks normal to the server. Off by default
 *   because images are most of a page's bytes, which matters on metered connections.
 * @property dnsMode Resolver for Fauxx's own synthetic traffic (#227). Device-local, never synced.
 * @property dohProvider Preset id from `DohPresets` (default Quad9, matching the desktop
 *   companion), or `DohPresets.CUSTOM_ID` to use [dohCustomUrl].
 * @property dohCustomUrl The user's own https DoH endpoint, e.g. a NextDNS profile URL. That URL
 *   identifies the user, so it is scrubbed from exported logs.
 * @property plainDnsServer The plain DNS server for [DnsMode.PLAIN], as `ip` or `ip:port`
 *   (IPv6 bracketed when a port is given). By IP only, never by name.
 * @property preferredCustomDnsMode The custom mode (DOH or PLAIN) the user last chose, so switching
 *   custom DNS off and on again restores it instead of silently landing on DoH.
 * @property routeDnsNoise Whether the DNS-noise module's lookups also go to the custom resolver.
 *   Off by default: DNS noise exists to fill the device's resolver logs (the ISP's or the
 *   network's), and on a custom resolver it fills only that resolver's.
 * @property excludedSearchEngines Names of search engines the user has opted OUT of poisoning
 *   (issue #281), e.g. a privacy-respecting engine they would rather not send synthetic traffic
 *   to. Stored as exclusions rather than inclusions so the default is empty and any engine added
 *   to the pool later is on by default without a preference migration. The UI keeps at least
 *   [MIN_ACTIVE_SEARCH_ENGINES] engines active: a single-engine noise stream is itself a
 *   fingerprint, and engine diversity is the reason the pool was widened in the first place
 *   (issue #24).
 */
data class PoisonProfile(
    val enabled: Boolean = false,
    val intensity: IntensityLevel = IntensityLevel.MEDIUM,
    val mobileIntensity: IntensityLevel? = null,
    val batteryThresholdBattery: Int = 20,
    val batteryThresholdCharging: Int = 20,
    val pauseOnBatterySaver: Boolean = false,
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
    val adversarialAllocationEnabled: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val resumeOnBoot: Boolean = true,
    val loadImages: Boolean = false,
    val dnsMode: DnsMode = DnsMode.SYSTEM,
    val dohProvider: String = "quad9",
    val dohCustomUrl: String = "",
    val plainDnsServer: String = "",
    val routeDnsNoise: Boolean = false,
    val preferredCustomDnsMode: DnsMode = DnsMode.DOH,
    val excludedSearchEngines: Set<String> = emptySet()
)

/**
 * Floor on how many search engines can stay active (issue #281). Real users spread their
 * searches across engines; collapsing synthetic traffic onto one SERP would make it
 * trivially separable, which is exactly what the poisoning is trying to avoid.
 */
const val MIN_ACTIVE_SEARCH_ENGINES = 2
