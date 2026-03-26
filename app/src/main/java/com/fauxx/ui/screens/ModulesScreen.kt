package com.fauxx.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
            description = "Feeds synthetic GPS routes via MockLocationProvider",
            enabled = uiState.locationEnabled,
            onToggle = { viewModel.setLocationEnabled(it) },
            warning = "Requires Developer Options enabled and Fauxx selected as mock location app"
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
    onToggle: (Boolean) -> Unit,
    warning: String? = null
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (enabled) Color(0xFF00FF88) else Color(0xFF666666)
                            )
                    )
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (warning != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}
