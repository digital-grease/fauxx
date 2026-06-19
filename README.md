# Fauxx

[![CI](https://img.shields.io/github/actions/workflow/status/digital-grease/fauxx/ci.yml?branch=main&logo=github&label=CI)](https://github.com/digital-grease/fauxx/actions/workflows/ci.yml)
[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](https://github.com/digital-grease/fauxx/blob/main/LICENSE)
[![Android API](https://img.shields.io/badge/API-26%2B-brightgreen?logo=android)](https://developer.android.com/about/versions/oreo)
[![GitHub Issues](https://img.shields.io/github/issues/digital-grease/fauxx)](https://github.com/digital-grease/fauxx/issues)

<a href="https://www.buymeacoffee.com/digitalgrease" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-red.png" alt="Buy Me A Coffee" style="height: 60px !important;width: 217px !important;" ></a>

**Data poisoning for your everyday tracking.**

Fauxx is an open-source Android privacy tool that poisons data broker and ad-tech profiles by generating continuous, plausible, off-demographic synthetic activity from your device. The goal is simple: make your real behavioral signal statistically indistinguishable from noise.

> 💬 **Questions about how Fauxx works, or wishlist ideas?** Use [Discussions](https://github.com/digital-grease/fauxx/discussions). Bug reports and feature requests stay in [Issues](https://github.com/digital-grease/fauxx/issues).

> 📦 **Where to get it:** Fauxx ships via **F-Droid**, **GitHub Releases**, and sideload / Obtainium. **The Google Play build is no longer maintained or published.** Play is too restrictive for Fauxx's full feature set (it disallows the location-spoofing and ad-profile-pollution modules), and it is moving toward requiring the age-verification API, which a no-account privacy tool has no business integrating. The `play` build flavor stays in the source in case that ever changes (CI still debug-compiles and unit-tests it so it doesn't rot unnoticed), but it carries no quality gates and is no longer built, shipped, or allowed to block a release.

> 🖥️ **Desktop companion (alpha):** [fauxx-desktop](https://github.com/digital-grease/fauxx-desktop) is a new, early-alpha cross-device privacy command center (a Rust headless core with an Iced GUI). It runs decoy generation on a desktop or homelab box and shares an encrypted, local-only LAN persona-sync protocol with the Android app, so paired devices can keep one coherent persona. It is alpha software, so expect rough edges while it stabilizes.

## The Problem

Every search you make, every link you click, every location you visit is collected by data brokers, ad networks, and analytics platforms. Over time, they build a detailed profile of who you are, what you want, and what you're likely to do next. That profile is sold, traded, and collated with other data and profiles to continue the process.

Fauxx addresses this by injecting continuous, category-weighted synthetic activity that obscures your real interests under a statistical cloud. Your genuine signal becomes noise.

## How It Works

Fauxx uses a **Demographic Distancing Engine**—a layered system that determines what noise to generate:

### Layer 0: Uniform Entropy (Always Active)

The baseline: equal probability across all content categories. This is your foundation.

### Layer 1: Self-Report (Optional)

You optionally tell Fauxx coarse demographics (age range, interests, profession, region). Fauxx then weights AWAY from these categories—generating noise in the things you don't care about, maximizing confusion.

- Skip it? You keep Layer 0 uniform noise.
- Enable it? Your real profile becomes harder to infer.

### Layer 2: Ad-Profile Import (Opt-in, Advanced)

Fauxx imports the ad-interest profile the platforms have already built about you, from a data export you provide: Google Takeout ("My Ad Center") or Facebook's "Download Your Information" (JSON). It reads exactly what the ad networks think they know about you, then aggressively suppresses those confirmed categories so the synthetic activity floods everything else instead.

- You provide an exported file. Fauxx never logs in, reads cookies, or touches the platforms.
- Reads the file only. Nothing is sent anywhere.
- Re-import occasionally as your profile drifts (Fauxx reminds you after about 90 days).
- On a missing or unrecognized file, degrades gracefully to Layer 0.

### Layer 3: Synthetic Persona Rotation (Active When L1 or L2 Enabled)

To prevent pattern detection, Fauxx generates a coherent synthetic persona every 7 days (with random jitter). This persona—a fake age, profession, interests, region—becomes your "noise profile" for that week. It changes on a schedule, adding temporal coherence and making you harder to fingerprint.

### How Weights Combine

All layers produce a weight map across content categories. These weights multiply together and normalize, so the final distribution sums to 1.0. Categories are clamped with a minimum weight of 0.001—absence is still a signal.

Example: If you report yourself as a 25-year-old software engineer, Layer 1 drops RETIREMENT and PARENTING to 0.15× (away-from) and boosts GAMING and TECHNOLOGY to 2.5× (toward other interests). When Layer 2 imports your ad profile and sees Google has tagged you with TECH, it further suppresses TECH (0.05×) and boosts categories Google has never associated with you (3.0×). These multiply together, then Layer 3 blends in the weekly persona's preferences. The result: a noise distribution that looks nothing like your real profile.

## Modules

Fauxx poisons through seven complementary channels:

### 1. Search Poisoning

Executes synthetic search queries across Google, Bing, DuckDuckGo, and Yahoo. Queries are generated using a Markov-chain model trained on a bundled corpus—they're natural-sounding and topically coherent, not random gibberish. Each query is followed by 1–3 result clicks with random dwell time (2–30 seconds).

**Category-aware:** Query bank selection is weighted by your targeting engine output.

### 2. Ad Pollution

Loads ad-heavy pages in background WebViews, clicks ads at sub-1% CTR (keeping it plausible), and visits ad preference dashboards. Designed to generate signals ad networks interpret as low intent, not botting.

### 3. Location Spoofing

Uses Android's MockLocationProvider to feed fake GPS coordinates along plausible paths:
- Walking routes (3–5 km/h)
- Driving routes (30–100 km/h)
- Stationary jitter around fake "home" locations

Powered by a database of 800+ world city centers. Location selection is weighted by your demographics—if you report yourself as US Midwest, spoofing favors distant regions.

**Setup:** Android requires you to designate the mock-location app explicitly. Enable Developer Options (Settings → About phone → tap Build Number 7 times), then Developer Options → "Select mock location app" → Fauxx. The Location Spoofing toggle surfaces this dialog on first enable. The Play Store build does not include this module; F-Droid / sideload only.

### 4. Fingerprint Rotation

Continuously rotates User-Agent strings (from a pool of 275+ real-world UA strings), injects canvas fingerprint noise via JavaScript, randomizes Accept-Language and Accept-Encoding headers, and periodically resets the Android Advertising ID. This layer disrupts browser fingerprinting and device-level profiling.

### 5. Cookie Saturation

Visits 2,400+ categorized URLs in isolated background WebViews, accumulating tracker cookies across diverse categories. Each URL load respects a per-domain rate limit (minimum 5 seconds between hits). WebViews are pooled and process-isolated to avoid contaminating your real browser cookies.

**Category-aware:** URL selection weighted by your targeting engine.

### 6. App Signal Noise

Opens deep links and app store pages for off-profile applications, triggering attribution pixel fires that make ad networks think you're interested in categories you've never touched.

### 7. DNS Noise

Resolves diverse domain names, generating DNS query noise visible to ISP and network-level trackers. Adds another layer of confusion at the network level.

## Privacy Guarantees

Fauxx is built with privacy-first architecture:

- **On-device only:** All demographic data, profile settings, and activity logs stay on your device. Nothing is uploaded.
- **Encrypted database:** Sensitive tables (UserDemographicProfile, PlatformProfileCache) use SQLCipher encryption with AndroidKeyStore-backed keys. The encryption key is derived from the device's secure key material.
- **Import reads only your file:** Layer 2 reads the ad-profile export you hand it. It never logs into, modifies, or contacts the ad platforms.
- **No sensitive attributes:** Demographic distance rules never include or infer race, ethnicity, religion, sexual orientation, gender identity, disability, or political affiliation.
- **Domain blocklist:** Every URL is checked against a hardcoded blocklist of illegal/harmful domains before loading.
- **Rate limiting:** Maximum 1 request per 5 seconds per domain. Maximum 200 requests per hour at HIGH intensity. Enforced per-domain to avoid abuse.
- **No fingerprinting of users:** The app does not track or identify individual users. It only tracks its own action log locally.

## Tech Stack

- **Language:** Kotlin (Android API 26+ minimum, API 36 target)
- **UI:** Jetpack Compose + Material 3 (dark-first theme)
- **Database:** Room + SQLCipher (encrypted)
- **Networking:** OkHttp 4.x with custom interceptors
- **Security:** AndroidX Security (EncryptedSharedPreferences, AndroidKeyStore)
- **Background:** WorkManager for scheduling
- **DI:** Hilt
- **Services:** Android ForegroundService for persistent background execution

## Installation

### Prerequisites

- Android SDK API 36 (build tools)
- Kotlin compiler (bundled with Gradle)
- JDK 21 (LTS — matches F-Droid buildserver)

### Build from Source

```bash
# Clone the repository
git clone https://github.com/digital-grease/fauxx.git
cd fauxx

# Build debug APK
./gradlew assembleDebug

# Build release APK (requires keystore)
./gradlew assembleRelease

# Run tests
./gradlew test

# Run instrumented tests
./gradlew connectedAndroidTest
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### Installation

```bash
# Install via adb
adb install app/build/outputs/apk/debug/app-debug.apk

# Or sideload via Settings > Unknown Sources
```

## Usage

### First Launch: Onboarding (Optional)

On first launch, Fauxx offers an optional demographic self-report flow:
- Age range
- Gender
- Interests (multi-select chips)
- Profession
- Region

Every screen has a visible "Skip" button. You can skip all of it and run on pure Layer 0 uniform noise.

### Dashboard

View at a glance:
- Protection status (on/off toggle)
- Actions executed today/this week (animated counter)
- Per-module activity sparklines
- Current synthetic persona (name, age, interests)
- Category distribution donut chart showing how noise is spread

### Targeting

Fine-tune the Demographic Distancing Engine:
- **Layer 1 Toggle:** Enable/disable self-report weighting. Edit your profile or clear it entirely.
- **Layer 2 Toggle:** Enable/disable ad-profile import. Import a Google Takeout / Facebook export and see when you last imported.
- **Layer 3 Toggle:** Enable/disable persona rotation. See the current persona and rotate manually.
- **Weight Visualization:** Live horizontal bar chart showing weight per category. Red = suppressed, green = boosted, gray = neutral.

### Modules

Toggle each poison module independently:
- Search Poisoning (choose search engines)
- Ad Pollution
- Location Spoofing (location spoofing mode)
- Fingerprint Rotation
- Cookie Saturation (URL categories)
- App Signal Noise
- DNS Noise

### Log

Scrollable audit log of all actions with timestamps, types, and details. Export to CSV or JSON.

### Settings

Global controls:
- **Wi-Fi intensity:** Low (light noise) / Medium (balanced) / High (aggressive) / Max (highest volume)
- **Mobile data intensity:** a separate Off / Low / Medium / High / Max ladder for mobile data — Off (the default) never touches mobile data; any tier runs the engine on mobile at its own rate
- **Battery threshold:** Minimum battery % to run actions
- **Active hours:** Time range when actions should run (e.g., 7am–11pm)
- **Clear all data:** Destructive button to reset everything

## Configuration

All configurable values are exposed in the app UI and backed by Room preferences or compile-time constants. Key thresholds:

- **Per-domain rate limit:** 5 seconds (CrawlListManager)
- **Action timing:** Poisson-distributed with human-like bursts (3–7 actions, then 5–20 min gaps)
- **Cross-niche dwell:** Lognormal dwell-time multiplier on category transitions (e.g., Finance → Legal) with a 30s floor — defeats heuristic bot detection that flags sub-second niche switches
- **Circadian pattern:** Near-zero activity 11pm–7am local time
- **Layer 3 rotation:** Every 7 days ± [1, 3] days jitter
- **Layer 2 re-import reminder:** about 90 days (the import is manual, not scheduled)

## Project Structure

```
app/src/main/
├── java/com/fauxx/
│   ├── FauxxApp.kt                      # Application entry point
│   ├── di/                              # Hilt dependency injection
│   ├── data/
│   │   ├── db/                          # Room database, DAOs, entities
│   │   ├── model/                       # Data models (ActionType, CategoryPool, etc.)
│   │   ├── querybank/                   # Query bank manager & Markov generator
│   │   ├── crawllist/                   # URL corpus manager & blocklist
│   │   └── location/                    # Fake route generator & city database
│   ├── targeting/                       # Demographic Distancing Engine
│   │   ├── TargetingEngine.kt           # Orchestrator
│   │   ├── layer0/                      # Uniform entropy
│   │   ├── layer1/                      # Self-report weighting
│   │   ├── layer2/                      # Ad-profile import
│   │   ├── layer3/                      # Persona rotation
│   │   └── WeightNormalizer.kt
│   ├── engine/
│   │   ├── PoisonEngine.kt              # Core orchestrator
│   │   ├── modules/                     # Seven poison modules
│   │   ├── webview/                     # WebView pool & customization
│   │   └── scheduling/                  # Poisson scheduler & dispatcher
│   ├── service/
│   │   ├── PhantomForegroundService.kt
│   │   └── BootReceiver.kt
│   ├── network/
│   │   ├── HeaderRandomizerInterceptor.kt
│   │   └── UserAgentPool.kt
│   └── ui/
│       ├── MainActivity.kt
│       ├── navigation/
│       ├── screens/                     # Dashboard, Targeting, Modules, Log, Settings, Onboarding
│       └── theme/
└── assets/
    ├── query_banks/                     # Query bank JSON per category
    ├── crawl_urls.json                  # 2,400+ categorized URLs
    ├── user_agents.json                 # 275+ real User-Agent strings
    ├── city_coords.json                 # 800+ city coordinates
    ├── blocklist.json                   # Blocked domains
    ├── demographic_distance_rules.json  # Category weight rules by demographic
    ├── platform_category_map.json       # Platform string to CategoryPool mapping
    └── persona_templates.json           # Persona archetypes
```

## Development

### Code Style

- Clean, well-documented Kotlin with KDoc on all public classes/functions
- Composition over inheritance
- Kotlin Flow for reactive updates
- Room + Hilt for DI and persistence

### Testing

The test suite covers:

- **Unit tests:** TargetingEngine weight math, layer logic, normalizer, scheduler, Markov generator, route constraints
- **Integration tests:** Rate limiting, action dispatcher sampling, full targeting engine with multiple layers
- **UI tests:** Onboarding flows, targeting screen toggles, dashboard updates

Run tests:

```bash
./gradlew test              # Unit + local integration tests
./gradlew connectedAndroidTest  # Instrumented tests on a device/emulator
```

### Contributing

Contributions are welcome. Please ensure:
- Existing tests pass
- New public functions have KDoc
- Code follows Kotlin conventions
- Sensitive attributes are never added to DemographicDistanceMap

## Localization

Fauxx ships with English (`en`) UI and synthetic-activity content. Spanish (`es`) and French (`fr`) infrastructure is in place; their content (UI strings, query banks, harmful_queries blocklist, persona templates, crawl URLs, search-engine URL params, Accept-Language headers) is locale-aware end-to-end. Selecting a non-English locale flips all of those layers together — the synthetic activity tracks the UI language so a Spanish-mode profile emits `Accept-Language: es-ES` with `&hl=es&gl=ES` URL params, not a mismatched `en-US` that would itself be a fingerprintable signal.

**Locale gate.** Production builds restrict the Settings language picker via `BuildConfig.SHIPPED_LOCALES`. A locale only joins the allowlist after its `assets/harmful_queries/<localeTag>.json` has signed-off native-speaker review. The blocklist contains the region's crisis-line, domestic-violence, and poison-control numbers; an English fallback would not catch the Spanish/French equivalents and would risk dispatching a synthetic query that data brokers interpret as a real first-person distress signal. Debug builds preview all three locales (`en`, `es`, `fr`) for development testing.

**Adding a new locale.** Outline of the work, in order:
1. Append the locale to the `SupportedLocale` enum (with `tag`, `displayName`, `defaultRegion`, `yahooSubdomainPrefix`).
2. Translate `app/src/main/res/values/strings.xml` → `values-<localeTag>/`. Same for the play flavor under `app/src/play/res/values/`.
3. Draft `assets/harmful_queries/<localeTag>.json`. Class A illegal terms can start as a translation of the English file; the self-signal section must include the region's crisis hotlines (e.g. ES 024, FR 3114, DE 0800-181-0721, etc.) and DV / poison-control numbers. **Native-speaker review is mandatory before this locale ships.** Add a sentinel-presence assertion in `HarmfulQueriesLocaleAuditTest` to lock the regression in.
4. Curate `assets/persona_templates/<localeTag>.json` (region- and culture-plausible archetypes) and `assets/crawl_urls/<localeTag>.json` (region-appropriate domains across all CategoryPool values).
5. Add ES/FR/etc. entries to `CATEGORY_APP_KEYWORDS` in `AppSignalModule` (Play Store keywords idiomatic to that storefront).
6. Add a row to the `LANGUAGE_VARIANTS` map in `HeaderRandomizerInterceptor` (4–5 plausible primary/secondary Accept-Language strings).
7. Run `ANTHROPIC_API_KEY=... python3 scripts/translate_query_banks.py <localeTag>` to populate the per-locale query banks (32 categories). Spot-check a few categories for idiomatic phrasing.
8. After native-speaker review, bump `BuildConfig.SHIPPED_LOCALES` in `defaultConfig` to include the new tag.

See `.devloop/spikes/multilingual-support.md` for the design and threat model.

## FAQ

### What does "Noise Ratio" on the Dashboard mean?

It's a throughput indicator: `min(actions today / 500, 100%)`. 500 actions in a day reads as a "saturated" Noise Ratio of 100%.

What it *doesn't* measure: the *quality* of the noise — whether the synthetic activity is hitting categories that are actually different from your real interests, whether it's fooling profiling systems, or how diverse the topics are. It's just a rate gauge.

If you want to see where the noise is actually going, the **Targeting screen's category-weight chart** is more useful: red bars are categories Fauxx is suppressing (because they match your demographic profile), green bars are categories it's boosting (off-profile noise), gray is neutral.

A future release will move Noise Ratio toward a quality-aware metric rather than pure throughput. Tracked as a planned improvement.

### How does Layer 2 (Ad-Profile Import) work, and what do I import?

Layer 2 reads the ad-interest profile the platforms have already built about you, from a data export you download yourself, and tells the engine to *suppress* those categories so the synthetic activity steers away from your real profile.

To use it:

- **Export your ad data.** From Google, use [Google Takeout](https://takeout.google.com) and select **"My Ad Center"**. From Facebook, use **Download Your Information** (accountscenter.facebook.com), choose **JSON** format, and include **"Ads and businesses"**. The platform emails you an archive (a ZIP or JSON file).
- **Import the file.** On Fauxx's Targeting screen, tap the Google Takeout or Facebook import button and pick the file you downloaded. Fauxx reads the ad-interest categories and applies the away-from weighting.
- **Nothing leaves your device.** Fauxx never logs in, reads cookies, or contacts the platforms. It only parses the file you give it, on-device.
- **Re-import occasionally.** Your ad profile drifts over time, so Fauxx reminds you to re-import after about 90 days. There is no background scraping.

If the import says it couldn't find an ad-interest file, the most common cause is that personalized ads are turned **off** in your Google or Facebook settings, so the platform keeps no ad profile to export. Turn personalized ads on, let a profile accrue, then export again.

### Why does Fauxx sometimes stop and show a "Tap to resume protection" notification?

When Fauxx pauses for a long stretch, it releases its foreground service rather than sit idle holding the slot, and posts a one-tap notification so you can resume when you next pick up your phone.

You'll typically see this notification:

- **In the morning,** if you have quiet hours configured (default 7am to 11pm). Rather than spin idle overnight, Fauxx steps down at the start of quiet hours and reappears as a tap-to-resume at the start of your next active window.
- **After a long no-network pause,** if your mobile data intensity is Off and Wi-Fi is gone, or after a long low-battery pause. Sustained pauses past 30 minutes release the service rather than spinning idle.
- **After a reboot or an app update,** because Android won't let Fauxx restart its own foreground service from a boot or update event. Fauxx posts the resume notification instead.

Tapping the notification opens Fauxx and restarts protection. Nothing is lost. Your settings, profile, persona, and action log are all persistent. This behavior is identical on the Play Store and F-Droid builds.

## License

Fauxx is licensed under the GNU Affero General Public License v3 (AGPLv3). See [LICENSE](LICENSE) for details.

By using, modifying, or distributing this software, you agree to the terms of the AGPLv3. In particular, if you run a modified version as a service, you must make the source code available to your users.

## Disclaimer

Fauxx is a privacy research tool. It generates synthetic activity to poison ad profiling, but it is not a complete privacy solution. Use it as part of a broader privacy strategy:

- Use a VPN to hide your IP
- Use Tor for maximum anonymity
- Disable location services when not needed
- Review app permissions regularly
- Use a privacy-focused browser

Fauxx operates within the terms of service of search engines and ad platforms, but continued use of these platforms may still subject you to profiling through other channels (cookies, account-level data, cross-site tracking). No tool is perfect.

## Threat Model

- **Adversaries:** Data brokers, ad networks, analytics platforms, ISPs.
- **Data in scope:** The weak, low-confidence signals these adversaries infer from search queries, URL visits, location history, device fingerprints, app-store interest signals, and DNS queries.
- **Goal:** Dilution at the edge of the identity graph. Fauxx adds plausible synthetic activity so that low-confidence behavioral inferences become noisier and less certain. It does not replace or overwrite a profile.

### What Fauxx does not reach

Fauxx jams weak signals. It deliberately does not attempt to defeat the strong, deterministic mechanisms modern profiling anchors on, and it makes no claim to affect the following:

- **Deterministic identity joins** from your real email address or phone number.
- **Google Privacy Sandbox Topics.** Fauxx runs in isolated WebViews that do not influence the Topics your real browser computes.
- **Server-side and Conversions API (CAPI) events** sent directly between businesses and ad platforms.
- **Device and Play Integrity attestation** and attested in-app telemetry.
- **Public records and offline data** that brokers buy and sell.
- **Connected-TV (CTV) and household-IP** graph linking.

Also out of scope, as for any app: nation-state surveillance, SIM swaps, device compromise, and subpoenas.

Realistically, Fauxx dilutes at the edge of the identity graph. It is not profile replacement, and it works best as one layer in a broader privacy strategy.

## Feedback & Security

Found a bug? Have a suggestion? Security concern?

- Open an issue on GitHub
- For security issues, please report responsibly

---

Built with care for privacy. Stay in control of your data.
