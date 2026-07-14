package com.fauxx.ui.sync

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fauxx.R
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
import com.fauxx.sync.wire.SyncConstants
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
        // #213: seed the toggle from the REAL service state so re-entering the screen (or a fresh
        // process) reflects whether a session is actually running, not the stale OFF default.
        _uiState.value = _uiState.value.copy(syncEnabled = SyncForegroundService.isRunning)
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
                        lastImport = appContext.getString(R.string.sync_status_imported, it.name, it.id.take(8))
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Re-sync the toggle with the real service state (#213). Called on screen resume so the toggle
     * reflects an externally-stopped session (e.g. the notification's Stop action) instead of a
     * stale ON.
     */
    fun refreshSyncEnabledState() {
        val running = SyncForegroundService.isRunning
        if (_uiState.value.syncEnabled != running) {
            _uiState.value = _uiState.value.copy(syncEnabled = running)
        }
    }

    /**
     * Set (or clear, on blank input) a manual peer IP[:port] used as a last-resort route when mDNS
     * discovery is blocked by a VPN/proxy app or Wi-Fi AP client-isolation (#213). The sealed
     * channel still authenticates by paired key, so a wrong address simply fails to deliver.
     */
    fun setManualPeerAddress(input: String) {
        viewModelScope.launch {
            if (input.isBlank()) {
                tcpClient.setManualFallback(null)
                _uiState.value = _uiState.value.copy(
                    statusMessage = appContext.getString(R.string.sync_status_manual_ip_cleared)
                )
                return@launch
            }
            val addr = withContext(Dispatchers.IO) {
                TcpClient.parseHostPort(input, SyncConstants.DEFAULT_SYNC_PORT)
            }
            if (addr == null) {
                _uiState.value = _uiState.value.copy(
                    statusMessage = appContext.getString(R.string.sync_status_manual_ip_invalid)
                )
                return@launch
            }
            tcpClient.setManualFallback(addr)
            _uiState.value = _uiState.value.copy(
                statusMessage = appContext.getString(
                    R.string.sync_status_manual_ip_set, "${addr.hostString}:${addr.port}"
                )
            )
        }
    }

    /** Enable or disable the LAN sync session (starts/stops the foreground service). */
    fun setSyncEnabled(enabled: Boolean) {
        if (enabled) SyncForegroundService.start(appContext) else SyncForegroundService.stop(appContext)
        _uiState.value = _uiState.value.copy(
            syncEnabled = enabled,
            statusMessage = appContext.getString(
                if (enabled) R.string.sync_status_enabled else R.string.sync_status_stopped
            )
        )
    }

    /** Complete pairing from a scanned or pasted base64url payload string. */
    fun completePairing(payload: String) {
        viewModelScope.launch {
            val result = runCatching { pairingManager.completePairing(payload) }
            _uiState.value = _uiState.value.copy(
                statusMessage = result.fold(
                    onSuccess = { appContext.getString(R.string.sync_status_paired, it.name, it.fingerprint) },
                    onFailure = { appContext.getString(R.string.sync_status_pair_failed, it.message ?: "") }
                )
            )
        }
    }

    /** Revoke a paired peer. */
    fun revoke(peer: PairedPeer) {
        viewModelScope.launch {
            pairedPeerRepository.unpair(peer.publicKey)
            _uiState.value = _uiState.value.copy(
                statusMessage = appContext.getString(R.string.sync_status_removed, peer.name)
            )
        }
    }

    /** Push the most recent locally-generated persona to every paired peer. */
    fun pushCurrentPersonaToAll() {
        viewModelScope.launch {
            // #213: routes only populate while the session runs, so pushing with sync disabled
            // deterministically failed with a confusing "no route" error. Tell the user plainly.
            if (!_uiState.value.syncEnabled) {
                _uiState.value = _uiState.value.copy(
                    statusMessage = appContext.getString(R.string.sync_status_push_requires_enabled)
                )
                return@launch
            }
            val peers = pairedPeers.value
            if (peers.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    statusMessage = appContext.getString(R.string.sync_status_no_peers)
                )
                return@launch
            }
            val persona = latestLocalPersona()
            if (persona == null) {
                _uiState.value = _uiState.value.copy(
                    statusMessage = appContext.getString(R.string.sync_status_no_persona)
                )
                return@launch
            }
            val result = tcpClient.pushPersonaToAll(persona, peers)
            val message = if (result.failedPeerNames.isEmpty()) {
                appContext.getString(R.string.sync_status_push_result, result.sent, peers.size)
            } else {
                appContext.getString(
                    R.string.sync_status_push_result_failures,
                    result.sent, peers.size, result.failedPeerNames.joinToString()
                )
            }
            _uiState.value = _uiState.value.copy(statusMessage = message)
        }
    }

    private suspend fun latestLocalPersona(): SyntheticPersona? = withContext(Dispatchers.IO) {
        personaHistoryDao.getRecentPersonas(0L).firstOrNull()?.let {
            runCatching { gson.fromJson(it.personaJson, SyntheticPersona::class.java) }.getOrNull()
        }
    }
}
