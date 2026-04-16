package com.fauxx.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fauxx.BuildConfig
import com.fauxx.R

/**
 * About & Privacy Policy screen accessible from Settings.
 * Explains what data stays on-device, what network requests are made,
 * and that no telemetry or analytics are collected.
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
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
                text = stringResource(R.string.about_title),
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
                stringResource(R.string.about_app_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Privacy Policy
        AboutCard {
            SectionTitle(stringResource(R.string.about_privacy_title))
            Spacer(Modifier.height(8.dp))

            SectionSubtitle(stringResource(R.string.about_data_on_device_title))
            BodyText(stringResource(R.string.about_data_on_device_body))

            Spacer(Modifier.height(12.dp))

            SectionSubtitle(stringResource(R.string.about_network_title))
            BodyText(stringResource(R.string.about_network_body))

            Spacer(Modifier.height(12.dp))

            SectionSubtitle(stringResource(R.string.about_not_collected_title))
            BodyText(stringResource(R.string.about_not_collected_body))

            Spacer(Modifier.height(12.dp))

            SectionSubtitle(stringResource(R.string.about_location_title))
            BodyText(stringResource(R.string.about_location_body))

            Spacer(Modifier.height(12.dp))

            SectionSubtitle(stringResource(R.string.about_deletion_title))
            BodyText(stringResource(R.string.about_deletion_body))
        }

        // Play Store flavor: full-version notice with F-Droid / GitHub links.
        if (BuildConfig.FLAVOR == "play") {
            val fdroidUrl = stringResource(R.string.full_version_fdroid_url)
            val githubUrl = stringResource(R.string.full_version_github_url)
            AboutCard {
                SectionTitle(stringResource(R.string.full_version_notice_title))
                Spacer(Modifier.height(8.dp))
                BodyText(stringResource(R.string.full_version_notice_body))
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fdroidUrl)))
                    }) {
                        Text(
                            stringResource(R.string.full_version_notice_fdroid),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl)))
                    }) {
                        Text(
                            stringResource(R.string.full_version_notice_github),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // License
        AboutCard {
            SectionTitle(stringResource(R.string.about_license_title))
            Spacer(Modifier.height(8.dp))
            BodyText(stringResource(R.string.about_license_body))
        }

        // Support
        AboutCard {
            SectionTitle(stringResource(R.string.about_support_title))
            Spacer(Modifier.height(8.dp))
            BodyText(stringResource(R.string.about_support_body))
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.buymeacoffee.com/digitalgrease"))
                )
            }) {
                Text(
                    stringResource(R.string.about_support_button),
                    fontFamily = FontFamily.Monospace
                )
            }
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

@Composable
private fun BodyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
}
