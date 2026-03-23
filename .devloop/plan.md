# Devloop Plan: Build Fauxx Android Privacy App

**Created**: 2026-03-22
**Status**: In Progress
**Complexity**: XL

---

## Overview
Build Fauxx — an open-source Android privacy tool that poisons data broker and ad-tech profiles by generating continuous, plausible, off-demographic synthetic activity from the user's device. Full implementation per CLAUDE.md spec.

### Approach
Follow the 17 implementation priorities in order. Build each layer on top of the previous.

---

## Phase 1: Project Scaffolding ✅
- [x] Task 1.1: Create Gradle project structure (settings.gradle.kts, build.gradle.kts, libs.versions.toml)
- [x] Task 1.2: Create AndroidManifest.xml with all required permissions
- [x] Task 1.3: Create FauxxApp.kt (Application class, Hilt entry point)
- [x] Task 1.4: Create di/ modules: AppModule, WorkerModule, NetworkModule, TargetingModule
- [x] Task 1.5: Create Theme.kt (dark hacker aesthetic, Material 3)
- [x] Task 1.6: Create MainActivity.kt + NavGraph.kt (bottom nav)
- [x] Task 1.7: Create data model classes: ActionType, IntensityLevel, PoisonProfile, SyntheticPersona
- [x] Task 1.8: Create Room database: PhantomDatabase, ActionLogDao, ActionLogEntity

## Phase 2: Targeting Engine (Foundation) ✅
- [x] Task 2.1: Create CategoryPool enum (all categories)
- [x] Task 2.2: Create WeightNormalizer utility
- [x] Task 2.3: Create UniformEntropyLayer (Layer 0)
- [x] Task 2.4: Create TargetingEngine orchestrator with Flow<Map<CategoryPool, Float>>
- [x] Task 2.5: Create asset files: demographic_distance_rules.json, platform_category_map.json, persona_templates.json

## Phase 3: Poison Engine Core Loop ✅
- [x] Task 3.1: Create PoissonScheduler with circadian patterns
- [x] Task 3.2: Create ActionDispatcher (weighted sampling from TargetingEngine)
- [x] Task 3.3: Create Module interface
- [x] Task 3.4: Create PoisonEngine orchestrator
- [x] Task 3.5: Create PhantomForegroundService + BootReceiver

## Phase 4: Search Poison Module ✅
- [x] Task 4.1: Create QueryBankManager + CategoryPool asset JSON files (query banks)
- [x] Task 4.2: Create MarkovQueryGenerator
- [x] Task 4.3: Create SearchPoisonModule
- [x] Task 4.4: Create asset files: crawl_urls.json, user_agents.json, city_coords.json, blocklist.json

## Phase 5: WebView Infrastructure + Cookie Module ✅
- [x] Task 5.1: Create PhantomWebViewPool
- [x] Task 5.2: Create PhantomWebViewClient + JSInjector
- [x] Task 5.3: Create DomainBlocklist
- [x] Task 5.4: Create CrawlListManager
- [x] Task 5.5: Create CookieSaturationModule

## Phase 6: Layer 1 — Self Report Targeting ✅
- [x] Task 6.1: Create UserDemographicProfile Room entity + DemographicProfileDao
- [x] Task 6.2: Create DemographicDistanceMap (loads from JSON asset)
- [x] Task 6.3: Create SelfReportLayer
- [x] Task 6.4: Create OnboardingScreen (welcome→age→gender→interests→profession→region→done)

## Phase 7: Fingerprint + Network ✅
- [x] Task 7.1: Create UserAgentPool + HeaderRandomizerInterceptor
- [x] Task 7.2: Create FingerprintModule

## Phase 8: Location Spoofing ✅
- [x] Task 8.1: Create CityDatabase
- [x] Task 8.2: Create FakeRouteGenerator
- [x] Task 8.3: Create LocationSpoofModule

## Phase 9: Layer 3 — Persona Rotation ✅
- [x] Task 9.1: Create PersonaHistoryDao + Room entity
- [x] Task 9.2: Create PersonaConsistencyRules
- [x] Task 9.3: Create PersonaGenerator
- [x] Task 9.4: Create PersonaRotationLayer

## Phase 10: Layer 2 — Adversarial Scraper ✅
- [x] Task 10.1: Create PlatformProfileCache entity + PlatformProfileDao
- [x] Task 10.2: Create CategoryMapper
- [x] Task 10.3: Create PlatformScraper interface + GoogleAdsScraper + FacebookAdsScraper
- [x] Task 10.4: Create ScrapeScheduler
- [x] Task 10.5: Create AdversarialScraperLayer

## Phase 11: Remaining Modules ✅
- [x] Task 11.1: Create AdPollutionModule
- [x] Task 11.2: Create AppSignalModule
- [x] Task 11.3: Create DnsNoiseModule

## Phase 12: UI Screens ✅
- [x] Task 12.1: Create DashboardScreen (stats, charts, persona card)
- [x] Task 12.2: Create TargetingScreen (layer toggles, weight viz)
- [x] Task 12.3: Create LogScreen (audit log, export)
- [x] Task 12.4: Create ModulesScreen (per-module config)
- [x] Task 12.5: Create SettingsScreen (global controls)

## Phase 13: Tests ✅
- [x] Task 13.1: Unit tests for WeightNormalizer, CategoryMapper, PersonaConsistencyRules
- [x] Task 13.2: Unit tests for PoissonScheduler, MarkovQueryGenerator, FakeRouteGenerator
- [x] Task 13.3: ActionDispatcher distribution test
- [x] Task 13.4: UI tests (Android instrumented — require device/emulator)

## ⚠️ Blocked: SettingsViewModel.kt
- Security hook on system blocks writes containing these class name patterns.
  Must be written manually — content provided in chat output.

---

## Progress Log
- 2026-03-22: Plan created
