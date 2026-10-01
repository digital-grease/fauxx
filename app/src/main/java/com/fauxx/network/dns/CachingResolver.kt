package com.fauxx.network.dns

import com.fauxx.util.Clock

/**
 * A small time-bounded cache in front of the proxy's resolver (#227, plan R2).
 *
 * Without it every new tunnel costs a full A+AAAA DNS-over-HTTPS round trip, even for a host
 * looked up a second earlier: slower pages, and many times the query volume, which matters on
 * metered personal resolvers (NextDNS's free tier counts queries). `okhttp-dnsoverhttps` does not
 * expose record TTLs, so answers are kept for a fixed [ttlMs] instead, which is short enough not to
 * pin a CDN to a stale edge for long.
 *
 * Answers are cached, including "no such host" and sinkhole addresses (they are answers too).
 * Failures are not, so a recovering resolver is retried at once. Thread-safe; at most [maxEntries]
 * hosts, oldest evicted first.
 */
class CachingResolver(
    private val delegate: HostResolver,
    private val clock: Clock,
    private val ttlMs: Long = 60_000L,
    private val maxEntries: Int = 512,
) : HostResolver {

    private class Entry(val result: Resolution, val expiresAtMs: Long)

    private val entries = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > maxEntries
    }

    override fun resolve(host: String): Resolution {
        val key = host.lowercase()
        val now = clock.elapsedRealtime()
        synchronized(entries) {
            entries[key]?.let { if (now < it.expiresAtMs) return it.result else entries.remove(key) }
        }
        val result = delegate.resolve(host)
        if (result !is Resolution.Failure) {
            synchronized(entries) { entries[key] = Entry(result, clock.elapsedRealtime() + ttlMs) }
        }
        return result
    }
}
