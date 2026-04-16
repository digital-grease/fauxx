# Google Play Permissions Declarations

Reference text for completing restricted-permission declarations in the Play Console.

## REQUEST_IGNORE_BATTERY_OPTIMIZATIONS

**Core functionality:** Fauxx is a privacy tool that continuously generates synthetic browsing activity to make the user's real interest profile harder to infer from behavioral data. The engine must run on a stable, human-like Poisson cadence (3-7 action bursts, 5-20 minute gaps, 7am-11pm circadian window) that cannot be reproduced by short-lived WorkManager jobs or JobScheduler tasks.

**Why battery-optimization exemption is required:** Aggressive OEM battery managers on Samsung, Xiaomi, Huawei, OnePlus, Oppo, and Vivo devices kill foreground services within minutes of the launcher being swiped away. When the service dies, no synthetic activity is generated; the user's real browsing signal reappears and the privacy protection the user installed the app for silently stops working.

**User flow:** The app asks for battery-optimization exemption only:
- After the user has actively enabled the privacy engine from the Dashboard
- Via the system-provided `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent (system dialog, not a custom dialog masquerading as system UI)
- With a plain-language in-app explainer (`BatteryOptimizationDialog`) describing why protection needs the exemption
- As an optional step — the user can dismiss without granting and the app continues to run, at reduced reliability

**Fallback when permission is denied:** The engine still runs and still starts its foreground service; it will simply be killed by the OEM sooner. The app remains functional.

**Declaration category:** Privacy, Security, or Anti-Virus app — uninterrupted background execution is the documented load-bearing feature.

**Relevant Play policy:** "If the app's core functionality is dependent on the background service working without interruption and/or the device's performance would be significantly impacted without this permission." — Fauxx qualifies on both counts.

## FOREGROUND_SERVICE_SPECIAL_USE (Play flavor)

**Service:** `com.fauxx.service.PhantomForegroundService`

**Declared subtype** (from `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` in `src/play/AndroidManifest.xml`):

> Generates synthetic, off-profile browsing activity continuously in the background to dilute user-identifying signal available to third-party data brokers and ad-tech trackers. The service cannot be an instant-off/on worker because its privacy value depends on maintaining a stable, human-like traffic rhythm (Poisson-distributed cadence, 5-20 minute gaps, circadian quiet hours) that short-lived tasks cannot sustain.

**Why not `dataSync`:** `dataSync` is defined by Google as user-initiated transfer of the user's own data to/from the cloud (syncing contacts, uploading photos, etc.). Fauxx does not sync any of the user's own data; it generates synthetic network activity to protect privacy. `specialUse` is the only accurate type match.

**Why not short-lived `WorkManager` jobs:** The privacy value is continuity. A bursty worker pattern is trivially detectable as non-human, and the 15-minute minimum interval of `PeriodicWorkRequest` is too long to maintain realistic human browsing rhythms.
