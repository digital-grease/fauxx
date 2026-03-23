You are building an open-source Android app called "Fauxx" — a privacy tool that
poisons data broker and ad-tech profiles by generating continuous, plausible, off-demographic
synthetic activity from the user's device. The goal is to make the user's real behavioral
signal statistically indistinguishable from noise.

The app uses a layered Demographic Distancing Engine to determine WHAT noise to generate:
- Layer 0 (always active): Uniform entropy — equal weight to all content categories
- Layer 1 (optional): User self-reports coarse demographics; app weights AWAY from them
- Layer 2 (opt-in, advanced): Scrapes ad platform profiles to find confirmed interests,
  then aggressively targets the gaps
- Layer 3 (always active when L1 or L2 enabled): Rotates synthetic personas weekly to
  add temporal coherence and prevent pattern detection
Weights combine multiplicatively across layers and normalize to a probability distribution
that the ActionDispatcher samples from when selecting what category each action targets.

TECH STACK:
- Kotlin, targeting Android API 26+ (minSdk) and API 34 (compileSdk)
- Gradle with version catalog (libs.versions.toml)
- Jetpack Compose for all UI
- Material 3 with a dark-first theme (the app should feel like a security tool)
- Room for local database, with SQLCipher for encrypting demographic/profile tables
- AndroidX Security (EncryptedSharedPreferences, AndroidKeyStore) for key management
- OkHttp 4.x for networking with custom interceptors
- WorkManager for background scheduling
- Hilt for dependency injection
- Android ForegroundService for persistent background execution

PROJECT STRUCTURE (create all of these):
app/
├── src/main/java/com/Fauxx/
│   ├── FauxxApp.kt              # Application class, Hilt entry point
│   ├── di/                              # Hilt modules
│   │   ├── AppModule.kt                 # Singletons: Room DB (with SQLCipher encryption via
│   │   │                                #   AndroidKeyStore-backed key for demographic data),
│   │   │                                #   OkHttpClient, SharedPrefs, TargetingEngine
│   │   ├── WorkerModule.kt              # WorkManager + Hilt worker factory
│   │   ├── NetworkModule.kt             # OkHttp with HeaderRandomizerInterceptor
│   │   └── TargetingModule.kt           # Provides all targeting layer singletons: SelfReportLayer,
│   │                                    #   AdversarialScraperLayer, PersonaRotationLayer,
│   │                                    #   TargetingEngine, WeightNormalizer
│   ├── data/
│   │   ├── db/
│   │   │   ├── PhantomDatabase.kt       # Room database with entities below
│   │   │   ├── ActionLogDao.kt          # DAO for audit log
│   │   │   └── ActionLogEntity.kt       # Entity: id, timestamp, actionType, detail, category
│   │   ├── model/
│   │   │   ├── ActionType.kt            # Enum: SEARCH_QUERY, AD_CLICK, PAGE_VISIT,
│   │   │   │                            #   LOCATION_SPOOF, DNS_LOOKUP, COOKIE_HARVEST,
│   │   │   │                            #   DEEP_LINK_VISIT, FINGERPRINT_ROTATE
│   │   │   ├── IntensityLevel.kt        # Enum: LOW, MEDIUM, HIGH with actions-per-hour values
│   │   │   ├── PoisonProfile.kt         # Data class: enabled modules, intensity, schedule,
│   │   │   │                            #   wifiOnly, batteryThreshold, allowedHoursStart/End
│   │   │   └── SyntheticPersona.kt      # Data class for generated fake demographics
│   │   ├── querybank/
│   │   │   ├── QueryBankManager.kt      # Loads and serves queries from bundled JSON assets
│   │   │   ├── MarkovQueryGenerator.kt  # Generates natural-sounding compound search queries
│   │   │   │                            #   using n-gram model trained on bundled corpus
│   │   │   └── CategoryPool.kt          # Enum of query categories: MEDICAL, LEGAL, AUTOMOTIVE,
│   │   │       							 #   PARENTING, RETIREMENT, GAMING, AGRICULTURE, FASHION,
│   │   │       							 #   ACADEMIC, REAL_ESTATE, COOKING, SPORTS, FINANCE, etc.
│   │   ├── crawllist/
│   │   │   ├── CrawlListManager.kt      # Manages the 10,000+ URL corpus, tracks last-visit,
│   │   │   │                            #   enforces per-domain rate limits (min 5s between hits)
│   │   │   └── DomainBlocklist.kt       # Hard-coded blocklist of illegal/harmful domains,
│   │   │                                #   loaded from assets/blocklist.json
│   │   └── location/
│   │       ├── FakeRouteGenerator.kt    # Generates GPS coordinate sequences along plausible
│   │       │                            #   paths: walking (3-5 km/h), driving (30-100 km/h),
│   │       │                            #   stationary (small jitter around a fake "home")
│   │       └── CityDatabase.kt          # Bundled list of 500+ city center coordinates worldwide
│   ├── targeting/                        # *** DEMOGRAPHIC DISTANCING ENGINE ***
│   │   ├── TargetingEngine.kt           # Orchestrator: combines all active layers into a final
│   │   │                                #   normalized weight map (Map<CategoryPool, Float>).
│   │   │                                #   Exposes fun getWeights(): Flow<Map<CategoryPool, Float>>
│   │   │                                #   that recalculates when any layer input changes.
│   │   │                                #   Multiplicative combination: base × L1 × L2 × L3,
│   │   │                                #   then normalize so all weights sum to 1.0.
│   │   ├── layer0/
│   │   │   └── UniformEntropyLayer.kt   # Returns weight 1.0 for every CategoryPool value.
│   │   │                                #   Always active. Zero user data required.
│   │   ├── layer1/
│   │   │   ├── SelfReportLayer.kt       # Reads UserDemographicProfile from Room. Returns:
│   │   │   │                            #   0.15 for matching categories, 2.5 for distant
│   │   │   │                            #   categories, 1.0 for neutral. Falls back to all-1.0
│   │   │   │                            #   if user skipped onboarding.
│   │   │   ├── UserDemographicProfile.kt # Room @Entity: ageRange (enum), gender (enum),
│   │   │   │                            #   interests (Set<InterestArea>), profession (enum),
│   │   │   │                            #   region (enum). All fields nullable (optional).
│   │   │   ├── DemographicProfileDao.kt  # Room DAO: insert, get, delete (single-row table)
│   │   │   └── DemographicDistanceMap.kt # Hard-coded mapping of which CategoryPool values are
│   │   │                                #   "close" (0.15), "distant" (2.5), or "neutral" (1.0)
│   │   │                                #   for each combination of demographic attributes.
│   │   │                                #   Example: ageRange=18-24 + profession=STUDENT →
│   │   │                                #   RETIREMENT=distant, PARENTING=distant,
│   │   │                                #   ACADEMIC=close, GAMING=close.
│   │   │                                #   Uses a rule-based approach, NOT ML inference.
│   │   ├── layer2/
│   │   │   ├── AdversarialScraperLayer.kt # Reads cached platform profiles. Returns: 0.05 for
│   │   │   │                              #   categories the platform has assigned to user,
│   │   │   │                              #   3.0 for categories absent from platform profile,
│   │   │   │                              #   1.0 if scraper is disabled or data is stale.
│   │   │   ├── PlatformProfileCache.kt    # Room @Entity: platformName, scrapedCategories
│   │   │   │                              #   (JSON string of CategoryPool values), lastScraped
│   │   │   │                              #   (timestamp). One row per platform.
│   │   │   ├── PlatformProfileDao.kt      # Room DAO: upsert, getByPlatform, deleteAll
│   │   │   ├── scrapers/
│   │   │   │   ├── PlatformScraper.kt     # Interface: suspend fun scrape(webView): Set<String>
│   │   │   │   ├── GoogleAdsScraper.kt    # Navigates adssettings.google.com/authenticated,
│   │   │   │   │                          #   waits for content load, extracts interest chips
│   │   │   │   │                          #   via JS: document.querySelectorAll('[data-topic]')
│   │   │   │   ├── FacebookAdsScraper.kt  # Navigates facebook.com/adpreferences, extracts
│   │   │   │   │                          #   interest categories from the Ad Topics section
│   │   │   │   └── CategoryMapper.kt      # Maps raw platform strings ("Video Games",
│   │   │   │                              #   "Software Development") to CategoryPool enums
│   │   │   │                              #   using fuzzy keyword matching + manual override map
│   │   │   └── ScrapeScheduler.kt         # Triggers scrapes once/week (configurable).
│   │   │                                  #   Uses WorkManager PeriodicWorkRequest.
│   │   │                                  #   On failure: logs error, keeps stale cache, Layer 2
│   │   │                                  #   falls back to all-1.0 weights gracefully.
│   │   ├── layer3/
│   │   │   ├── PersonaRotationLayer.kt   # Generates a new SyntheticPersona every 7±3 days.
│   │   │   │                             #   Returns: 2.0 for persona-aligned categories,
│   │   │   │                             #   0.3 for persona-misaligned, 1.0 otherwise.
│   │   │   │                             #   70% persona-following / 30% uniform blend.
│   │   │   ├── PersonaGenerator.kt       # Builds a coherent SyntheticPersona by sampling from
│   │   │   │                             #   high-weight categories (after L1+L2) and combining
│   │   │   │                             #   into a consistent demographic: age, profession,
│   │   │   │                             #   location, 3-5 interests. Validates internal
│   │   │   │                             #   consistency (no "retiree + college student").
│   │   │   ├── PersonaConsistencyRules.kt # Rule set for persona validation: incompatible
│   │   │   │                             #   trait pairs, required co-occurrences, age-gated
│   │   │   │                             #   interests. Returns isValid(persona): Boolean.
│   │   │   └── PersonaHistoryDao.kt      # Room DAO tracking past personas. Generator checks
│   │   │                                 #   history to avoid repeating similar profiles within
│   │   │                                 #   a 90-day window. Stores: persona JSON + timestamp.
│   │   └── WeightNormalizer.kt           # Utility: takes Map<CategoryPool, Float>, normalizes
│   │                                     #   so values sum to 1.0. Clamps minimum weight to
│   │                                     #   0.001 (never truly zero — absence is a signal).
│   ├── engine/
│   │   ├── PoisonEngine.kt              # Core orchestrator: reads PoisonProfile, dispatches
│   │   │                                #   work to module executors, manages scheduling via
│   │   │                                #   Poisson-distributed timers, respects battery/wifi
│   │   ├── modules/
│   │   │   ├── SearchPoisonModule.kt    # Executes search queries across Google/Bing/DDG/Yahoo,
│   │   │   │                            #   follows 1-3 result links with random dwell (2-30s)
│   │   │   ├── AdPollutionModule.kt     # Loads ad-heavy pages in background WebView, clicks
│   │   │   │                            #   ads at sub-1% CTR, visits ad preference dashboards
│   │   │   ├── LocationSpoofModule.kt   # Manages MockLocationProvider lifecycle, feeds
│   │   │   │                            #   coordinates from FakeRouteGenerator
│   │   │   ├── FingerprintModule.kt     # Rotates User-Agent, injects canvas noise JS,
│   │   │   │                            #   randomizes Accept-Language, resets Ad ID
│   │   │   ├── CookieSaturationModule.kt# Visits URLs from CrawlListManager in isolated
│   │   │   │                            #   WebView, accumulates diverse tracker cookies
│   │   │   ├── AppSignalModule.kt       # Opens deep links and app store pages for off-profile
│   │   │   │                            #   apps to trigger attribution pixel fires
│   │   │   └── DnsNoiseModule.kt        # Resolves diverse domain names to generate DNS query
│   │   │                                #   noise visible to ISP/network-level trackers
│   │   ├── webview/
│   │   │   ├── PhantomWebViewPool.kt    # Manages a pool of 2-3 reusable WebView instances
│   │   │   │                            #   with process isolation and separate cookie stores
│   │   │   ├── PhantomWebViewClient.kt  # Custom WebViewClient: blocks dangerous content types,
│   │   │   │                            #   injects fingerprint-noise JS, handles SSL errors
│   │   │   └── JSInjector.kt            # JavaScript payloads for canvas noise, font enum
│   │   │                                #   spoofing, navigator property overrides
│   │   └── scheduling/
│   │       ├── PoissonScheduler.kt      # Generates next-action timestamps using Poisson
│   │       │                            #   distribution, with human-like circadian patterns
│   │       │                            #   (active 7am-11pm, quiet overnight)
│   │       └── ActionDispatcher.kt      # Picks next module AND category to execute.
│   │                                    #   Consumes TargetingEngine.getWeights() flow to get
│   │                                    #   current category weight map. Performs weighted random
│   │                                    #   sampling to select a CategoryPool value, then
│   │                                    #   dispatches to the appropriate enabled module.
│   │                                    #   Respects module enable flags independently of
│   │                                    #   category weights.
│   ├── service/
│   │   ├── PhantomForegroundService.kt  # ForegroundService with persistent notification
│   │   │                                #   showing: status (active/paused), actions today,
│   │   │                                #   current intensity. Hosts the PoisonEngine.
│   │   └── BootReceiver.kt             # BroadcastReceiver to restart service after reboot
│   ├── network/
│   │   ├── HeaderRandomizerInterceptor.kt # OkHttp interceptor: rotates User-Agent from a
│   │   │                                  #   pool of 200+ real-world UA strings, randomizes
│   │   │                                  #   Accept-Language, Accept-Encoding variations
│   │   └── UserAgentPool.kt              # Curated list of real UA strings across Chrome,
│   │                                      #   Firefox, Samsung Browser, various Android versions
│   └── ui/
│       ├── MainActivity.kt              # Single activity, Compose-based
│       ├── navigation/
│       │   └── NavGraph.kt              # Bottom nav: Dashboard, Targeting, Modules, Log, Settings
│       │                                #   Plus OnboardingScreen shown once on first launch
│       ├── screens/
│       │   ├── DashboardScreen.kt       # Shows: protection status (on/off toggle),
│       │   │                            #   actions today/this week (animated counter),
│       │   │                            #   per-module activity sparkline charts,
│       │   │                            #   estimated "noise ratio" indicator,
│       │   │                            #   current persona card (name, age, interests),
│       │   │                            #   category distribution donut chart showing
│       │   │                            #   how noise is spread across categories
│       │   ├── OnboardingScreen.kt      # Optional first-launch demographic self-report flow.
│       │   │                            #   Screens: Welcome → Age Range → Gender → Interests
│       │   │                            #   (multi-select chips) → Profession → Region → Done.
│       │   │                            #   Every screen has a prominent "Skip" button.
│       │   │                            #   Explains clearly: "This data stays on your device
│       │   │                            #   and helps us generate noise that's different from
│       │   │                            #   your real profile. Skip if you prefer uniform noise."
│       │   │                            #   Saves to UserDemographicProfile via Room.
│       │   ├── TargetingScreen.kt       # Visualizes the targeting engine state:
│       │   │                            #   - Toggle Layer 1 (self-report) on/off, edit profile
│       │   │                            #   - Toggle Layer 2 (adversarial scraper) on/off,
│       │   │                            #     shows last scrape date per platform, manual
│       │   │                            #     "Scrape Now" button, auth status indicators
│       │   │                            #   - Toggle Layer 3 (persona rotation) on/off,
│       │   │                            #     shows current persona card, "Rotate Now" button
│       │   │                            #   - Live weight visualization: horizontal bar chart
│       │   │                            #     showing current weight per category, color-coded
│       │   │                            #     (red=suppressed, green=boosted, gray=neutral)
│       │   │                            #   - "Clear My Profile" destructive button with confirm
│       │   ├── ModulesScreen.kt         # Toggle each poison module on/off individually,
│       │   │                            #   configure per-module settings (e.g., which search
│       │   │                            #   engines, location spoof mode, query categories)
│       │   ├── LogScreen.kt             # Scrollable, filterable audit log of all actions
│       │   │                            #   with timestamp, type icon, and detail text.
│       │   │                            #   Export button (CSV/JSON).
│       │   └── SettingsScreen.kt        # Global settings: intensity slider (Low/Med/High),
│       │       							 #   wifi-only toggle, battery threshold slider,
│       │       							 #   active hours range picker, clear all data button,
│       │       							 #   about/licenses page
│       └── theme/
│           └── Theme.kt                 # Dark theme with green/cyan accent (hacker aesthetic)
├── src/main/assets/
│   ├── query_banks/                     # JSON files per category with 500+ queries each
│   ├── crawl_urls.json                  # 10,000+ categorized URLs for cookie saturation
│   ├── user_agents.json                 # 200+ real-world User-Agent strings
│   ├── city_coords.json                 # 500+ city coordinates for location spoofing
│   ├── blocklist.json                   # Blocked domains and query terms
│   ├── demographic_distance_rules.json  # Rule-based mapping: for each (ageRange, gender,
│   │                                    #   profession, region) tuple, lists which CategoryPool
│   │                                    #   values are CLOSE (0.15), DISTANT (2.5), or NEUTRAL.
│   │                                    #   Example: {"ageRange":"18-24","profession":"STUDENT",
│   │                                    #   "close":["ACADEMIC","GAMING"],"distant":["RETIREMENT",
│   │                                    #   "PARENTING","AGRICULTURE"]}
│   ├── platform_category_map.json       # Maps raw ad platform strings to CategoryPool enums.
│   │                                    #   Example: {"Video Games":"GAMING","Software":"TECHNOLOGY",
│   │                                    #   "Baby Products":"PARENTING"}
│   └── persona_templates.json           # Seed templates for PersonaGenerator: 50+ base persona
│                                        #   archetypes with consistent trait combinations.
│                                        #   Example: {"archetype":"rural_retiree","ageRange":"65+",
│                                        #   "interests":["AGRICULTURE","COOKING","PETS"],
│                                        #   "region":"US_MIDWEST","profession":"RETIRED"}
└── src/main/res/
    └── (standard Android resources, launcher icon, notification icons)

IMPLEMENTATION PRIORITIES (build in this order):
1. Project scaffolding: Gradle setup, Hilt, Room database, Theme, Navigation
2. TargetingEngine + UniformEntropyLayer + WeightNormalizer + CategoryPool enum
   (this is the foundation — every module needs category weights before it can act)
3. PoisonEngine + PoissonScheduler + ActionDispatcher + ForegroundService (core loop,
   consumes TargetingEngine.getWeights() to decide what category each action targets)
4. SearchPoisonModule + MarkovQueryGenerator (highest-impact module, now category-aware:
   selects query bank based on weighted category from ActionDispatcher)
5. CookieSaturationModule + PhantomWebViewPool (second-highest impact, URL selection
   weighted by category from crawl_urls.json category tags)
6. OnboardingScreen + SelfReportLayer + UserDemographicProfile + DemographicDistanceMap
   (Layer 1 targeting — user can now opt in to directed noise)
7. FingerprintModule + HeaderRandomizerInterceptor
8. LocationSpoofModule + FakeRouteGenerator (location selection weighted by Layer 1
   region data — spoof to regions DIFFERENT from user's reported region)
9. PersonaRotationLayer + PersonaGenerator + PersonaConsistencyRules + PersonaHistoryDao
   (Layer 3 — adds temporal coherence to noise patterns)
10. AdversarialScraperLayer + GoogleAdsScraper + FacebookAdsScraper + CategoryMapper
    (Layer 2 — most complex targeting layer, requires WebView auth flow)
11. AdPollutionModule + AppSignalModule
12. DnsNoiseModule
13. DashboardScreen with live stats + category distribution chart + persona card
14. TargetingScreen with layer toggles + weight visualization + profile management
15. LogScreen with export
16. ModulesScreen with per-module config
17. SettingsScreen with all global controls

KEY BEHAVIORAL REQUIREMENTS:
- All HTTP requests must have a minimum 5-second delay between hits to any single domain.
  Enforce this in CrawlListManager with a per-domain timestamp map.
- The PoissonScheduler must produce human-like timing: bursts of 3-7 actions close together,
  then gaps of 5-20 minutes, with near-zero activity between 11pm-7am local time.
- WebView instances must run with: JavaScript enabled, third-party cookies accepted,
  DOM storage enabled, separate cookie stores from the user's real browser.
- All actions must be logged to Room via ActionLogDao before execution (write-ahead logging).
- The ForegroundService notification must update every 60 seconds with action count.
- Location spoofing must check for developer options enabled and guide the user through
  enabling "Select mock location app" if not configured.
- Every module must implement a common Module interface with: start(), stop(),
  isEnabled(): Boolean, and onAction(category: CategoryPool): ActionLogEntity.
  The category parameter comes from ActionDispatcher's weighted sampling.

TARGETING ENGINE REQUIREMENTS:
- TargetingEngine must expose a Flow<Map<CategoryPool, Float>> that all modules observe.
  Weight map recalculates reactively when: user edits profile (L1), scraper returns new
  data (L2), or persona rotates (L3). Use Kotlin combine() on layer flows.
- Multiplicative weight combination: finalWeight = L0 × L1 × L2 × L3, then normalize.
  Minimum clamped weight: 0.001 (never zero — absence of a category is itself a signal).
- Layer 1 (SelfReportLayer): All fields in UserDemographicProfile are nullable. If a field
  is null, that dimension contributes 1.0 (neutral) for all categories. Load distance rules
  from assets/demographic_distance_rules.json at init.
- Layer 2 (AdversarialScraperLayer): Scrape triggers via WorkManager PeriodicWorkRequest,
  default period 7 days. Each scraper gets a 30-second timeout. On ANY failure (timeout,
  DOM change, auth required), log the error, keep the existing cache, and return all-1.0
  weights. NEVER crash or block the engine on scraper failure.
- Layer 2 scrapers must use the SAME PhantomWebViewPool as other modules but in a SEPARATE
  tagged WebView instance to avoid cookie contamination between scraping and poisoning.
- Layer 3 (PersonaRotationLayer): Persona rotation interval = 7 days ± uniform random
  [1,3] days of jitter. PersonaGenerator must check PersonaHistoryDao and reject any
  persona that shares >60% of trait overlap with any persona used in the last 90 days.
- CategoryMapper (Layer 2) must be forgiving: if a platform string doesn't match any
  CategoryPool value via the JSON map, try fuzzy keyword matching (contains check on
  category name). If still no match, log it and skip (don't crash).
- DemographicDistanceMap must NEVER attempt to infer or use: race, ethnicity, religion,
  sexual orientation, gender identity beyond the self-reported enum, disability status,
  or political affiliation. These attributes must not appear in the distance rules JSON.
- OnboardingScreen must present every question with a visible "Skip" button that is
  visually equal in prominence to the "Next" button. The app must function identically
  (using Layer 0 uniform weights) if the user skips every question.
- "Clear My Profile" in Settings/TargetingScreen must: delete UserDemographicProfile row,
  delete all PlatformProfileCache rows, delete all PersonaHistory rows, reset
  TargetingEngine to Layer 0 only. Single Room transaction, immediate effect.

SAFETY REQUIREMENTS (non-negotiable):
- DomainBlocklist must be checked before ANY URL is loaded. Include patterns for known
  illegal content domains. Reject any URL matching the blocklist silently.
- Query banks must be audited — no queries that would surface illegal content.
- Rate limiting is mandatory: max 1 request per 5 seconds per domain, max 200 total
  requests per hour in HIGH mode.
- The app must never interfere with the user's foreground activity or real browsing.
- All WebView work happens in background with WEBVIEW_CHROMIUM_PROVIDER flags.
- UserDemographicProfile data must NEVER leave the device. It must not appear in any
  HTTP request, URL parameter, log uploaded to a server, or analytics event. Enforce
  this with a compile-time lint rule or code review checklist.
- The Room database tables containing UserDemographicProfile and PlatformProfileCache
  must use SQLCipher encryption with an AndroidKeyStore-backed key.
- DemographicDistanceMap must not contain rules for sensitive attributes (race, ethnicity,
  religion, sexual orientation, disability, political affiliation). Validate this in CI
  by parsing demographic_distance_rules.json and asserting no forbidden keys exist.
- Adversarial scrapers must ONLY read the user's existing ad settings pages. They must
  never modify settings, click ads, or interact with any platform UI beyond reading.

TESTING:
- Unit tests for TargetingEngine weight combination: verify multiplicative formula,
  verify normalization sums to 1.0, verify minimum clamp at 0.001
- Unit tests for SelfReportLayer: given a known demographic profile, verify correct
  categories get 0.15/2.5/1.0 weights per DemographicDistanceMap rules
- Unit tests for AdversarialScraperLayer: given a mock set of platform-assigned categories,
  verify correct 0.05/3.0/1.0 weight assignment
- Unit tests for PersonaGenerator: verify personas pass PersonaConsistencyRules,
  verify no >60% overlap with recent history, verify all trait fields populated
- Unit tests for CategoryMapper: verify known platform strings map correctly,
  verify unknown strings fall back gracefully without crash
- Unit tests for WeightNormalizer: edge cases (all zeros → uniform, single category,
  negative weights rejected)
- Unit tests for PoissonScheduler distribution validation
- Unit tests for MarkovQueryGenerator output plausibility
- Unit tests for FakeRouteGenerator velocity constraints
- Integration test for CrawlListManager rate limiting
- Integration test for ActionDispatcher: over 10,000 samples, verify category selection
  distribution matches weight map within 5% tolerance (chi-squared test)
- Integration test for full TargetingEngine: set up L1+L2+L3, verify combined weights
  heavily suppress matching categories and boost distant ones
- UI tests for OnboardingScreen: verify skip flow works, verify all fields optional
- UI tests for TargetingScreen: verify layer toggles, verify weight chart updates
- UI tests for Dashboard and Settings screens

Write clean, well-documented Kotlin. Use KDoc on every public class and function.
Prefer composition over inheritance. Use Kotlin coroutines and Flow throughout.
Make every configurable value a constant in a companion object or a Room-backed preference.
