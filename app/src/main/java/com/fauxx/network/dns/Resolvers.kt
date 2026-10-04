package com.fauxx.network.dns

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import android.annotation.SuppressLint
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

/** The device's own resolver: whatever Android, the VPN, Private DNS or the router provides. */
object SystemHostResolver : HostResolver {
    override fun resolve(host: String): Resolution = classify { InetAddress.getAllByName(host).toList() }
}

/**
 * DNS-over-HTTPS through OkHttp's `okhttp-dnsoverhttps` (#227).
 *
 * Reaching the DoH endpoint itself is the delicate part, because the system resolver is exactly
 * what the user is routing around: a Pi-hole that blocks trackers may well block
 * `dns.quad9.net` or `dns.nextdns.io` too (DoH-bypass blocklists list them). So:
 * - a preset is reached by its built-in [bootstrap] IPs, and only if every one of them fails
 *   (stale addresses) by resolving its hostname through [endpointResolver] instead;
 * - a custom URL has no built-in addresses, so its hostname always goes through
 *   [endpointResolver], which the router points at the default preset over DoH before the system
 *   resolver.
 *
 * Whatever goes wrong reaching the endpoint must surface as a [Resolution.Failure], never as
 * [Resolution.NoSuchHost]: a failure trips the fail-open breaker, while "no such host" is taken as
 * the resolver's answer. Getting this wrong fails CLOSED, silently: every lookup "answers" no such
 * host, the proxy refuses every page, and health still reads healthy.
 *
 * [certificatePin] replaces normal certificate checking when the user opted out of it for a
 * self-hosted resolver with a self-signed certificate: the server's key is trusted on first use
 * and only that key afterwards (see [CertificatePin]). Only a custom URL can ask for it (the router
 * never sets it for a preset), and it applies to this client alone, never to the WebView's own
 * connections.
 *
 * Redirects are not followed. A DoH server has no reason to redirect, and following one to another
 * host fails inside OkHttp's bootstrap resolver in a way that reads as "no such host": every
 * lookup would then fail closed while health stayed healthy.
 *
 * The OkHttp client is a fresh, minimal one and deliberately NOT the app's orphaned client from
 * NetworkModule, whose interceptors randomize headers. DoH requests only ever reach the resolver
 * the user chose, never a tracker, so OkHttp's TLS fingerprint here is not the #168/#169 tell.
 */
class DohHostResolver(
    url: String,
    bootstrap: List<InetAddress>,
    endpointResolver: HostResolver = SystemHostResolver,
    private val certificatePin: CertificatePin? = null,
) : HostResolver {

    private val endpointDns = EndpointDns(endpointResolver)
    private val primary: Dns = build(url, bootstrap)
    private val viaEndpointResolver: Dns? = if (bootstrap.isNotEmpty()) build(url, emptyList()) else null

    override fun resolve(host: String): Resolution {
        val first = classify { primary.lookup(host) }
        if (first !is Resolution.Failure || viaEndpointResolver == null) return first
        // Every bootstrap IP failed (they can go stale): try the endpoint's hostname instead.
        return classify { viaEndpointResolver.lookup(host) }.takeUnless { it is Resolution.Failure } ?: first
    }

    private fun build(url: String, bootstrap: List<InetAddress>): Dns = DnsOverHttps.Builder()
        .client(
            OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .apply { certificatePin?.let { pinTo(it) } }
                .build(),
        )
        .url(url.toHttpUrl())
        .apply { if (bootstrap.isNotEmpty()) bootstrapDnsHosts(bootstrap) }
        .systemDns(endpointDns)
        .includeIPv6(true)
        .post(true)
        // Skip OkHttp's private-name filter. It consults the public suffix list, which OkHttp 5
        // loads on Android from app assets via its startup initializer; an uninitialized list
        // would fail every lookup. The proxy refuses non-public targets itself.
        .resolvePrivateAddresses(true)
        .build()

    /**
     * Resolves the DoH endpoint's own hostname. Any miss is rethrown as a plain [IOException]
     * (not an [UnknownHostException]) so it can never be mistaken for the DoH server answering
     * "no such host" (see the class KDoc).
     */
    private class EndpointDns(private val resolver: HostResolver) : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            when (val r = resolver.resolve(hostname)) {
                is Resolution.Addresses -> r.addresses
                Resolution.NoSuchHost -> throw IOException("DoH endpoint $hostname did not resolve")
                is Resolution.Failure -> throw IOException("DoH endpoint $hostname could not be resolved", r.cause)
            }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L

        /**
         * Trust only the pinned key (see [CertificatePin]). The hostname is not checked: the pinned
         * key is the server's identity, and a self-signed certificate rarely names the URL's host.
         */
        @SuppressLint("BadHostnameVerifier")
        fun OkHttpClient.Builder.pinTo(pin: CertificatePin): OkHttpClient.Builder {
            val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(pin), null) }
            return sslSocketFactory(tls.socketFactory, pin).hostnameVerifier { _, _ -> true }
        }
    }
}

/**
 * Map a lookup onto [Resolution].
 *
 * Pinned by `DohHostResolverTest` against okhttp-dnsoverhttps 5.5.0, which reports:
 * - NXDOMAIN and NODATA as an [UnknownHostException] with no cause and no "server failure"
 *   message: the resolver's ANSWER, so [Resolution.NoSuchHost];
 * - SERVFAIL as `UnknownHostException("DNS server failure")`: the resolver could not answer, so
 *   [Resolution.Failure];
 * - transport problems as the raw [IOException] (or an [UnknownHostException] caused by one).
 *
 * On the system resolver an [UnknownHostException] means no such host either way; it is the
 * fallback, so the distinction does not matter there.
 */
internal fun classify(lookup: () -> List<InetAddress>): Resolution =
    try {
        val addresses = lookup()
        if (addresses.isEmpty()) Resolution.NoSuchHost else Resolution.Addresses(addresses)
    } catch (e: UnknownHostException) {
        when {
            e.cause is IOException -> Resolution.Failure(e)
            e.message?.contains(SERVER_FAILURE, ignoreCase = true) == true -> Resolution.Failure(e)
            // OkHttp's bootstrap resolver was asked for a host other than the endpoint's: the
            // client was sent elsewhere, which is the resolver failing, not answering.
            e.message?.startsWith(BOOTSTRAP_MISMATCH) == true -> Resolution.Failure(e)
            else -> Resolution.NoSuchHost
        }
    } catch (e: IOException) {
        Resolution.Failure(e)
    } catch (e: RuntimeException) {
        Resolution.Failure(e)
    }

private const val SERVER_FAILURE = "server failure"
private const val BOOTSTRAP_MISMATCH = "BootstrapDns called for"
