package com.fauxx.ui.screens

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.Context
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.fauxx.R
import com.fauxx.engine.modules.LocationDiagnostics
import com.fauxx.ui.viewmodels.ModulesViewModel

/**
 * Module configuration screen: toggle each poison module on/off individually.
 */
@Composable
fun ModulesScreen(
    viewModel: ModulesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val locationFailure by viewModel.locationStartFailure.collectAsState()
    var showLocationSetupHint by remember { mutableStateOf(false) }

    // #202: the mock-location AppOp is only checked when the location module (re)starts, so setting
    // Fauxx as the "Select mock location app" in Developer Options while Location is already ON is
    // not picked up live. Re-check on every return to this screen (e.g. back from the Developer
    // Options deep-link) so the green/red banner refreshes without an engine restart.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshLocationStatus()
    }

    if (showLocationSetupHint) {
        LocationSetupHintDialog(onDismiss = { showLocationSetupHint = false })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.modules_title),
            style = MaterialTheme.typography.titleLarge,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        ModuleToggleCard(
            name = stringResource(R.string.module_search_name),
            description = stringResource(R.string.module_search_description),
            enabled = uiState.searchEnabled,
            onToggle = { viewModel.setSearchEnabled(it) }
        )
        ModuleToggleCard(
            name = stringResource(R.string.module_cookie_name),
            description = stringResource(R.string.module_cookie_description),
            enabled = uiState.cookieEnabled,
            onToggle = { viewModel.setCookieEnabled(it) }
        )
        ModuleToggleCard(
            name = stringResource(R.string.module_dns_name),
            description = stringResource(R.string.module_dns_description),
            enabled = uiState.dnsEnabled,
            onToggle = { viewModel.setDnsEnabled(it) }
        )
        ModuleToggleCard(
            name = stringResource(R.string.module_fingerprint_name),
            description = stringResource(R.string.module_fingerprint_description),
            enabled = uiState.fingerprintEnabled,
            onToggle = { viewModel.setFingerprintEnabled(it) }
        )
        ModuleToggleCard(
            name = stringResource(R.string.module_ad_name),
            description = stringResource(R.string.module_ad_description),
            enabled = uiState.adEnabled,
            onToggle = { viewModel.setAdEnabled(it) }
        )
        ModuleToggleCard(
            name = stringResource(R.string.module_location_name),
            description = stringResource(R.string.module_location_description),
            enabled = uiState.locationEnabled,
            onToggle = { enabled ->
                viewModel.setLocationEnabled(enabled)
                // Only surface the setup-hint dialog when setup hasn't already been
                // done. Issue #4 originally added this dialog because users were
                // toggling and seeing no effect; once they've done the Developer
                // Options dance, nagging them again on every toggle is a regression.
                if (enabled && !viewModel.isLocationReadyForUse()) {
                    showLocationSetupHint = true
                }
            },
            // Pre-toggle informational hint only — once the toggle is on, the banner
            // below replaces this text with a real success-or-failure signal so users
            // aren't perpetually shown a "warning" when everything's actually fine.
            hint = if (!uiState.locationEnabled) {
                stringResource(R.string.module_location_setup_hint)
            } else null
        )
        // Inline post-mortem of the most recent start() attempt. Issue #48: users
        // had no way to know location spoofing was silently doing nothing. Now we
        // show either a green success banner (start() succeeded) or a red failure
        // banner with a tailored remediation hint.
        if (uiState.locationEnabled) {
            when (locationFailure) {
                LocationDiagnostics.StartFailure.OK -> LocationSuccessBanner()
                LocationDiagnostics.StartFailure.NEVER_STARTED -> {
                    // Transient pre-engine state on cold launch — show nothing
                    // rather than flash a temporary banner.
                }
                else -> LocationFailureBanner(failure = locationFailure)
            }
        }
        ModuleToggleCard(
            name = stringResource(R.string.module_appsignal_name),
            description = stringResource(R.string.module_appsignal_description),
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
    hint: String? = null
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
                if (hint != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

/**
 * Shown when the user enables Location Spoofing. Tells them the *additional*
 * Android-side setup they need to do — declaring `ACCESS_MOCK_LOCATION` (issue #4
 * fix) is necessary but not sufficient; the user must also designate Fauxx as the
 * mock location app in Developer Options. Without this dialog, the toggle just
 * silently fails inside `LocationSpoofModule.start()`.
 */
@Composable
private fun LocationSetupHintDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.module_location_setup_dialog_title)) },
        text = { Text(stringResource(R.string.module_location_setup_dialog_body)) },
        confirmButton = {
            TextButton(onClick = {
                openDeveloperOptionsOrSettings(context)
                onDismiss()
            }) { Text(stringResource(R.string.action_open_developer_options)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_got_it)) }
        }
    )
}

/**
 * `Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS` only resolves on devices where
 * Developer Options has been unlocked (Settings → About phone → tap Build number 7×).
 * On fresh devices it silently fails, leaving the user stuck. This helper checks the
 * `DEVELOPMENT_SETTINGS_ENABLED` flag first and falls back to About phone (where the
 * Build-number tap-counter lives) when dev options aren't unlocked yet — and finally
 * to generic Settings if even that doesn't resolve.
 */
private fun openDeveloperOptionsOrSettings(context: Context) {
    val devOptionsEnabled = Settings.Global.getInt(
        context.contentResolver,
        Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
        0
    ) == 1
    val candidates = buildList {
        if (devOptionsEnabled) add(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        add(Settings.ACTION_DEVICE_INFO_SETTINGS) // About phone (where Build number lives)
        add(Settings.ACTION_SETTINGS) // root Settings, always present
    }
    for (action in candidates) {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) != null) {
            runCatching { context.startActivity(intent) }
            return
        }
    }
}

/**
 * Banner shown when the user has Location Spoofing enabled AND the most recent
 * `LocationSpoofModule.start()` succeeded — i.e. Fauxx is actually feeding mock
 * fixes. Replaces the perpetual "warning" copy that used to live on the toggle
 * card itself, so a working setup gets a clear positive signal instead of a
 * red string that made it look like something was wrong.
 */
@Composable
private fun LocationSuccessBanner() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.module_location_success_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.module_location_success_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

/**
 * Banner surfaced under the Location Spoofing toggle when [LocationDiagnostics] reports
 * the most-recent start() attempt failed. Each failure mode gets a tailored message and
 * (where applicable) a deep-link to Developer Options.
 */
@Composable
private fun LocationFailureBanner(failure: LocationDiagnostics.StartFailure) {
    val context = LocalContext.current
    val (headline, detail, showDevOptions) = when (failure) {
        LocationDiagnostics.StartFailure.NOT_MOCK_APP -> Triple(
            stringResource(R.string.module_location_failure_not_mock_app_title),
            stringResource(R.string.module_location_failure_not_mock_app_body),
            true
        )
        LocationDiagnostics.StartFailure.SECURITY_EXCEPTION -> Triple(
            stringResource(R.string.module_location_failure_security_title),
            stringResource(R.string.module_location_failure_security_body),
            true
        )
        LocationDiagnostics.StartFailure.RUNTIME_EXCEPTION -> Triple(
            stringResource(R.string.module_location_failure_runtime_title),
            stringResource(R.string.module_location_failure_runtime_body),
            false
        )
        else -> Triple("", "", false)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = headline,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            if (showDevOptions) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { openDeveloperOptionsOrSettings(context) }) {
                    Text(stringResource(R.string.action_open_developer_options))
                }
            }
        }
    }
}
