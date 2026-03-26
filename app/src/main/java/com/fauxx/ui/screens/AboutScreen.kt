package com.fauxx.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fauxx.BuildConfig

/**
 * About & Privacy Policy screen accessible from Settings.
 * Explains what data stays on-device, what network requests are made,
 * and that no telemetry or analytics are collected.
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = "ABOUT & PRIVACY",
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // App info
        AboutCard {
            Text(
                "Fauxx",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace
            )
            Text(
                "v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "An open-source privacy tool that poisons data broker and ad-tech profiles " +
                    "by generating continuous, plausible, off-demographic synthetic activity " +
                    "from your device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Privacy Policy
        AboutCard {
            SectionTitle("Privacy Policy")
            Spacer(Modifier.height(8.dp))

            SectionSubtitle("Data that stays on your device")
            Text(
                "All personal data remains exclusively on your device and is encrypted at rest:\n" +
                    "\u2022 Demographic profile (age, gender, interests, profession, region)\n" +
                    "\u2022 Ad platform profile cache (scraped interest categories)\n" +
                    "\u2022 Synthetic persona history\n" +
                    "\u2022 Action audit logs\n\n" +
                    "This data is stored in an encrypted database (SQLCipher) with a key " +
                    "managed by Android Keystore. It never leaves your device in any form.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(12.dp))

            SectionSubtitle("Network requests the app makes")
            Text(
                "Fauxx generates synthetic browsing activity to confuse tracking profiles. " +
                    "This means the app will:\n\n" +
                    "\u2022 Perform web searches on Google, Bing, DuckDuckGo, and Yahoo\n" +
                    "\u2022 Visit categorized URLs to accumulate diverse tracker cookies\n" +
                    "\u2022 Load ad-heavy pages in background web views\n" +
                    "\u2022 Resolve domain names to generate DNS query noise\n" +
                    "\u2022 Open app store deep links for off-profile apps\n\n" +
                    "All requests use randomized User-Agent headers and are rate-limited " +
                    "to avoid disrupting target services. Blocked domains (private networks, " +
                    "harmful content) are never contacted.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(12.dp))

            SectionSubtitle("What we do NOT collect")
            Text(
                "\u2022 No analytics or telemetry of any kind\n" +
                    "\u2022 No crash reports sent to external services\n" +
                    "\u2022 No advertising identifiers or tracking pixels\n" +
                    "\u2022 No server-side accounts or cloud storage\n" +
                    "\u2022 No data shared with third parties\n\n" +
                    "Fauxx has no backend server. The app is entirely self-contained.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(12.dp))

            SectionSubtitle("Location spoofing")
            Text(
                "If enabled, the Location Spoofing module feeds fake GPS coordinates to " +
                    "apps on your device using Android's mock location provider API. This " +
                    "requires enabling Developer Options and selecting Fauxx as the mock " +
                    "location app. Fake coordinates are generated along plausible routes " +
                    "in regions different from your reported location.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(12.dp))

            SectionSubtitle("Data deletion")
            Text(
                "You can delete all stored data at any time via Settings > Clear All Data. " +
                    "This permanently removes your demographic profile, platform caches, " +
                    "persona history, action logs, and resets all settings to defaults. " +
                    "Uninstalling the app also removes all data.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // License
        AboutCard {
            SectionTitle("License")
            Spacer(Modifier.height(8.dp))
            Text(
                "Fauxx is open-source software. Source code and license details are " +
                    "available in the project repository.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AboutCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun SectionSubtitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.secondary
    )
    Spacer(Modifier.height(4.dp))
}
