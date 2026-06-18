package com.fauxx.sync.discovery

/**
 * A peer surfaced by mDNS discovery (E13 #178). Untrusted until paired: discovery alone never
 * grants sync. Transient (never persisted); identity is the X25519 public key, the address is a
 * re-resolvable hint. Mirrors the desktop `DiscoveredPeer`.
 */
data class DiscoveredPeer(
    /** The mDNS instance name (human-readable device name). */
    val name: String,
    /** The resolved host name, if any. */
    val host: String?,
    /** Resolved socket addresses (`ip:port`). */
    val addresses: List<String>,
    /** The advertised sync port. */
    val port: Int,
    /** The advertised public-key fingerprint (TXT `fp`), or null if not advertised. */
    val fingerprint: String?,
    /** The advertised full base64url public key (TXT `pk`), or null. A peer without it cannot be paired from discovery. */
    val publicKey: String?,
    /** The advertised protocol version (TXT `v`), or null if unparseable. */
    val protocolVersion: Int?
)
