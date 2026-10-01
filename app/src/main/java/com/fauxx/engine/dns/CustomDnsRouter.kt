package com.fauxx.engine.dns

import com.fauxx.data.model.DnsMode
import com.fauxx.data.model.PoisonProfile
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.webview.PhantomProxyAuth
import com.fauxx.engine.webview.WebViewProxyOverride
import com.fauxx.network.dns.CachingResolver
import com.fauxx.network.dns.DnsHealth
import com.fauxx.network.dns.DohHostResolver
import com.fauxx.network.dns.DohPresets
import com.fauxx.network.dns.FailOpenResolver
import com.fauxx.network.dns.HostResolver
import com.fauxx.network.dns.LoopbackProxy
import com.fauxx.network.dns.ProxyCredentials
import com.fauxx.network.dns.Resolution
import com.fauxx.network.dns.SystemHostResolver
import com.fauxx.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * Fail-OPEN throughout (owner decision): every way this can break ends with browsing continuing
 * on the system resolver and [health] saying so, never with the WebView pointed at a dead port.
 * That covers a dead or slow resolver (the [FailOpenResolver] breaker), a WebView without
 * `PROXY_OVERRIDE`, an override that never confirms, the proxy's accept loop dying under a live
 * override, and an override that cannot be CLEARED: then the old proxy is kept running (it still
 * forwards, falling back to the system resolver) until a later clear succeeds, because stopping it
 * would black-hole every page.
 *
 * Teardown runs under [NonCancellable]: cancelled half-way, it would leave either a live proxy
 * nobody owns or a WebView override pointing at a stopped one.
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

    /** A previous proxy whose override could not be cleared; kept alive until a clear succeeds. */
    @Volatile private var stranded: Running? = null

    /** Credentials of the proxy just replaced, still answered while its last challenges drain. */
    @Volatile private var retired: ProxyCredentials? = null

    private var active = false
    private var settingsJob: Job? = null
    private var healthJob: Job? = null

    private val _health = MutableStateFlow<DnsHealth>(DnsHealth.Off)
    override val health: StateFlow<DnsHealth> = _health.asStateFlow()

    override suspend fun start() {
        mutex.withLock {
            active = true
            applyLocked(settingsOf(profileRepo.getProfile()))
            settingsJob?.cancel()
            // No drop(1): a change landing between the apply above and this subscription would
            // arrive as the flow's first value, and dropping it would lose the change. applyLocked
            // is idempotent for unchanged settings, so seeing the current value again costs nothing.
            settingsJob = scope.launch {
                profileRepo.profiles.map(::settingsOf).distinctUntilChanged().collect { settings ->
                    mutex.withLock { if (active) applyLocked(settings) }
                }
            }
        }
    }

    override suspend fun stop() {
        withContext(NonCancellable) {
            mutex.withLock {
                active = false
                settingsJob?.cancel()
                settingsJob = null
                teardownLocked()
                // Nothing is left to answer for (a stranded proxy keeps its own credentials).
                retired = null
                _health.value = DnsHealth.Off
            }
        }
    }

    override fun credentialsFor(host: String, realm: String): Pair<String, String>? {
        if (host != LoopbackProxy.LOOPBACK) return null
        val creds = listOfNotNull(running?.credentials, stranded?.credentials, retired).firstOrNull { it.realm == realm }
        return creds?.let { it.user to it.secret }
    }

    private suspend fun applyLocked(settings: Settings) {
        if (running?.settings == settings && stranded == null) return
        teardownLocked()
        if (stranded != null) return // could not restore direct connections; teardownLocked degraded
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
        val failOpen = FailOpenResolver(doh, SystemHostResolver, clock)
        val resolver = CachingResolver(failOpen, clock)
        val credentials = ProxyCredentials.random()
        lateinit var proxy: LoopbackProxy
        proxy = LoopbackProxy(resolver, credentials, onDied = { onProxyDied(proxy) })
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
        _health.value = failOpen.health.value
        healthJob = scope.launch { failOpen.health.collect { _health.value = it } }
        Timber.i("Custom DNS active (%s) on 127.0.0.1:%d", settings.provider, port)
    }

    /**
     * Clear the override FIRST, then stop the proxy. If the clear does not confirm, the proxy is
     * NOT stopped (the WebView may still be pointing at it) and is kept as [stranded] until a later
     * clear succeeds. Never cancelled half-way (see the class KDoc).
     */
    private suspend fun teardownLocked() = withContext(NonCancellable) {
        healthJob?.cancel()
        healthJob = null
        val current = running ?: stranded ?: return@withContext
        running = null
        retired = current.credentials
        val cleared = try {
            override.clear()
        } catch (e: Exception) {
            Timber.w(e, "Clearing the WebView proxy override failed")
            false
        }
        if (cleared) {
            stranded?.proxy?.stop()
            stranded = null
            current.proxy.stop()
        } else {
            stranded = current
            degrade("Could not restore direct connections; keeping the proxy running")
        }
    }

    private fun onProxyDied(proxy: LoopbackProxy) {
        scope.launch {
            mutex.withLock {
                // Only the proxy that died: a late callback must not tear down its replacement.
                if (running?.proxy !== proxy) return@withLock
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
        if (settings.provider == DohPresets.CUSTOM_ID) {
            if (!DohPresets.isValidCustomUrl(settings.customUrl)) return null
            // A custom endpoint has no built-in addresses. Resolve its hostname over the default
            // preset's DoH first: DoH-bypass blocklists on the very Pi-hole or VPN being routed
            // around often block hosts like dns.nextdns.io. The system resolver is the fallback.
            return DohHostResolver(settings.customUrl.trim(), bootstrap = emptyList(), endpointResolver = defaultThenSystem)
        }
        // An id this build does not know (a preset removed in a later version) means the default,
        // not a broken setting.
        val preset = DohPresets.byId(settings.provider) ?: DohPresets.byId(DohPresets.DEFAULT_ID)!!
        return DohHostResolver(preset.url, preset.bootstrapAddresses())
    }

    private val defaultThenSystem: HostResolver by lazy {
        val preset = DohPresets.byId(DohPresets.DEFAULT_ID)!!
        val viaDefault = DohHostResolver(preset.url, preset.bootstrapAddresses())
        HostResolver { host ->
            viaDefault.resolve(host).takeIf { it is Resolution.Addresses } ?: SystemHostResolver.resolve(host)
        }
    }

    private fun settingsOf(p: PoisonProfile) = Settings(p.dnsMode, p.dohProvider, p.dohCustomUrl)
}
