package com.fauxx.network.dns

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress

/**
 * A DNS-over-HTTPS resolver Fauxx offers by name (#227). [bootstrap] holds the resolver's own
 * addresses as IP literals, so reaching it never needs the system resolver, which may be the very
 * thing blocking it. Literals parse without any network I/O.
 */
data class DohPreset(val id: String, val label: String, val url: String, val bootstrap: List<String>) {
    fun bootstrapAddresses(): List<InetAddress> = bootstrap.map { InetAddress.getByName(it) }
}

object DohPresets {
    /** Default, matching the desktop companion's `DEFAULT_DOH_RESOLVER`. */
    val QUAD9 = DohPreset(
        id = "quad9", label = "Quad9", url = "https://dns.quad9.net/dns-query",
        bootstrap = listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9"),
    )
    val CLOUDFLARE = DohPreset(
        id = "cloudflare", label = "Cloudflare", url = "https://cloudflare-dns.com/dns-query",
        bootstrap = listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001"),
    )
    val MULLVAD = DohPreset(
        id = "mullvad", label = "Mullvad", url = "https://dns.mullvad.net/dns-query",
        bootstrap = listOf("194.242.2.2", "2a07:e340::2"),
    )
    val GOOGLE = DohPreset(
        id = "google", label = "Google", url = "https://dns.google/dns-query",
        bootstrap = listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844"),
    )

    val ALL: List<DohPreset> = listOf(QUAD9, CLOUDFLARE, MULLVAD, GOOGLE)

    /** Id stored when the user supplies their own URL (a NextDNS profile, for example). */
    const val CUSTOM_ID = "custom"
    const val DEFAULT_ID = "quad9"

    fun byId(id: String): DohPreset? = ALL.firstOrNull { it.id == id }

    /**
     * Whether [url] is usable as a custom DoH endpoint: https only (a resolver reached in the clear
     * would hand the whole browsing list to the network it is meant to route around), with a host,
     * and no `user:password@` part (credentials have no place in a DoH URL, and would end up
     * stored in plain preferences).
     */
    fun isValidCustomUrl(url: String): Boolean {
        val parsed = url.trim().toHttpUrlOrNull() ?: return false
        return parsed.isHttps && parsed.host.isNotBlank() && parsed.username.isEmpty() && parsed.password.isEmpty()
    }

    /**
     * The optional server address for a custom URL (#227): Fauxx connects there instead of looking
     * up the URL's hostname, which still names the server for TLS. For a resolver on the user's own
     * network, or one whose name their DNS blocks. Numeric only, validated without any lookup
     * (IPv6 may be bracketed); the unspecified address, multicast and link-local are refused. Null
     * for anything else, including blank.
     */
    fun parseServerIp(text: String): InetAddress? {
        val host = text.trim().removePrefix("[").removeSuffix("]")
        if (host.isEmpty()) return null
        val address = LoopbackProxy.parseIpLiteral(host) ?: return null
        if (address.isAnyLocalAddress || address.isMulticastAddress || address.isLinkLocalAddress) return null
        return address
    }
}
