package com.fauxx.network.dns

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** The device's own resolver: whatever Android, the VPN, Private DNS or the router provides. */
object SystemHostResolver : HostResolver {
    override fun resolve(host: String): Resolution = classify { InetAddress.getAllByName(host).toList() }
}

/**
 * DNS-over-HTTPS through OkHttp's `okhttp-dnsoverhttps` (#227).
 *
 * The DoH endpoint's own hostname is resolved from [bootstrap] when given, never through the
 * system resolver, because the system resolver is exactly what the user is routing around: a
 * Pi-hole that blocks trackers may as well block `dns.quad9.net`. A custom URL without bootstrap
 * addresses falls back to the system resolver for that one name.
 *
 * The OkHttp client is a fresh, minimal one and deliberately NOT the app's orphaned
 * [okhttp3.OkHttpClient] from NetworkModule, whose interceptors randomize headers. DoH requests only
 * ever reach the resolver the user chose, never a tracker, so OkHttp's TLS fingerprint here is not
 * the #168/#169 tell that keeps synthetic traffic on the WebView.
 */
class DohHostResolver(url: String, bootstrap: List<InetAddress>) : HostResolver {

    private val dns: Dns = DnsOverHttps.Builder()
        .client(
            OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build(),
        )
        .url(url.toHttpUrl())
        .apply { if (bootstrap.isNotEmpty()) bootstrapDnsHosts(bootstrap) }
        .includeIPv6(true)
        .post(true)
        // Skip OkHttp's private-name filter. It consults the public suffix list, which OkHttp 5
        // loads on Android from app assets via its startup initializer; an uninitialized list
        // would fail every lookup. Public DoH resolvers answer nothing useful for .local-style
        // names anyway, and the WebView reaches the LAN without the proxy too, so nothing new is
        // exposed by resolving them.
        .resolvePrivateAddresses(true)
        .build()

    override fun resolve(host: String): Resolution = classify { dns.lookup(host) }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
    }
}

/**
 * Map a lookup onto [Resolution]. An [UnknownHostException] whose cause is an [IOException] is a
 * resolver that could not answer; one without is a resolver that answered "no such host". An
 * empty list is treated as no such host too.
 */
internal fun classify(lookup: () -> List<InetAddress>): Resolution =
    try {
        val addresses = lookup()
        if (addresses.isEmpty()) Resolution.NoSuchHost else Resolution.Addresses(addresses)
    } catch (e: UnknownHostException) {
        if (e.cause is IOException) Resolution.Failure(e) else Resolution.NoSuchHost
    } catch (e: IOException) {
        Resolution.Failure(e)
    } catch (e: RuntimeException) {
        Resolution.Failure(e)
    }
