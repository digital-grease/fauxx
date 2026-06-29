package com.fauxx.ui.sync

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.fauxx.R
import com.fauxx.sync.discovery.DiscoveredPeer
import com.fauxx.sync.data.PairedPeer
import com.journeyapps.barcodescanner.ScanContract
import com.fauxx.sync.pairing.QrScanOptionsFactory

/**
 * Minimal Compose surface for encrypted LAN persona sync (E13 #178): show/scan the pairing QR,
 * enable/disable the sync session, list paired and discovered peers, and push the current decoy
 * persona. Decoy-only: this never touches real accounts; pairing is the cryptographic gate.
 */
@Composable
fun SyncScreen(viewModel: SyncViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val paired by viewModel.pairedPeers.collectAsState()
    val discovered by viewModel.discoveredPeers.collectAsState()

    var showPasteDialog by remember { mutableStateOf(false) }
    val scanPrompt = stringResource(R.string.sync_scan_prompt)

    // #213: reflect an externally-stopped session (e.g. the notification Stop action) when the
    // user returns to the screen, instead of leaving a stale ON toggle.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshSyncEnabledState()
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { viewModel.completePairing(it) }
    }
    val launchScan = { scanLauncher.launch(QrScanOptionsFactory.pairingScan(scanPrompt)) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchScan() else showPasteDialog = true
    }

    val onScanClicked = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) launchScan() else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.sync_enable_title), style = MaterialTheme.typography.titleMedium)
                Switch(checked = state.syncEnabled, onCheckedChange = { viewModel.setSyncEnabled(it) })
            }
        }

        // #213: most sync failures are silent setup gaps (one-way pairing, sync left off, mDNS
        // blocked). Spell out the steps and the common blockers up front.
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(stringResource(R.string.sync_onboarding_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.sync_onboarding_steps), style = MaterialTheme.typography.bodySmall)
                    Text(
                        stringResource(R.string.sync_onboarding_blockers),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.sync_my_qr_title), style = MaterialTheme.typography.titleSmall)
                    state.qrBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = stringResource(R.string.sync_qr_content_desc),
                            modifier = Modifier.size(240.dp)
                        )
                    }
                    state.fingerprint?.let {
                        Text(
                            stringResource(R.string.sync_fingerprint_label, it),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        stringResource(R.string.sync_qr_hint),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onScanClicked, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.sync_scan_button))
                }
                OutlinedButton(onClick = { showPasteDialog = true }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.sync_paste_button))
                }
            }
        }

        item {
            Button(
                onClick = { viewModel.pushCurrentPersonaToAll() },
                modifier = Modifier.fillMaxWidth(),
                // #213: a push needs both a paired peer AND a running session (routes only exist
                // while sync is enabled), so don't offer the action until both hold.
                enabled = paired.isNotEmpty() && state.syncEnabled
            ) { Text(stringResource(R.string.sync_push_button)) }
        }
        if (paired.isNotEmpty() && !state.syncEnabled) {
            item {
                Text(
                    stringResource(R.string.sync_push_disabled_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        state.statusMessage?.let { msg ->
            item { Text(msg, style = MaterialTheme.typography.bodySmall) }
        }
        state.lastImport?.let { msg ->
            item { Text(msg, style = MaterialTheme.typography.bodySmall) }
        }

        item { HorizontalDivider() }
        item { Text(stringResource(R.string.sync_paired_header), style = MaterialTheme.typography.titleSmall) }
        if (paired.isEmpty()) {
            item { Text(stringResource(R.string.sync_none_yet), style = MaterialTheme.typography.bodySmall) }
        } else {
            items(paired, key = { it.publicKey }) { peer -> PairedPeerRow(peer) { viewModel.revoke(peer) } }
        }

        item { HorizontalDivider() }
        item { Text(stringResource(R.string.sync_discovered_header), style = MaterialTheme.typography.titleSmall) }
        if (discovered.isEmpty()) {
            // #213: distinguish "sync off" from "on but nothing found" instead of a bare "none yet".
            item {
                Text(
                    stringResource(
                        if (state.syncEnabled) R.string.sync_discovered_empty_searching
                        else R.string.sync_discovered_disabled
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(discovered, key = { it.name }) { peer -> DiscoveredPeerRow(peer) }
        }

        // #213: manual IP fallback for networks where mDNS is blocked (VPN/proxy app, AP isolation).
        item { HorizontalDivider() }
        item {
            ManualPeerAddressSection(
                onSet = { viewModel.setManualPeerAddress(it) },
                onClear = { viewModel.setManualPeerAddress("") }
            )
        }
    }

    if (showPasteDialog) {
        PastePayloadDialog(
            onDismiss = { showPasteDialog = false },
            onPair = { payload ->
                showPasteDialog = false
                viewModel.completePairing(payload)
            }
        )
    }
}

@Composable
private fun ManualPeerAddressSection(onSet: (String) -> Unit, onClear: () -> Unit) {
    var manualIp by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(stringResource(R.string.sync_manual_ip_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.sync_manual_ip_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = manualIp,
            onValueChange = { manualIp = it },
            label = { Text(stringResource(R.string.sync_manual_ip_label)) },
            placeholder = { Text(stringResource(R.string.sync_manual_ip_placeholder)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSet(manualIp) }, enabled = manualIp.isNotBlank()) {
                Text(stringResource(R.string.sync_manual_ip_set_button))
            }
            OutlinedButton(onClick = {
                manualIp = ""
                onClear()
            }) { Text(stringResource(R.string.sync_manual_ip_clear_button)) }
        }
    }
}

@Composable
private fun PairedPeerRow(peer: PairedPeer, onRevoke: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(peer.name, style = MaterialTheme.typography.bodyMedium)
            Text(peer.fingerprint, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onRevoke) { Text(stringResource(R.string.sync_revoke_button)) }
    }
}

@Composable
private fun DiscoveredPeerRow(peer: DiscoveredPeer) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(peer.name, style = MaterialTheme.typography.bodyMedium)
        Text(
            peer.fingerprint ?: stringResource(R.string.sync_no_fingerprint),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun PastePayloadDialog(onDismiss: () -> Unit, onPair: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_paste_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.sync_paste_field_label)) },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onPair(text.trim()) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.sync_pair_button))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.sync_cancel_button)) } }
    )
}
