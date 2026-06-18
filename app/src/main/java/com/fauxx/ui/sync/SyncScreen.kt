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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
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

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { viewModel.completePairing(it) }
    }
    val launchScan = { scanLauncher.launch(QrScanOptionsFactory.pairingScan()) }

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
                Text("Enable LAN sync", style = MaterialTheme.typography.titleMedium)
                Switch(checked = state.syncEnabled, onCheckedChange = { viewModel.setSyncEnabled(it) })
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("My pairing QR", style = MaterialTheme.typography.titleSmall)
                    state.qrBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "This device's pairing QR",
                            modifier = Modifier.size(240.dp)
                        )
                    }
                    state.fingerprint?.let {
                        Text("Fingerprint: $it", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "Have the other device scan this, or scan theirs.",
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
                Button(onClick = onScanClicked, modifier = Modifier.weight(1f)) { Text("Scan to pair") }
                OutlinedButton(onClick = { showPasteDialog = true }, modifier = Modifier.weight(1f)) {
                    Text("Paste payload")
                }
            }
        }

        item {
            Button(
                onClick = { viewModel.pushCurrentPersonaToAll() },
                modifier = Modifier.fillMaxWidth(),
                enabled = paired.isNotEmpty()
            ) { Text("Push current persona to paired devices") }
        }

        state.statusMessage?.let { msg ->
            item { Text(msg, style = MaterialTheme.typography.bodySmall) }
        }
        state.lastImport?.let { msg ->
            item { Text(msg, style = MaterialTheme.typography.bodySmall) }
        }

        item { HorizontalDivider() }
        item { Text("Paired devices", style = MaterialTheme.typography.titleSmall) }
        if (paired.isEmpty()) {
            item { Text("None yet.", style = MaterialTheme.typography.bodySmall) }
        } else {
            items(paired, key = { it.publicKey }) { peer -> PairedPeerRow(peer) { viewModel.revoke(peer) } }
        }

        item { HorizontalDivider() }
        item { Text("Discovered on this network", style = MaterialTheme.typography.titleSmall) }
        if (discovered.isEmpty()) {
            item { Text("None yet.", style = MaterialTheme.typography.bodySmall) }
        } else {
            items(discovered, key = { it.name }) { peer -> DiscoveredPeerRow(peer) }
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
        TextButton(onClick = onRevoke) { Text("Revoke") }
    }
}

@Composable
private fun DiscoveredPeerRow(peer: DiscoveredPeer) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(peer.name, style = MaterialTheme.typography.bodyMedium)
        Text(
            peer.fingerprint ?: "(no fingerprint advertised)",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun PastePayloadDialog(onDismiss: () -> Unit, onPair: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste pairing payload") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("base64url payload") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = { onPair(text.trim()) }, enabled = text.isNotBlank()) { Text("Pair") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
