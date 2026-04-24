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

### Play Console text box — paste verbatim

The Play Console field prompt reads: *"Describe your app's use of this permission, including why the task must start immediately and cannot be paused or restarted."* Paste the block below:

> Fauxx is a privacy tool that protects users from behavioral profiling by data brokers and ad-tech trackers. The FOREGROUND_SERVICE_SPECIAL_USE permission lets the app host `PhantomForegroundService`, which continuously generates synthetic, off-profile browsing activity to dilute the user's identifiable behavioral signal.
>
> Why the task must start immediately: the privacy protection is only effective while the service is actively generating noise. From the moment the user enables protection, any delay is a window in which the user's real browsing signal remains undiluted and continues to be harvested by trackers. There is no meaningful way to queue privacy protection for later — either synthetic traffic is in-stream right now, or it is not.
>
> Why the task cannot be paused or restarted: the privacy value depends on temporal continuity. The engine dispatches actions on a Poisson-distributed cadence — 3–7 action bursts separated by 5–20 minute gaps, constrained to a 7am–11pm circadian window — so that synthetic activity is statistically indistinguishable from real human browsing. Pausing and restarting the service interrupts this rhythm and produces gaps that are trivially detectable as machine-generated, which defeats the protection. A WorkManager PeriodicWorkRequest is unsuitable for the same reason: its 15-minute minimum interval is too coarse to sustain the required inter-arrival distribution, and its bursty worker pattern creates signatures that trackers can filter out. The service runs only while the user has explicitly enabled protection, shows a persistent notification for the entire duration, and can be stopped at any time from a notification action or the Dashboard toggle.

### Declared subtype (stored in `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`, `src/play/AndroidManifest.xml`)

> Generates synthetic, off-profile browsing activity continuously in the background to dilute user-identifying signal available to third-party data brokers and ad-tech trackers. The service cannot be an instant-off/on worker because its privacy value depends on maintaining a stable, human-like traffic rhythm (Poisson-distributed cadence, 5-20 minute gaps, circadian quiet hours) that short-lived tasks cannot sustain.

### Why not other types (for reviewer follow-up questions)

**Why not `dataSync`:** `dataSync` is defined by Google as user-initiated transfer of the user's own data to/from the cloud (syncing contacts, uploading photos, etc.). Fauxx does not sync any of the user's own data; it generates synthetic network activity to protect privacy. `specialUse` is the only accurate type match.

**Why not short-lived `WorkManager` jobs:** The privacy value is continuity. A bursty worker pattern is trivially detectable as non-human, and the 15-minute minimum interval of `PeriodicWorkRequest` is too long to maintain realistic human browsing rhythms.

### Video demonstration

A short video demonstrating the foreground service behavior is submitted alongside this declaration in the Play Console form.
