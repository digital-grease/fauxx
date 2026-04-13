# Fauxx Privacy Policy

**Last updated:** April 2026

## Summary

Fauxx is an open-source privacy tool. All personal data stays on your device. We have no servers, no accounts, and no way to access your data.

## Data That Stays on Your Device

All personal data remains exclusively on your device and is encrypted at rest:

- **Demographic profile** (age, gender, interests, profession, region) — optional, provided during onboarding
- **Ad platform profile cache** (scraped interest categories) — if Layer 2 targeting is enabled
- **Synthetic persona history** — generated fake identities used by the targeting engine
- **Action audit logs** — a record of all synthetic actions performed

This data is stored in an encrypted database (SQLCipher) with a key managed by Android Keystore. It never leaves your device in any form.

## Network Requests the App Makes

Fauxx generates synthetic browsing activity to confuse tracking profiles. This means the app will:

- Perform web searches on Google, Bing, DuckDuckGo, and Yahoo
- Visit categorized URLs to diversify your browsing profile
- Load diverse pages in background web views
- Resolve domain names to generate DNS query noise
- Open app store deep links for off-profile apps

All requests use randomized User-Agent headers and are rate-limited to avoid disrupting target services. Domains on the blocklist (private networks, harmful content) are never contacted.

## What We Do NOT Collect

- No analytics or telemetry of any kind
- No crash reports sent to external services
- No advertising identifiers or tracking pixels
- No server-side accounts or cloud storage
- No data shared with third parties

Fauxx has no backend server. The app is entirely self-contained.

## Location Signal Noise

If enabled, the Location Signal module generates search queries and visits websites that suggest activity in regions different from your actual location, making it harder for data brokers to pinpoint where you live.

## Data Deletion

You can delete all stored data at any time via **Settings > Clear All Data**. This permanently removes your demographic profile, platform caches, persona history, action logs, and resets all settings to defaults. Uninstalling the app also removes all data.

## Permissions

Fauxx requests only the permissions necessary for its privacy protection features:

| Permission | Purpose |
|---|---|
| Internet | Required for generating synthetic browsing activity |
| Network/WiFi state | Enforcing WiFi-only mode setting |
| Foreground service | Keeping the privacy engine running in the background |
| Post notifications | Showing the persistent status notification |
| Boot completed | Optionally restarting protection after device reboot |
| Wake lock | Keeping the engine active during background operation |

## Open Source

Fauxx is open-source software. You can inspect the complete source code to verify these privacy claims.

## Contact

For privacy questions, open an issue on the project's GitHub repository.
