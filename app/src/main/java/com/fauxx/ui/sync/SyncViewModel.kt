package com.fauxx.ui.sync

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fauxx.data.model.SyntheticPersona
import com.fauxx.service.SyncForegroundService
import com.fauxx.sync.data.PairedPeer
import com.fauxx.sync.data.PairedPeerRepository
import com.fauxx.sync.data.SyncPersonaStore
import com.fauxx.sync.discovery.DiscoveredPeer
import com.fauxx.sync.discovery.NsdDiscovery
import com.fauxx.sync.pairing.PairingManager
import com.fauxx.sync.pairing.QrRenderer
import com.fauxx.sync.transport.TcpClient
import com.fauxx.targeting.layer3.PersonaHistoryDao
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Immutable UI state for the Sync screen. */
data class SyncUiState(
    val syncEnabled: Boolean = false,
    val fingerprint: String? = null,
    val qrBitmap: Bitmap? = null,
    val myPayload: String? = null,
    val statusMessage: String? = null,
    val lastImport: String? = null
)

/**
 * Drives the Sync screen (E13 #178): exposes this device's fingerprint + QR, the paired-peer and
 * discovered-peer flows, and the enable/disable, pair, revoke, and push actions. Enabling sync
 * starts the [SyncForegroundService] (which owns the listener/discovery/multicast lifecycle).
 */
@HiltViewModel
class SyncViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val pairingManager: PairingManager,
    private val pairedPeerRepository: PairedPeerRepository,
    private val tcpClient: TcpClient,
    nsdDiscovery: NsdDiscovery,
    syncPersonaStore: SyncPersonaStore,
    private val personaHistoryDao: PersonaHistoryDao
) : ViewModel() {

    private val gson = Gson()

    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    val pairedPeers: StateFlow<List<PairedPeer>> =
        pairedPeerRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val discoveredPeers: StateFlow<List<DiscoveredPeer>> = nsdDiscovery.discoveredPeers

    init {
        // Load this device's identity-derived display values off the main thread.
        viewModelScope.launch {
            val payload = pairingManager.myPairingPayload()
            val fingerprint = pairingManager.myFingerprint()
            val bitmap = withContext(Dispatchers.Default) { QrRenderer.render(payload) }
            _uiState.value = _uiState.value.copy(
                fingerprint = fingerprint,
                qrBitmap = bitmap,
                myPayload = payload.encode()
            )
        }
        // Surface inbound synced personas as a transparency notice (persistence is the gate).
        syncPersonaStore.observeAll()
            .onEach { personas ->
                personas.firstOrNull()?.let {
                    _uiState.value = _uiState.value.copy(
                        lastImport = "Imported decoy persona ${it.name} (${it.id.take(8)})"
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    /** Enable or disable the LAN sync session (starts/stops the foreground service). */
    fun setSyncEnabled(enabled: Boolean) {
        if (enabled) SyncForegroundService.start(appContext) else SyncForegroundService.stop(appContext)
        _uiState.value = _uiState.value.copy(
            syncEnabled = enabled,
            statusMessage = if (enabled) "Sync enabled. Discoverable on this network." else "Sync stopped."
        )
    }

    /** Complete pairing from a scanned or pasted base64url payload string. */
    fun completePairing(payload: String) {
        viewModelScope.launch {
            val result = runCatching { pairingManager.completePairing(payload) }
            _uiState.value = _uiState.value.copy(
                statusMessage = result.fold(
                    onSuccess = { "Paired with ${it.name} (${it.fingerprint})" },
                    onFailure = { "Pairing failed: ${it.message}" }
                )
            )
        }
    }

    /** Revoke a paired peer. */
    fun revoke(peer: PairedPeer) {
        viewModelScope.launch {
            pairedPeerRepository.unpair(peer.publicKey)
            _uiState.value = _uiState.value.copy(statusMessage = "Removed ${peer.name}")
        }
    }

    /** Push the most recent locally-generated persona to every paired peer. */
    fun pushCurrentPersonaToAll() {
        viewModelScope.launch {
            val peers = pairedPeers.value
            if (peers.isEmpty()) {
                _uiState.value = _uiState.value.copy(statusMessage = "No paired peers to push to.")
                return@launch
            }
            val persona = latestLocalPersona()
            if (persona == null) {
                _uiState.value = _uiState.value.copy(statusMessage = "No local persona to push yet.")
                return@launch
            }
            val result = tcpClient.pushPersonaToAll(persona, peers)
            _uiState.value = _uiState.value.copy(
                statusMessage = buildString {
                    append("Pushed to ${result.sent} of ${peers.size} peer(s)")
                    if (result.failedPeerNames.isNotEmpty()) {
                        append("; no route to ${result.failedPeerNames.joinToString()}")
                    }
                }
            )
        }
    }

    private suspend fun latestLocalPersona(): SyntheticPersona? = withContext(Dispatchers.IO) {
        personaHistoryDao.getRecentPersonas(0L).firstOrNull()?.let {
            runCatching { gson.fromJson(it.personaJson, SyntheticPersona::class.java) }.getOrNull()
        }
    }
}
