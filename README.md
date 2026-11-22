# Fauxx

[![CI](https://img.shields.io/github/actions/workflow/status/digital-grease/fauxx/ci.yml?branch=main&logo=github&label=CI)](https://github.com/digital-grease/fauxx/actions/workflows/ci.yml)
[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](https://github.com/digital-grease/fauxx/blob/main/LICENSE)
[![Android API](https://img.shields.io/badge/API-26%2B-brightgreen?logo=android)](https://developer.android.com/about/versions/oreo)
[![GitHub Issues](https://img.shields.io/github/issues/digital-grease/fauxx)](https://github.com/digital-grease/fauxx/issues)

<a href="https://www.buymeacoffee.com/digitalgrease" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-red.png" alt="Buy Me A Coffee" style="height: 60px !important;width: 217px !important;" ></a>

**Data poisoning for your everyday tracking.**

Fauxx is an open-source Android privacy tool that poisons data broker and ad-tech profiles by generating continuous, plausible, off-demographic synthetic activity from your device. The goal is simple: bury your real behavioral signal under a steady stream of coherent, believable decoy activity, so profilers can't tell the real you from the plausible fakes. Not easily filterable noise, but deception that reads as real people.

> 💬 **Questions about how Fauxx works, or wishlist ideas?** Use [Discussions](https://github.com/digital-grease/fauxx/discussions). Bug reports and feature requests stay in [Issues](https://github.com/digital-grease/fauxx/issues).

> 🤖 **AI usage disclosure:** see [AIUSE.md](https://github.com/digital-grease/fauxx/blob/main/AIUSE.md).

> 📦 **Where to get it:** **F-Droid**, **GitHub Releases**, or Obtainium pointed at the GitHub releases. Fauxx is not on Google Play and will not return there (see [Why not Google Play?](#why-not-google-play)).

> 🖥️ **Desktop companion (alpha):** [fauxx-desktop](https://github.com/digital-grease/fauxx-desktop) is a new, early-alpha cross-device privacy command center (a Rust headless core with an Iced GUI). It runs decoy generation on a desktop or homelab box and shares an encrypted, local-only LAN persona-sync protocol with the Android app, so paired devices can keep one coherent persona. It is alpha software, so expect rough edges while it stabilizes.

## The Problem

Every search you make, every link you click, every location you visit is collected by data brokers, ad networks, and analytics platforms. Over time, they build a detailed profile of who you are, what you want, and what you're likely to do next. That profile is sold, traded, and collated with other data and profiles to continue the process.

Fauxx addresses this by injecting continuous, category-weighted synthetic activity that obscures your real interests under a cloud of plausible, believable decoys. Your genuine signal gets lost among convincing fakes: not random noise a broker can filter out, but coherent activity that looks like real people.

## How It Works

Fauxx uses a **Demographic Distancing Engine**, a layered system that decides what synthetic activity to generate:

### Layer 0: Baseline (Always Active)

Every category starts from the same weight, and none is ever dropped entirely, so decoy activity always spans the whole range of topics. On its own this layer changes nothing: the persona (Layer 3) and your optional inputs (Layers 1 and 2) shape the actual mix.

### Layer 1: Self-Report (Optional)

You optionally tell Fauxx coarse demographics (age range, interests, profession, region). Fauxx then weights AWAY from these categories, generating believable decoy activity in the things you don't care about, so the interests it steers clear of are your real ones.

- Skip it? You keep the default persona-shaped decoys from Layer 3.
- Enable it? Your real profile becomes harder to infer.

### Layer 2: Ad-Profile Import (Opt-in, Advanced)

Fauxx imports the ad-interest profile the platforms have already built about you, from data you provide: the categories Google lists in My Ad Center (pasted or typed into Fauxx), your advertising data from Google Takeout, or Facebook's "Download Your Information" (JSON). It reads exactly what the ad networks think they know about you, then aggressively suppresses those confirmed categories so the decoy activity concentrates on believable off-profile interests instead.

- You provide the data. Fauxx never logs in, reads cookies, or touches the platforms.
- Reads only what you give it. Nothing is sent anywhere.
- Re-import occasionally as your profile drifts (Fauxx reminds you after about 90 days).
- On a missing or unrecognized file, or pasted text that matches no category, nothing is imported and the other layers carry on unchanged.

### Layer 3: Synthetic Persona Rotation (On by Default)

Layer 3 runs on its own, whether or not Layers 1 and 2 are enabled. To avoid a predictable change-point, Fauxx rotates to a fresh coherent persona on a jittered schedule: a lifetime drawn uniformly over 30 to 90 days, re-rolled every cycle, so rotations never land on a fixed tick. This persona (a fake age, profession, interests, and region) becomes the decoy identity that shapes the next stretch of activity: a believable person who isn't you. And it doesn't all flip at once. The persona's channels (device, location, queries, rhythm, category weights) phase in over days through staggered adoption, so a broker sees no single synchronized change-point to lock onto.

### How Weights Combine

All layers produce a weight map across content categories. These weights multiply together and normalize, so the final distribution sums to 1.0. Categories are clamped with a minimum weight of 0.001, because absence is still a signal.

Example: if you report yourself as a 25-to-34-year-old engineer, Layer 1 suppresses the categories typical of that profile, such as TECHNOLOGY and GAMING, to 0.15×, and boosts distant ones, such as RETIREMENT and PARENTING, to 2.5×. When Layer 2 imports your ad profile and sees Google has tagged you with TECHNOLOGY, it suppresses that category further (0.05×) and boosts categories Google has never associated with you (3.0×). These multiply together, then Layer 3 blends in the current persona's preferences. The result: a decoy profile that reads like a real person, just not you.

## Modules

Fauxx poisons through seven complementary channels. Location Spoofing and App Signals are off by default; the rest are on.

### 1. Search Poisoning

Runs synthetic search sessions across Google, Bing, DuckDuckGo, Yahoo, and Yandex, and you can switch off any engine you would rather spare. Most queries are composed on-device by a per-install grammar model (with a Markov fallback) over a bundled corpus, so they read as natural, topically coherent searches rather than random gibberish. The grammar is styled per install, so no two devices emit the same query distribution for a broker to fingerprint.

Each session is one browser visit: a first query, sometimes a few refined follow-up queries, and usually a click on one or two results, with a few seconds of dwell on each page. Each persona has its own search habits: a usual engine for most of its searches, picked in proportion to each engine's real market share in the noise language, and its own odds of refining and of clicking. The number of refinements and clicks depends on the intensity (at Max, a session is a single query), and a whole session is capped at about a minute. Bing and Yahoo wrap their result links in their own redirectors, so sessions on those engines end on the results page.

**Category-aware:** Query bank selection is weighted by your targeting engine output.

### 2. Ad Pollution

Loads ad-supported pages from the category-weighted crawl list in a background WebView, and now and then visits the platforms' ad-preference dashboards. It never clicks ads or triggers conversions: every load is a plain page visit, so it cannot be mistaken for click fraud.

### 3. Location Spoofing (experimental, off by default, see limits below)

Uses Android's MockLocationProvider to feed fake GPS coordinates along plausible paths, drawn from a database of about 800 world city centers. Routes are bound to the active persona's region.

**This module does not currently do what its name suggests, and you should not rely on it.** Measured on Android 14: Fauxx registers an additional location provider of its own rather than replacing the system ones, so apps that ask for location the normal way (Play Services fused location, or the standard GPS provider) receive your real location, unaffected. Apps also do not select Fauxx's provider when asking the system to choose one. The fake coordinates are generated correctly and then largely go nowhere.

Two further limits apply even if that is fixed. Android flags every coordinate from a mock provider, so any recipient that checks can discard them with a single call, and that flag cannot be suppressed without root. And spoofing GPS does not affect the other channels that reveal location anyway: your IP address, Wi-Fi and cell scanning, your timezone and SIM, or an address typed into an account.

It is kept, off by default, because the surrounding scaffolding is sound and a future privileged approach could use it. It is not a reason to choose Fauxx.

**Setup:** Android requires you to designate the mock-location app explicitly. Enable Developer Options (Settings → About phone → tap Build Number 7 times), then Developer Options → "Select mock location app" → Fauxx. The Location Spoofing toggle surfaces this dialog on first enable.

### 4. Device Identity

Presents a **stable, coherent device identity** per synthetic persona rather than churning random User-Agents. Each persona gets one believable Android handset, presented the way Chrome for Android presents it: the reduced Chrome User-Agent, with the handset model and Android version carried in the matching client hints (`Sec-CH-UA*` and `navigator.userAgentData`). The Chrome version is always the installed WebView's, so the version a page is told agrees with the engine it can probe, and the synthetic traffic reads as one real device instead of the User-Agent-hopping pattern anti-fraud systems trivially flag and discard. Fauxx no longer injects canvas noise or overrides `navigator` values: on-device measurement showed each override was itself a contradiction a page could read, so pages now see the real engine.

### 5. Cookie Saturation

Visits categorized URLs (about 2,400 in English, with smaller Spanish, French and Russian lists) in isolated background WebViews, accumulating tracker cookies across diverse categories. Each URL load respects a per-domain rate limit (minimum 5 seconds between hits). WebViews are pooled and confined to Fauxx's own WebView data directory, so synthetic traffic never touches your real browser's cookies.

**Per-persona cookie jars:** each synthetic persona browses with a cookie jar, site storage and cache of its own. When Fauxx rotates to a new persona, the browser identity and everything stored under the old one change together, so nothing a tracker stored under one persona can be read back under the next. This needs a recent Android System WebView; where that support is missing, all personas share one jar as before. Every persona still browses from the same phone and the same network address, and Fauxx does not fake the hardware signals a fingerprinting script reads, so a tracker that fingerprints rather than reads cookies can still tell they are one device.

**Category-aware:** URL selection weighted by your targeting engine.

### 6. App Signals

Off by default. Browses Google Play store searches for apps in off-profile categories, in the same background WebView, so the persona's Play browsing reflects decoy interests. It stays on play.google.com in the WebView: it never opens the Play Store app, and never installs or launches anything.

### 7. DNS Noise

Resolves a diverse spread of domain names, so the DNS lookups visible to ISP and network-level trackers reflect the decoy activity instead of only your real destinations.

## Privacy Guarantees

Fauxx is built with privacy-first architecture:

- **On-device only:** All demographic data, profile settings, and activity logs stay on your device. Nothing is uploaded.
- **Encrypted database:** The whole Room database, including your self-reported demographics, imported ad profiles and the action log, is encrypted with SQLCipher. Its passphrase is stored encrypted by a Tink key that is protected by the Android Keystore.
- **Import reads only your file:** Layer 2 reads the ad-profile export you hand it. It never logs into, modifies, or contacts the ad platforms.
- **No sensitive attributes:** Demographic distance rules never include or infer race, ethnicity, religion, sexual orientation, gender identity, disability, or political affiliation.
- **Domain blocklist:** Every URL is checked against a hardcoded blocklist of illegal/harmful domains before loading.
- **Rate limiting:** At most one visit per domain every 5 seconds, and a hard per-hour cap on actions set by the intensity: 12 at Low, 60 at Medium, 200 at High, 500 at Max.
- **No fingerprinting of users:** The app does not track or identify individual users. It only tracks its own action log locally.

## Tech Stack

- **Language:** Kotlin (Android API 26+ minimum, API 36 target)
- **UI:** Jetpack Compose + Material 3 (dark-first theme)
- **Browsing:** Android System WebView through AndroidX WebKit (per-persona profiles, proxy override, client-hint metadata)
- **Database:** Room + SQLCipher (encrypted); settings in Jetpack DataStore
- **Networking:** OkHttp 5 with okhttp-dnsoverhttps, and MiniDNS for plain DNS (custom DNS)
- **Security:** Tink and the Android Keystore for key storage
- **Background:** a foreground service runs the engine; WorkManager and exact alarms handle resume, reconciliation and log retention
- **DI:** Hilt

## Installation

### Prerequisites

- Android SDK Platform 37 (the app compiles against API 37 and targets API 36)
- Kotlin compiler (bundled with Gradle)
- JDK 21 (LTS, matching the F-Droid buildserver)

### Build from Source

```bash
# Clone the repository
git clone https://github.com/digital-grease/fauxx.git
cd fauxx

# Build the debug APK ("full" is the only build flavor)
./gradlew assembleFullDebug

# Build the release APK (unsigned; sign it with your own key)
./gradlew assembleFullRelease

# Run unit tests
./gradlew testFullDebugUnitTest

# Run instrumented tests on a connected device or emulator
./gradlew connectedFullDebugAndroidTest
```

The debug APK will be at `app/build/outputs/apk/full/debug/app-full-debug.apk`.

### Installation

```bash
# Install via adb
adb install app/build/outputs/apk/full/debug/app-full-debug.apk

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

Every screen has a visible "Skip" button. You can skip all of it and run on the default persona-shaped decoys. If you answer anything, Layer 1 is switched on when you finish, so your answers steer the decoys straight away; you can turn it off on the Targeting screen.

### Dashboard

View at a glance:
- Protection status (on/off toggle), with the reason whenever the engine is paused
- Actions executed today and this week
- Category distribution donut chart showing how the decoy activity is spread
- Current synthetic persona (name, age, profession, interests)
- Synthetic activity: the average number of decoy actions per hour over the last 24 hours
- Profile drift: how far your ad profile has moved between Layer 2 imports, plus a comparison against a control profile if you import one
- Quiet notices when something needs attention, such as custom DNS falling back or a WebView that cannot hide Fauxx's package name

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
- Device Identity
- Cookie Saturation (URL categories)
- App Signals
- DNS Noise

### Log

Scrollable audit log of all actions with timestamps, types, and details. Export to CSV, JSON or HTML, or share a single entry.

### Settings

Global controls:
- **Wi-Fi intensity:** Low (light activity) / Medium (balanced) / High (aggressive) / Max (highest volume)
- **Mobile data intensity:** a separate Off / Low / Medium / High / Max ladder for mobile data. Off (the default) never touches mobile data; any tier runs the engine on mobile at its own rate. Wi-Fi networks the system reports as metered, a tethered phone hotspot or a network you marked metered in Android's Wi-Fi settings, are governed by this ladder too, so Fauxx does not quietly spend a data allowance it cannot see
- **Wi-Fi intensity while charging:** Off (the default) / Low / Medium / High / Max. While the phone is plugged in on unmetered Wi-Fi, Fauxx runs at this level instead, so it can stay light on battery and do more while charging. It only ever raises the rate. See [How does the charging intensity work?](#how-does-the-charging-intensity-work)
- **Battery threshold:** Minimum battery % to run actions (Can be adjusted individually while on battery and while charging)
- **Pause during Battery Saver:** Off by default. On, Fauxx stops while Android's Battery Saver is on and picks up again as soon as it turns off. While paused, its notification stays up and it checks once a minute, so it can resume the moment Battery Saver ends
- **Active hours:** Time range when actions should run (e.g., 7am–11pm)
- **Search engines:** Which engines receive synthetic queries. Turning one off spares it entirely. At least two stay active: each persona has a usual engine but sometimes searches elsewhere, and needs another one when its usual engine is unavailable
- **Load images on synthetic pages:** Off by default. On, pages load images like a real browser, so tracking pixels fire and each visit looks normal to the site; off saves a lot of data, since images are most of a page's size, but a browser that never loads images is unusual
- **Custom DNS for Fauxx:** Off by default. On, the sites Fauxx visits are looked up with a resolver you choose (encrypted DNS-over-HTTPS from Quad9, Cloudflare, Mullvad, Google or your own URL, or a plain DNS server by IP address) instead of your device's DNS, so a Pi-hole, NextDNS profile or VPN app that blocks tracker domains no longer stops Fauxx. Other apps are not affected. See [My Pi-hole, NextDNS or VPN blocks tracker domains](#my-pi-hole-nextdns-or-vpn-blocks-tracker-domains-so-fauxx-cant-reach-them-what-can-i-do) for the options and how fallback works
- **Your searches and sites:** add your own search terms and websites, such as local news and searches in your language, one at a time or many at once (one per line). They get a capped share of the activity, from 5% with one entry up to 30%, so the built-in lists always carry most of it. See [How do my own searches and sites work?](#how-do-my-own-searches-and-sites-work)
- **Clear all data:** Destructive button to reset everything

## Configuration

User settings are exposed in the app UI and stored in Jetpack DataStore; the thresholds below are compile-time constants. Key thresholds:

- **Per-domain rate limit:** 5 seconds (CrawlListManager)
- **Action timing:** exponentially distributed gaps around the intensity's average rate (one minute apart on average at Medium), with a short 2 to 30 second follow-up on some same-topic transitions
- **Cross-niche dwell:** a lognormal dwell-time multiplier on category transitions (e.g., Finance → Legal) with a floor of up to 30 seconds, so the engine never jumps between unrelated niches in under a second
- **Daily rhythm:** no activity outside your active hours (7am to 11pm by default). Inside them, the rate rises and falls within ±50% of the average, following the hours you usually use your phone (learned on-device from screen-on times) and the persona's own peak hours
- **Layer 3 rotation:** lifetime drawn uniformly over 30–90 days, re-rolled each cycle; channels phase in via staggered adoption over up to 20% of that lifetime
- **Layer 2 re-import reminder:** about 90 days (the import is manual, not scheduled)

## Project Structure

```
app/src/main/
├── java/com/fauxx/
│   ├── FauxxApp.kt                      # Application entry point
│   ├── di/                              # Hilt modules, encrypted DB and key management
│   ├── data/
│   │   ├── db/                          # Room database, DAOs, entities
│   │   ├── model/                       # Data models (PoisonProfile, CategoryPool, etc.)
│   │   ├── querybank/                   # Query banks, grammar and Markov query generators
│   │   ├── crawllist/                   # URL corpus manager & blocklist
│   │   └── location/                    # Fake route generator & city database
│   ├── targeting/                       # Demographic Distancing Engine
│   │   ├── TargetingEngine.kt           # Orchestrator
│   │   ├── layer0/ … layer3/            # Baseline, self-report, ad-profile import, persona rotation
│   │   ├── allocation/                  # Optional adversarial allocation stage
│   │   └── WeightNormalizer.kt
│   ├── engine/
│   │   ├── PoisonEngine.kt              # Core orchestrator and constraint loop
│   │   ├── modules/                     # The seven poison modules
│   │   ├── webview/                     # WebView pool, browser identity, per-persona jars
│   │   ├── dns/                         # Custom DNS router
│   │   └── scheduling/                  # Scheduler, dispatcher, daily-rhythm modulation
│   ├── network/
│   │   └── dns/                         # Loopback proxy, DoH and plain DNS resolvers
│   ├── locale/                          # Supported locales, Accept-Language variants
│   ├── service/                         # Foreground service, resume scheduling, workers
│   └── ui/                              # Compose screens, navigation, theme
└── assets/
    ├── query_banks/                     # Query banks per category (es/, fr/, ru/ per locale)
    ├── crawl_urls.json, crawl_urls/     # Categorized URLs (English, plus es/fr/ru lists)
    ├── harmful_queries.json, harmful_queries/  # Safety blocklists per locale
    ├── persona_templates.json, persona_templates/  # Persona archetypes per locale
    ├── device_templates.json            # Handsets personas can present as
    ├── city_coords.json                 # About 800 city coordinates
    ├── blocklist.json                   # Blocked domains
    ├── demographic_distance_rules.json  # Category weight rules by demographic
    └── platform_category_map.json       # Platform string to CategoryPool mapping
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

Fauxx ships in English (`en`), Spanish (`es`), French (`fr`) and Russian (`ru`). Each locale's content (UI strings, query banks, harmful-queries blocklist, persona templates and names, crawl URLs, search-engine URL params, Accept-Language headers) is locale-aware end to end. Selecting a language flips all of those layers together: the synthetic activity tracks the UI language, so a Spanish-mode profile emits `Accept-Language: es-ES` with `&hl=es&gl=ES` URL params, not a mismatched `en-US` that would itself be a fingerprintable signal.

**Locale gate.** Production builds restrict the Settings language picker via `BuildConfig.SHIPPED_LOCALES`. A locale only joins the allowlist after its `assets/harmful_queries/<localeTag>.json` has signed-off native-speaker review. The blocklist contains the region's crisis-line, domestic-violence, and poison-control numbers; an English fallback would not catch the Spanish/French equivalents and would risk dispatching a synthetic query that data brokers interpret as a real first-person distress signal. All four locales have passed that review and ship in production builds.

**Adding a new locale.** Outline of the work, in order:
1. Append the locale to the `SupportedLocale` enum (with `tag`, `displayName`, `defaultRegion`, `yahooSubdomainPrefix`).
2. Translate `app/src/main/res/values/strings.xml` → `values-<localeTag>/`.
3. Draft `assets/harmful_queries/<localeTag>.json`. Class A illegal terms can start as a translation of the English file; the self-signal section must include the region's crisis hotlines (e.g. ES 024, FR 3114, DE 0800-181-0721, etc.) and DV / poison-control numbers. **Native-speaker review is mandatory before this locale ships.** Add a sentinel-presence assertion in `HarmfulQueriesLocaleAuditTest` to lock the regression in.
4. Curate `assets/persona_templates/<localeTag>.json` (region- and culture-plausible archetypes) and `assets/crawl_urls/<localeTag>.json` (region-appropriate domains across all CategoryPool values).
5. Add a name table for the locale to `PersonaNames` (common given names and surnames, so personas are named like people from the region), and ES/FR/etc. entries to `CATEGORY_APP_KEYWORDS` in `AppSignalModule` (Play Store keywords idiomatic to that storefront).
6. Add an entry to `AcceptLanguageVariants` (4–5 plausible primary/secondary Accept-Language strings).
7. Run `ANTHROPIC_API_KEY=... python3 scripts/translate_query_banks.py <localeTag>` to populate the per-locale query banks (32 categories). Spot-check a few categories for idiomatic phrasing.
8. After native-speaker review, bump `BuildConfig.SHIPPED_LOCALES` in `defaultConfig` to include the new tag.

## FAQ

### What does "Synthetic Activity" on the Dashboard mean?

It's the average number of decoy actions Fauxx generated per hour over the last 24 hours: how much cover traffic it is adding.

What it *doesn't* measure: a ratio against your real activity, which Android does not let an app see, or the *quality* of the decoys, such as whether they are hitting categories far from your real interests or fooling profiling systems.

To see where the decoy activity is going, the **Targeting screen's category-weight chart** is more useful: red bars are categories Fauxx is suppressing (because they match your profile), green bars are categories it's boosting (off-profile decoys), gray is neutral. If you have imported an ad profile in Layer 2, the dashboard's **profile drift** card shows how far the imported profile has moved from your baseline.

### I use DuckDuckGo App Tracking Protection or another tracker-blocking app. Does that affect Fauxx?

Yes. Fauxx keeps running, but much of its effect is lost. The decoy signal travels in the requests a page makes to ad networks and analytics companies, and those are exactly what tracker blockers stop. In one report, DuckDuckGo App Tracking Protection had blocked 1,445 requests from 38 tracking companies made by Fauxx. The pages themselves still load, so the action log shows normal visits. The Dashboard can usually spot DNS-based blockers (Pi-hole, NextDNS, Private DNS, and the DNS filtering in AdGuard, Rethink or Blokada), and shows a hint when DuckDuckGo is installed and a VPN is on. DuckDuckGo App Tracking Protection itself can't be confirmed from inside Fauxx, so exclude Fauxx from it anyway.

Exclude Fauxx from the blocker. Your other apps stay protected, and what gets through is Fauxx's decoy browsing, not your own.

- **DuckDuckGo App Tracking Protection:** in its settings, open the list of protected apps and turn protection off for Fauxx.
- **Other blocker apps that run as a VPN on your phone** (AdGuard, Blokada, Rethink, TrackerControl and similar): add Fauxx to their excluded or bypassed apps. In Rethink, set Fauxx to Exclude: bypass DNS and firewall is not enough on Android 8 to 10, where the system makes Fauxx's DNS lookups on its behalf and Rethink cannot tell they are Fauxx's.
- **Blockers that cover your whole network or your phone's DNS** (Pi-hole, NextDNS, Private DNS, a router): these cannot exclude a single app. Use Custom DNS instead, described in the next question.

### My Pi-hole, NextDNS or VPN blocks tracker domains, so Fauxx can't reach them. What can I do?

Turn on **Settings → Custom DNS for Fauxx**. Fauxx then looks up the sites it visits with a resolver you pick, over encrypted DNS (DNS-over-HTTPS) or a plain DNS server, while every other app on your phone keeps using your normal DNS and its blocking.

How it works: Android's WebView has no DNS setting of its own, so Fauxx runs a small proxy on your phone's loopback address and points its own WebView at it. The proxy looks each name up through your chosen resolver and passes the connection through untouched; TLS stays end to end between the WebView and the site. The proxy requires a password that changes every run, so other apps on your phone cannot use it to get around a firewall.

Things to know:

- If a VPN or firewall app filters DNS, exempt Fauxx from its DNS filtering (but see below if your resolver lives behind that VPN). In Rethink, choose Exclude for Fauxx. If your resolver is only reachable through Rethink, set Fauxx to bypass DNS and firewall instead: its "block when DNS is bypassed" rule would otherwise block the connections Fauxx makes with its own DNS answers.
- It covers every site Fauxx's browser loads, including the images, scripts and ads inside a page: the WebView hands each name to the proxy unresolved, and the proxy asks your resolver.
- By default it falls back. When your resolver is slow or unreachable (three slow or failed lookups in a row, which happens easily over a VPN), Fauxx sends lookups to your device's DNS for at least a minute before trying your resolver again, and says so on the dashboard. **Never fall back to your device's DNS** stops this: lookups then fail, and Fauxx's pages do not load until your resolver answers again. With it on, Fauxx also pauses its browsing whenever its WebView cannot be routed through your resolver, keeps retrying, and says why on the dashboard; on a WebView that cannot route through a proxy at all, Settings keeps the switch reachable so you can turn it off.
- DNS noise uses your device's DNS on purpose, to fill your network's and ISP's DNS logs, unless you turn on **Also use it for DNS noise** (or never-fall-back, which does the same).
- If you use your own resolver URL (a NextDNS profile, for example), Fauxx looks up that resolver's own address through its default resolver first, since DoH-bypass blocklists often block those hostnames. Only with fallback allowed is the device's DNS the last resort for that lookup. You can also give the server's IP address next to the URL, so Fauxx never has to look up its name at all (useful for a resolver on your network, or one your DNS blocks).
- For a self-hosted resolver with a self-signed certificate, turn on **Skip certificate check**. Fauxx then trusts the certificate it first sees from that server and refuses any other, so an impostor on another network is turned away. If you replace your server's certificate, switch the setting off and on to trust the new one; the dashboard tells you when this is needed.
- Your own URL may be `http://` for a resolver on a private network (a self-hosted DoH server behind your VPN, say). Fauxx accepts it when you give the server's private IP address (then the URL may use any name), or when the URL itself names a private IP or a `.lan`, `.local`, `.home.arpa`, `.internal` or `.ts.net` host. It never sends the queries to a public address, but they are not encrypted and go out on whatever network your phone is on, including public Wi-Fi that happens to use the same private range, where anyone on it can read them. A `100.x` (Tailscale) or VPN address only reaches your server while that VPN is connected. Settings says this for as long as an `http://` resolver is in use. With never-fall-back on, give the server's IP address too, since only your device's DNS knows names like `pihole.lan`.
- If your resolver is only reachable through your VPN, do not exempt Fauxx from the VPN itself; exempt it from the VPN app's DNS filtering only.
- Plain DNS is not encrypted: your network and ISP can see the names it looks up. It is also often captured by VPN and firewall apps, which then answer in place of your server; Fauxx checks for this (again whenever your network changes) and says so on the dashboard. Encrypted DNS avoids both.
- It gets around DNS-level blocking only. Blocking by IP address or by TLS server name still applies.
- While it is on, sites that support HTTP/3 are reached over HTTP/2: browsers do not use HTTP/3 through a proxy.
- If your WebView lacks the proxy feature (PROXY_OVERRIDE), the setting is unavailable. WebView 113 and 151 both have it. With Google Play, updating Android System WebView may add it; otherwise your WebView usually updates with your system or ROM, or from wherever it was installed.

### How does Layer 2 (Ad-Profile Import) work, and what do I import?

Layer 2 reads the ad-interest profile the platforms have already built about you, from data you give it, and tells the engine to *suppress* those categories so the synthetic activity steers away from your real profile.

To use it:

- **Google, from My Ad Center (most complete).** Google does not document any export of the ad categories it has inferred about you, but [My Ad Center](https://myadcenter.google.com) shows them under **Manage privacy → "Categories used to show you ads"**. On Fauxx's Targeting screen, tap **Enter categories from My Ad Center**, then paste or type the list, one category per line. Fauxx shows which of its categories each line matches and which lines it ignores before you save. Fauxx recognises English category names best.
- **Or export your ad data.** From Google, use [Google Takeout](https://takeout.google.com) and select your advertising data (you can deselect everything else to keep the file small). From Facebook, use **Download Your Information** (accountscenter.facebook.com), choose **JSON** format, and include **"Ads and businesses"**. The platform emails you an archive (a ZIP or JSON file).
- **Import the file.** On Fauxx's Targeting screen, tap the Google Takeout or Facebook import button and pick the file you downloaded. Fauxx reads the ad-interest categories and applies the away-from weighting.
- **Nothing leaves your device.** Fauxx never logs in, reads cookies, or contacts the platforms. It only parses the file or text you give it, on-device.
- **Re-import occasionally.** Your ad profile drifts over time, so Fauxx reminds you to re-import after about 90 days. There is no background scraping.

If the import says it couldn't find ad categories, there are two common causes. Google's Takeout may not include the inferred categories at all, in which case use **Enter categories from My Ad Center** instead. Or personalized ads are turned **off** in your Google or Facebook settings, so the platform keeps no ad profile; turn personalized ads on, let a profile build up, then try again.

A Google Takeout import and the My Ad Center entry are both stored as your Google profile, so saving one replaces the other.

### Why doesn't Fauxx's activity show up in my Google account or My Ad Center?

Because Fauxx is never signed in to your accounts, on purpose. It browses in its own sandboxed WebView with no access to your Google login. Google's terms restrict automated use of its services, and sending Fauxx's decoys through your real account could get that account flagged or suspended.

What you see in My Ad Center, My Activity and Google Takeout comes mostly from what you do while signed in: your searches, YouTube, and Chrome and Android activity tied to your account. Fauxx's activity is not tied to your account, so it will not appear there, and your Google ad interests usually will not change because of it. For the same reason, Profile drift on the Dashboard may barely move for Google.

Fauxx aims at the profiling that needs no login: third-party ad networks and trackers, data brokers, and anything that recognises you by your device, IP address or DNS lookups (see [Threat Model](#threat-model)). Those profiles are not shown to you, so there is no page where you can watch them change.

The Layer 2 import works the other way round. It tells Fauxx what Google and Facebook already think you like, so the decoy personas steer away from your real interests instead of resembling you. It is a baseline for the noise, not a score for whether Fauxx is working.

### Why does Fauxx sometimes stop and show a "Tap to resume protection" notification?

When Fauxx pauses for a long stretch, it releases its foreground service rather than sit idle holding the slot, and posts a one-tap notification so you can resume when you next pick up your phone.

You'll typically see this notification:

- **Outside your active hours (default 7am to 11pm), normally nothing.** Fauxx steps down at the end of the window and sets an exact alarm that restarts it automatically when the next window opens. If the system denies that alarm, it falls back to a tap-to-resume notification at that time.
- **After a long no-network pause,** if your mobile data intensity is Off and Wi-Fi is gone or is a metered network, or after a long low-battery pause. Sustained pauses past 30 minutes release the service rather than spinning idle. A Battery Saver pause is the exception: Fauxx keeps the service so it can resume the moment Battery Saver turns off, since Android gives a stopped app no way to find out.
- **After a reboot or an app update,** because Android won't let Fauxx restart its own foreground service from a boot or update event. Fauxx posts the resume notification instead.

Tapping the notification opens Fauxx and restarts protection. Nothing is lost. Your settings, profile, persona, and action log are all persistent.

### How does the charging intensity work?

**Settings → Wi-Fi intensity while charging** (Off by default) sets the rate Fauxx runs at while the phone is plugged in on unmetered Wi-Fi. It only ever raises the rate, so a level at or below your Wi-Fi intensity changes nothing.

- Mobile data and metered Wi-Fi keep their own intensity whether or not the phone is charging.
- Active hours, the battery thresholds and Battery Saver still apply.
- It takes effect within seconds of plugging in, and the dashboard shows "Charging boost" while it is in effect.
- After you unplug, the lower rate's hourly cap counts the past hour's actions, so Fauxx may pause briefly until that hour has passed.

### How do my own searches and sites work?

**Settings → Your searches and sites** lets you add search terms and websites when the built-in English, Spanish, French and Russian lists do not match where you live, such as local news sites and searches in your language.

- **Share of the activity:** each entry sends 5% of search sessions or site visits to your list, up to 30% from six entries on. The built-in lists always carry the rest, so a short personal list never dominates the noise or hammers one site.
- **No category:** your entries are drawn alongside whatever the persona is browsing.
- **Safety checks:** searches must pass the harmful-query blocklists of all four languages, not just the active one. Sites must be public `http`/`https` addresses (no IP addresses, local network names or embedded passwords) and pass the same domain blocklist and per-site rate limit as the built-in sites.
- **Privacy:** entries are stored in the encrypted database, never written to the debug log, deleted by **Clear all data**, and not carried by LAN sync.
- In the Log, a search from your list is marked `[CUSTOM]`.

### Why not Google Play?

Play's policies disallow Fauxx's location-spoofing and ad-profile-pollution modules, and Play is moving toward requiring its age-verification API, which a no-account privacy tool has no business integrating. The former `play` build flavor has been removed from the source.

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

Automated searches and page visits may conflict with some platforms' terms of service, and continued use of those platforms may still subject you to profiling through other channels (cookies, account-level data, cross-site tracking). No tool is perfect.

## Threat Model

- **Adversaries:** Data brokers, ad networks, analytics platforms, ISPs.
- **Data in scope:** The weak, low-confidence signals these adversaries infer from search queries, URL visits, location history, device fingerprints, app-store interest signals, and DNS queries.
- **Goal:** Dilution at the edge of the identity graph. Fauxx adds plausible decoy activity so that low-confidence behavioral inferences become less certain and less trustworthy to the profiler. It does not replace or overwrite a profile.

### What Fauxx does not reach

Fauxx targets weak signals with believable decoys. It deliberately does not attempt to defeat the strong, deterministic mechanisms modern profiling anchors on, and it makes no claim to affect the following:

- **Deterministic identity joins** from your real email address or phone number.
- **Server-side and Conversions API (CAPI) events** sent directly between businesses and ad platforms.
- **Device and Play Integrity attestation** and attested in-app telemetry.
- **Public records and offline data** that brokers buy and sell.
- **Connected-TV (CTV) and household-IP** graph linking.

Also out of scope, as for any app: nation-state surveillance, SIM swaps, device compromise, and subpoenas.

Realistically, Fauxx dilutes at the edge of the identity graph. It is not profile replacement, and it works best as one layer in a broader privacy strategy.

## Feedback & Security

- **Bugs and feature requests:** open an [issue](https://github.com/digital-grease/fauxx/issues).
- **Questions and ideas:** use [Discussions](https://github.com/digital-grease/fauxx/discussions).
- **Security vulnerabilities:** do not open a public issue. Report privately as described in [SECURITY.md](SECURITY.md).

---

Built with care for privacy. Stay in control of your data.
