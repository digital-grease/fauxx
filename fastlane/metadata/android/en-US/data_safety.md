# Data Safety Form Responses

Reference document for completing the Google Play Console Data Safety form.

## Overview

- **Does your app collect or share any of the required user data types?** Yes
- **Is all of the user data collected by your app encrypted in transit?** Yes (HTTPS only, cleartext disabled)
- **Do you provide a way for users to request that their data is deleted?** Yes (Settings > Clear All Data)

## Data Types Collected

### Personal Info

| Data type | Collected | Shared | Purpose | Optional |
|---|---|---|---|---|
| Name | No | — | — | — |
| Email | No | — | — | — |
| Personal identifiers | No | — | — | — |
| Age range | Yes (on-device only) | No | App functionality (demographic targeting) | Yes |
| Gender | Yes (on-device only) | No | App functionality (demographic targeting) | Yes |

### App Activity

| Data type | Collected | Shared | Purpose | Optional |
|---|---|---|---|---|
| App interactions | Yes (on-device only) | No | App functionality (audit log) | No |
| In-app search history | No | — | — | — |
| Other user-generated content | Yes (on-device only) | No | App functionality (custom interests) | Yes |

### Web Browsing

| Data type | Collected | Shared | Purpose | Optional |
|---|---|---|---|---|
| Web browsing history | Yes (on-device only) | No | App functionality (action audit log) | No |

### Device or Other IDs

| Data type | Collected | Shared | Purpose | Optional |
|---|---|---|---|---|
| Device or other IDs | No | — | — | — |

### Location

| Data type | Collected | Shared | Purpose | Optional |
|---|---|---|---|---|
| Approximate location | No | — | — | — |
| Precise location | No | — | — | — |

> Note: The app generates location-suggestive searches but does NOT collect or store
> the user's actual location. The self-reported "region" is a coarse preference
> (e.g., "US_NORTHEAST"), not a GPS coordinate.

## Data Handling Practices

- **Is data encrypted in transit?** Yes — all network requests use HTTPS only
- **Is data encrypted at rest?** Yes — SQLCipher with AndroidKeyStore-backed key
- **Can users request data deletion?** Yes — Settings > Clear All Data
- **Is data processed ephemerally?** Demographic profile is persistent; action logs are persistent but deletable
- **Is data transferred to third parties?** No
- **Does your app use data for advertising?** No
- **Does your app use data for analytics?** No

## Security Practices

- All on-device data encrypted with SQLCipher
- Encryption key stored in Android Keystore (hardware-backed when available)
- No cloud backup (android:allowBackup="false", data_extraction_rules exclude all)
- No cleartext network traffic (usesCleartextTraffic="false")
- Network security config enforces HTTPS with system CAs only
