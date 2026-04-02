# Fauxx

[![CI](https://img.shields.io/github/actions/workflow/status/digital-grease/fauxx/ci.yml?branch=main&logo=github&label=CI)](https://github.com/digital-grease/fauxx/actions/workflows/ci.yml)
[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](https://github.com/digital-grease/fauxx/blob/main/LICENSE)
[![Android API](https://img.shields.io/badge/API-26%2B-brightgreen?logo=android)](https://developer.android.com/about/versions/oreo)
[![GitHub Issues](https://img.shields.io/github/issues/digital-grease/fauxx)](https://github.com/digital-grease/fauxx/issues)

<a href="https://www.buymeacoffee.com/digitalgrease" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-red.png" alt="Buy Me A Coffee" style="height: 60px !important;width: 217px !important;" ></a>

**Data poisoning for your everyday tracking.**

Fauxx is an open-source Android privacy tool that poisons data broker and ad-tech profiles by generating continuous, plausible, off-demographic synthetic activity from your device. The goal is simple: make your real behavioral signal statistically indistinguishable from noise.

## The Problem

Every search you make, every link you click, every location you visit is collected by data brokers, ad networks, and analytics platforms. Over time, they build a detailed profile of who you are, what you want, and what you're likely to do next. That profile is sold, traded, and used to target you—and to deny you opportunities.

Fauxx addresses this by injecting continuous, category-weighted synthetic activity that obscures your real interests under a statistical cloud. Your genuine signal becomes noise.

## How It Works

Fauxx uses a **Demographic Distancing Engine**—a layered system that determines what noise to generate:

### Layer 0: Uniform Entropy (Always Active)

The baseline: equal probability across all content categories. This is your foundation.

### Layer 1: Self-Report (Optional)

You optionally tell Fauxx coarse demographics (age range, interests, profession, region). Fauxx then weights AWAY from these categories—generating noise in the things you don't care about, maximizing confusion.

- Skip it? You keep Layer 0 uniform noise.
- Enable it? Your real profile becomes harder to infer.

### Layer 2: Adversarial Profile Scraping (Opt-in, Advanced)

Fauxx can scrape your existing ad interest profiles from Google Ads Settings and Facebook Ad Preferences. It then sees exactly what ads think they know about you, and aggressively targets the gaps—flooding the gaps with synthetic activity to dilute the confirmed interests.

- Requires authentication to ad platforms (read-only, never modifies).
- Runs on a configurable schedule (default: weekly).
- On failure, degrades gracefully to Layer 0.

### Layer 3: Synthetic Persona Rotation (Active When L1 or L2 Enabled)

To prevent pattern detection, Fauxx generates a coherent synthetic persona every 7 days (with random jitter). This persona—a fake age, profession, interests, region—becomes your "noise profile" for that week. It changes on a schedule, adding temporal coherence and making you harder to fingerprint.

### How Weights Combine

All layers produce a weight map across content categories. These weights multiply together and normalize, so the final distribution sums to 1.0. Categories are clamped with a minimum weight of 0.001—absence is still a signal.

Example: If you report yourself as a 25-year-old software engineer, Layer 1 drops RETIREMENT and PARENTING to 0.15× (away-from) and boosts GAMING and TECHNOLOGY to 2.5× (toward other interests). When Layer 2 sees Google knows you search for TECH, it further suppresses TECH (0.05×) and boosts categories Google has never seen from you (3.0×). These multiply together, then Layer 3 blends in the weekly persona's preferences. The result: a noise distribution that looks nothing like your real profile.

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
- **Read-only scrapers:** Adversarial platform scrapers only read your existing profiles. They never modify settings, click ads, or interact beyond reading.
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
- JDK 25+

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
- **Layer 2 Toggle:** Enable/disable adversarial scraping. See last scrape dates and scrape now.
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
- **Intensity:** Low (light noise) / Medium (balanced) / High (aggressive)
- **Wifi-only:** Only run poison actions on WiFi
- **Battery threshold:** Minimum battery % to run actions
- **Active hours:** Time range when actions should run (e.g., 7am–11pm)
- **Clear all data:** Destructive button to reset everything

## Configuration

All configurable values are exposed in the app UI and backed by Room preferences or compile-time constants. Key thresholds:

- **Per-domain rate limit:** 5 seconds (CrawlListManager)
- **Action timing:** Poisson-distributed with human-like bursts (3–7 actions, then 5–20 min gaps)
- **Circadian pattern:** Near-zero activity 11pm–7am local time
- **Layer 3 rotation:** Every 7 days ± [1, 3] days jitter
- **Layer 2 scrape interval:** Every 7 days (configurable via WorkManager)
- **Scraper timeout:** 30 seconds per platform

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
│   │   ├── layer2/                      # Adversarial scraper
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

Fauxx assumes:

- **Adversaries:** Data brokers, ad networks, analytics platforms, ISPs
- **Data in scope:** Search queries, URL visits, location history, device fingerprints, app installations, DNS queries
- **Goal:** Make your behavioral signal indistinguishable from synthetic noise
- **Out of scope:** Nation-state surveillance, SIM swaps, device compromise, subpoenas

## Feedback & Security

Found a bug? Have a suggestion? Security concern?

- Open an issue on GitHub
- For security issues, please report responsibly

---

Built with care for privacy. Stay in control of your data.
