package com.fauxx.sync.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.fauxx.sync.wire.SyncConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton

/**
 * mDNS advertise + browse over [NsdManager] (E13 #178). Advertises this device under
 * [SyncConstants.NSD_SERVICE_TYPE] with a TXT record carrying `v`/`fp`/`pk`, browses for the same
 * type, and surfaces resolved peers as untrusted [DiscoveredPeer] records (discovery never grants
 * sync; the QR/pairing handshake does).
 *
 * NsdManager footguns handled here (see the E13 brief): the registration and discovery listeners
 * are single long-lived fields (you cannot unregister a listener you no longer hold); register and
 * unregister are idempotent behind a state flag; resolves are serialized through a one-in-flight
 * queue with a FRESH [NsdManager.ResolveListener] per resolve (pre-API-34 `resolveService` is
 * single-flight and silently drops concurrent calls with `FAILURE_ALREADY_ACTIVE`); the post-
 * registration name is read back as canonical (NsdManager may rename a colliding "Name" to
 * "Name (2)"); and our own advertisement is skipped by EXACT instance label, never a prefix match.
 */
@Singleton
class NsdDiscovery @Inject constructor(
    @ApplicationContext context: Context
) {
    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val lock = Any()

    // Resolved peers keyed by mDNS instance name, so repeat resolutions update in place.
    private val peers = LinkedHashMap<String, DiscoveredPeer>()
    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())

    /** The current snapshot of resolved peers (untrusted). */
    val discoveredPeers: StateFlow<List<DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    // The single held listeners. Never construct these inline at call sites.
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /** The canonical post-registration instance name (NsdManager may have renamed a collision). */
    @Volatile private var advertisedName: String? = null
    private var requestedName: String? = null

    // Serialized resolve queue: one resolve in flight at a time (legacy single-flight constraint).
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolveInFlight = false

    private var lastAdvertise: AdvertiseParams? = null

    private data class AdvertiseParams(
        val instanceName: String,
        val port: Int,
        val publicKeyB64: String,
        val fingerprint: String
    )

    /**
     * Build the [NsdServiceInfo] this device advertises, including the TXT record. Pure assembly
     * (no multicast), so it is exercisable by an instrumented TXT-record test without a live network.
     */
    fun buildServiceInfo(
        instanceName: String,
        port: Int,
        publicKeyB64: String,
        fingerprint: String
    ): NsdServiceInfo = NsdServiceInfo().apply {
        serviceName = instanceName
        serviceType = SyncConstants.NSD_SERVICE_TYPE
        this.port = port
        setAttribute(SyncConstants.TXT_KEY_VERSION, SyncConstants.PROTOCOL_VERSION.toString())
        setAttribute(SyncConstants.TXT_KEY_FINGERPRINT, fingerprint)
        setAttribute(SyncConstants.TXT_KEY_PUBKEY, publicKeyB64)
    }

    /** Register this device's service and begin browsing. Idempotent. */
    @Synchronized
    fun advertise(instanceName: String, port: Int, publicKeyB64: String, fingerprint: String) {
        lastAdvertise = AdvertiseParams(instanceName, port, publicKeyB64, fingerprint)
        if (registrationListener == null) {
            requestedName = instanceName
            val listener = newRegistrationListener()
            registrationListener = listener
            try {
                nsdManager.registerService(
                    buildServiceInfo(instanceName, port, publicKeyB64, fingerprint),
                    NsdManager.PROTOCOL_DNS_SD,
                    listener
                )
            } catch (e: Exception) {
                Timber.w(e, "LAN sync: registerService failed")
                registrationListener = null
            }
        }
        browse()
    }

    /** Begin browsing for peers. Idempotent (no-op while a browse is active). */
    @Synchronized
    fun browse() {
        if (discoveryListener != null) return
        val listener = newDiscoveryListener()
        discoveryListener = listener
        try {
            nsdManager.discoverServices(SyncConstants.NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Timber.w(e, "LAN sync: discoverServices failed")
            discoveryListener = null
        }
    }

    /** Stop advertising and browsing, tearing listeners down in reverse order of creation. */
    @Synchronized
    fun stop() {
        discoveryListener?.let {
            runCatching { nsdManager.stopServiceDiscovery(it) }
        }
        discoveryListener = null
        registrationListener?.let {
            runCatching { nsdManager.unregisterService(it) }
        }
        registrationListener = null
        advertisedName = null
        synchronized(lock) {
            peers.clear()
            resolveQueue.clear()
            resolveInFlight = false
        }
        _discoveredPeers.value = emptyList()
    }

    /**
     * Re-register and re-discover after a network change (Wi-Fi drop/roam). The discovered cache is
     * cleared; identity is the X25519 key, the address a re-resolvable hint.
     */
    @Synchronized
    fun restart() {
        val params = lastAdvertise ?: return
        stop()
        advertise(params.instanceName, params.port, params.publicKeyB64, params.fingerprint)
    }

    private fun newRegistrationListener() = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            // Canonical name: NsdManager may have renamed a collision to "Name (2)".
            advertisedName = serviceInfo.serviceName
            Timber.i("LAN sync: advertising as '%s'", serviceInfo.serviceName)
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Timber.w("LAN sync: registration failed (%d)", errorCode)
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            Timber.i("LAN sync: unregistered")
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Timber.w("LAN sync: unregistration failed (%d)", errorCode)
        }
    }

    private fun newDiscoveryListener() = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            // Skip our own instance by EXACT label (a prefix match would wrongly hide a peer whose
            // name merely extends ours, e.g. "Pixel" vs "Pixel-2").
            if (serviceInfo.serviceName == advertisedName) return
            enqueueResolve(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            synchronized(lock) {
                if (peers.remove(serviceInfo.serviceName) != null) publishLocked()
            }
        }

        override fun onDiscoveryStopped(serviceType: String) {}

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Timber.w("LAN sync: start discovery failed (%d)", errorCode)
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Timber.w("LAN sync: stop discovery failed (%d)", errorCode)
        }
    }

    private fun enqueueResolve(serviceInfo: NsdServiceInfo) {
        synchronized(lock) {
            resolveQueue.addLast(serviceInfo)
            maybeResolveNextLocked()
        }
    }

    private fun maybeResolveNextLocked() {
        if (resolveInFlight) return
        val next = resolveQueue.pollFirst() ?: return
        resolveInFlight = true
        try {
            nsdManager.resolveService(next, newResolveListener())
        } catch (e: Exception) {
            Timber.w(e, "LAN sync: resolveService threw")
            resolveInFlight = false
            maybeResolveNextLocked()
        }
    }

    private fun newResolveListener() = object : NsdManager.ResolveListener {
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val peer = peerFromResolved(serviceInfo)
            synchronized(lock) {
                // Re-check self-skip post-resolve, by exact label.
                if (serviceInfo.serviceName != advertisedName) {
                    peers[serviceInfo.serviceName] = peer
                    publishLocked()
                }
                resolveInFlight = false
                maybeResolveNextLocked()
            }
        }

        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Timber.d("LAN sync: resolve failed for '%s' (%d)", serviceInfo.serviceName, errorCode)
            synchronized(lock) {
                resolveInFlight = false
                maybeResolveNextLocked()
            }
        }
    }

    private fun publishLocked() {
        _discoveredPeers.value = peers.values.toList()
    }

    private companion object {
        @Suppress("DEPRECATION")
        fun peerFromResolved(info: NsdServiceInfo): DiscoveredPeer {
            val txt = info.attributes
            val version = txt[SyncConstants.TXT_KEY_VERSION]?.let { String(it, Charsets.UTF_8) }?.toIntOrNull()
            val fingerprint = txt[SyncConstants.TXT_KEY_FINGERPRINT]?.let { String(it, Charsets.UTF_8) }
            val publicKey = txt[SyncConstants.TXT_KEY_PUBKEY]?.let { String(it, Charsets.UTF_8) }
            val hostAddr = info.host?.hostAddress
            val hostName = info.host?.hostName
            val addresses = if (hostAddr != null) listOf("$hostAddr:${info.port}") else emptyList()
            return DiscoveredPeer(
                name = info.serviceName,
                host = hostName,
                addresses = addresses,
                port = info.port,
                fingerprint = fingerprint,
                publicKey = publicKey,
                protocolVersion = version
            )
        }
    }
}
