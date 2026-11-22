package com.fauxx.engine.webview

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Takes the hosts a page visit asked for, for [PageDependencySource] to replay as DNS noise
 * (#322 item 6). The pool calls it from the WebView's request thread.
 */
interface PageDependencySink {
    /** A visit to a page on [pageHost] requested [hosts] (its own host included or not). */
    fun record(pageHost: String, hosts: Collection<String>)

    companion object {
        val NONE = object : PageDependencySink {
            override fun record(pageHost: String, hosts: Collection<String>) = Unit
        }
    }
}

/** The other hosts a page on a given host loads, for a DNS burst that mirrors a real page load. */
interface PageDependencySource {
    /** Hosts a page on [host] loads besides its own, in the order a page asks for them; may be empty. */
    fun dependenciesOf(host: String, random: Random): List<String>

    companion object {
        /** No dependencies: a DNS action looks up the site alone, as before #322. */
        val NONE = object : PageDependencySource {
            override fun dependenciesOf(host: String, random: Random) = emptyList<String>()
        }
    }
}

/**
 * What each site's pages load (#322 item 6), learned from Fauxx's own page visits.
 *
 * A real page load makes a burst of lookups within about a second: the site, its CDN, fonts,
 * analytics and ad hosts. An observer can tell standalone lookups of single domains from browsing,
 * and can even pick the real site out of such a burst by its dependency set (Herrmann, Maaß and
 * Federrath, IFIP SEC 2014). The DNS noise module replays a site's set as a burst, so it reads as a
 * page load.
 *
 * The sets come from the requests the pool's WebViews made on real visits (cookie and ad pages,
 * search results and the sites they open), in the order first asked for, capped at [MAX_HOSTS]
 * per site and [MAX_SITES] sites, the least recently used dropped first. They hold only hosts
 * Fauxx's own synthetic visits asked for, live in memory only, and are rebuilt as Fauxx browses.
 * A site not visited yet gets a few hosts from [TYPICAL], ones pages across the web commonly load.
 */
@Singleton
class PageDependencies @Inject constructor() : PageDependencySink, PageDependencySource {

    private val learned = object : LinkedHashMap<String, List<String>>(MAX_SITES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > MAX_SITES
    }

    override fun record(pageHost: String, hosts: Collection<String>) {
        val page = normalize(pageHost)
        val others = hosts.map(::normalize).filter { it.isNotEmpty() && it != page }.distinct().take(MAX_HOSTS)
        if (page.isEmpty() || others.isEmpty()) return
        synchronized(learned) { learned[page] = others }
    }

    override fun dependenciesOf(host: String, random: Random): List<String> {
        val known = synchronized(learned) { learned[normalize(host)] }
        if (!known.isNullOrEmpty()) return known
        return TYPICAL.shuffled(random).take(random.nextInt(TYPICAL_COUNT.first, TYPICAL_COUNT.last + 1))
    }

    private fun normalize(host: String) = host.lowercase().trim().trimEnd('.')

    companion object {
        const val MAX_SITES = 300
        const val MAX_HOSTS = 25
        private val TYPICAL_COUNT = 3..8

        /**
         * Third-party hosts that pages across the web commonly load: fonts, script CDNs, tag
         * managers, analytics, consent banners and ad serving. Every entry is a live host pages
         * request directly, so none answers NXDOMAIN.
         */
        val TYPICAL = listOf(
            "fonts.googleapis.com", "fonts.gstatic.com", "www.gstatic.com", "ajax.googleapis.com",
            "www.googletagmanager.com", "www.google-analytics.com", "cdnjs.cloudflare.com", "cdn.jsdelivr.net",
            "static.cloudflareinsights.com", "connect.facebook.net", "securepubads.g.doubleclick.net",
            "pagead2.googlesyndication.com", "tpc.googlesyndication.com", "cdn.cookielaw.org",
            "consent.cookiebot.com", "use.typekit.net", "static.hotjar.com", "i.ytimg.com",
        )
    }
}

/**
 * The hosts one pooled WebView's current visit has asked for (#322 item 6). The page's host is
 * the latest main-frame request's; a new main-frame navigation, or [flush] at release, hands the
 * visit's hosts to the [sink]. Called from the WebView's request thread and from release.
 */
internal class VisitHosts(private val sink: PageDependencySink) {
    private var pageHost: String? = null
    private val hosts = LinkedHashSet<String>()

    @Synchronized
    fun onRequest(host: String, mainFrame: Boolean) {
        if (mainFrame) {
            flushLocked()
            pageHost = host
        } else if (pageHost != null && hosts.size < PageDependencies.MAX_HOSTS) {
            hosts += host
        }
    }

    @Synchronized
    fun flush() = flushLocked()

    private fun flushLocked() {
        val page = pageHost
        if (page != null && hosts.isNotEmpty()) runCatching { sink.record(page, hosts.toList()) }
        pageHost = null
        hosts.clear()
    }
}
