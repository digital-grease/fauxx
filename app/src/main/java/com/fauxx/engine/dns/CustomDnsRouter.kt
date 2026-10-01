package com.fauxx.engine.dns

import com.fauxx.data.model.DnsMode
import com.fauxx.data.model.PoisonProfile
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.webview.PhantomProxyAuth
import com.fauxx.engine.webview.WebViewProxyOverride
import com.fauxx.network.dns.DnsHealth
import com.fauxx.network.dns.DohHostResolver
import com.fauxx.network.dns.DohPresets
import com.fauxx.network.dns.FailOpenResolver
import com.fauxx.network.dns.LoopbackProxy
import com.fauxx.network.dns.ProxyCredentials
import com.fauxx.network.dns.SystemHostResolver
import com.fauxx.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** The engine's view of custom DNS (#227): start and stop with it, and report how it is doing. */
interface CustomDns {
    val health: StateFlow<DnsHealth>

    /** Route per the current settings. Returns once the WebView override is in effect, or not needed. */
    suspend fun start()

    /** Stop routing: restore direct WebView connections first, then stop the proxy. */
    suspend fun stop()

    companion object {
        /** Custom DNS absent entirely; used by engine tests. */
        val NONE: CustomDns = object : CustomDns {
            override val health: StateFlow<DnsHealth> = MutableStateFlow(DnsHealth.Off)
            override suspend fun start() {}
            override suspend fun stop() {}
        }
    }
}

/**
 * Runs the loopback proxy that gives Fauxx's WebView traffic the user's chosen resolver (#227),
 * and points the WebView at it.
 *
 * Fail-OPEN throughout (owner decision): any way this can break ends with the WebView override
 * cleared and browsing continuing on the system resolver, with [health] saying so. That covers a
 * dead resolver (the [FailOpenResolver] breaker), a WebView without `PROXY_OVERRIDE`, an override
 * that never confirms, an invalid custom URL, and the proxy's accept loop dying underneath a live
 * override, which would otherwise black-hole every page: an accidental fail-CLOSED.
 *
 * Settings changes while running are picked up live; each change gets a fresh proxy with fresh
 * credentials.
 */
@Singleton
class CustomDnsRouter @Inject constructor(
    private val profileRepo: PoisonProfileRepository,
    private val clock: Clock,
    private val override: WebViewProxyOverride,
) : CustomDns, PhantomProxyAuth {

    private data class Settings(val mode: DnsMode, val provider: String, val customUrl: String)

    private class Running(val settings: Settings, val proxy: LoopbackProxy, val credentials: ProxyCredentials)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    @Volatile private var running: Running? = null
    private var active = false
    private var settingsJob: Job? = null
    private var healthJob: Job? = null

    private val _health = MutableStateFlow<DnsHealth>(DnsHealth.Off)
    override val health: StateFlow<DnsHealth> = _health.asStateFlow()

    override suspend fun start() {
        mutex.withLock {
            active = true
            applyLocked(settingsOf(profileRepo.getProfile()))
        }
        settingsJob?.cancel()
        // No drop(1): a change landing between the apply above and this subscription would arrive
        // as the flow's first value, and dropping it would lose the change. applyLocked is
        // idempotent for unchanged settings, so seeing the current value again costs nothing.
        settingsJob = scope.launch {
            profileRepo.profiles.map(::settingsOf).distinctUntilChanged().collect { settings ->
                mutex.withLock { if (active) applyLocked(settings) }
            }
        }
    }

    override suspend fun stop() {
        settingsJob?.cancel()
        settingsJob = null
        mutex.withLock {
            active = false
            teardownLocked()
            _health.value = DnsHealth.Off
        }
    }

    override fun credentialsFor(host: String, realm: String): Pair<String, String>? {
        val creds = running?.credentials ?: return null
        return if (host == LoopbackProxy.LOOPBACK && realm == creds.realm) creds.user to creds.secret else null
    }

    private suspend fun applyLocked(settings: Settings) {
        if (running?.settings == settings) return
        teardownLocked()
        if (settings.mode == DnsMode.SYSTEM) {
            _health.value = DnsHealth.Off
            return
        }
        if (!override.isSupported()) {
            degrade("This WebView cannot route through a proxy")
            return
        }
        val doh = dohFor(settings) ?: run {
            degrade("The custom DNS-over-HTTPS URL is not a valid https address")
            return
        }
        val resolver = FailOpenResolver(doh, SystemHostResolver, clock)
        val credentials = ProxyCredentials.random()
        val proxy = LoopbackProxy(resolver, credentials, onDied = { onProxyDied() })
        val port = try {
            proxy.start()
        } catch (e: Exception) {
            Timber.w(e, "Could not start the custom DNS proxy")
            proxy.stop()
            degrade("The custom DNS proxy could not start")
            return
        }
        // Publish before pointing the WebView at it, so the auth challenge can always be answered.
        running = Running(settings, proxy, credentials)
        if (!override.set(port)) {
            teardownLocked()
            degrade("The WebView did not accept the proxy")
            return
        }
        _health.value = resolver.health.value
        healthJob = scope.launch { resolver.health.collect { _health.value = it } }
        Timber.i("Custom DNS active (%s) on 127.0.0.1:%d", settings.provider, port)
    }

    /** Clear the override FIRST, so the WebView is never left pointing at a dead port. */
    private suspend fun teardownLocked() {
        healthJob?.cancel()
        healthJob = null
        val current = running ?: return
        running = null
        override.clear()
        current.proxy.stop()
    }

    private fun onProxyDied() {
        scope.launch {
            mutex.withLock {
                if (running == null) return@withLock
                teardownLocked()
                degrade("The custom DNS proxy stopped")
            }
        }
    }

    private fun degrade(reason: String) {
        Timber.w("Custom DNS degraded: %s", reason)
        _health.value = DnsHealth.Degraded(clock.currentTimeMillis(), reason)
    }

    private fun dohFor(settings: Settings): DohHostResolver? {
        val preset = DohPresets.byId(settings.provider)
        return when {
            preset != null -> DohHostResolver(preset.url, preset.bootstrapAddresses())
            settings.provider == DohPresets.CUSTOM_ID && DohPresets.isValidCustomUrl(settings.customUrl) ->
                DohHostResolver(settings.customUrl.trim(), bootstrap = emptyList())
            else -> null
        }
    }

    private fun settingsOf(p: PoisonProfile) = Settings(p.dnsMode, p.dohProvider, p.dohCustomUrl)
}
