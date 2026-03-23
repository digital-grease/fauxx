package com.fauxx.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fauxx.ui.viewmodels.ModulesViewModel

/**
 * Module configuration screen: toggle each poison module on/off individually.
 */
@Composable
fun ModulesScreen(
    viewModel: ModulesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "MODULES",
            style = MaterialTheme.typography.titleLarge,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        ModuleToggleCard(
            name = "Search Poisoning",
            description = "Executes search queries across Google, Bing, DuckDuckGo, Yahoo",
            enabled = uiState.searchEnabled,
            onToggle = { viewModel.setSearchEnabled(it) }
        )
        ModuleToggleCard(
            name = "Cookie Saturation",
            description = "Visits 10,000+ URLs in background to accumulate diverse tracker cookies",
            enabled = uiState.cookieEnabled,
            onToggle = { viewModel.setCookieEnabled(it) }
        )
        ModuleToggleCard(
            name = "DNS Noise",
            description = "Resolves diverse domains to generate ISP-visible DNS query noise",
            enabled = uiState.dnsEnabled,
            onToggle = { viewModel.setDnsEnabled(it) }
        )
        ModuleToggleCard(
            name = "Fingerprint Rotation",
            description = "Rotates User-Agent, canvas noise, and navigator properties",
            enabled = uiState.fingerprintEnabled,
            onToggle = { viewModel.setFingerprintEnabled(it) }
        )
        ModuleToggleCard(
            name = "Ad Pollution",
            description = "Loads ad-heavy pages and visits ad preference dashboards",
            enabled = uiState.adEnabled,
            onToggle = { viewModel.setAdEnabled(it) }
        )
        ModuleToggleCard(
            name = "Location Spoofing",
            description = "Feeds synthetic GPS routes via MockLocationProvider (requires dev options)",
            enabled = uiState.locationEnabled,
            onToggle = { viewModel.setLocationEnabled(it) }
        )
        ModuleToggleCard(
            name = "App Signals",
            description = "Opens app store pages for off-profile apps to trigger attribution pixels",
            enabled = uiState.appSignalEnabled,
            onToggle = { viewModel.setAppSignalEnabled(it) }
        )
    }
}

@Composable
private fun ModuleToggleCard(
    name: String,
    description: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}
