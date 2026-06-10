package com.fauxx.engine.modules

import com.google.gson.Gson
import java.net.URI
import kotlin.random.Random

/**
 * E5 (#175): selects result links from a SERP's anchor list that fauxx is willing to
 * visit, and builds the JS that "clicks" them.
 *
 * Pure logic, separated from the WebView so the gating is unit-testable. Two gates,
 * both required:
 *  - [select]'s `isAllowed` is the PRIMARY gate: an ALLOW-list of curated-corpus hosts
 *    (CrawlListManager.isCuratedHost). Live SERPs surface arbitrary destinations, and
 *    fauxx's blocklist is a finite named denylist, not a content classifier — a visited
 *    URL is itself a profile entry, so only destinations already reviewed for page
 *    visits may be navigated to.
 *  - `isBlocked` (DomainBlocklist.isUrlBlocked) re-gates the survivors as
 *    defense-in-depth, and PhantomWebViewClient re-checks every navigation/redirect.
 *
 * Engines that wrap organic results in same-host redirectors (Bing's /ck/a, Yahoo)
 * yield no followable candidates — the wrapper host is engine-family and is excluded —
 * so sessions on those engines simply skip the click step. Candidates keep DOM order
 * and the pick is position-biased toward the top, like real click-through curves.
 */
object SerpLinkSelector {

    private val gson = Gson()

    /**
     * Search-engine and ad/infra domain FAMILIES, matched against whole host labels
     * ("blog.google" and "ads.google.com" match "google"; "googleblog.com" does not).
     */
    private val ENGINE_HOST_FAMILIES = setOf(
        "google", "gstatic", "googleadservices", "googlesyndication", "doubleclick",
        "youtube", "bing", "microsoft", "msn", "live",
        "duckduckgo", "yahoo", "yimg", "yandex",
    )

    /** Probability of advancing past each candidate: ~55% of clicks land on the first. */
    private const val POSITION_ADVANCE_P = 0.45f

    /**
     * Parse the result of evaluating the link-collection JS. `evaluateJavascript`
     * yields a JSON-encoded STRING whose content is itself a JSON array of hrefs;
     * either decode step failing yields an empty list, never a throw.
     */
    fun parseHrefs(evalResult: String?): List<String> = runCatching {
        val inner: String = gson.fromJson(evalResult, String::class.java) ?: return emptyList()
        gson.fromJson(inner, Array<String>::class.java)?.toList().orEmpty()
    }.getOrDefault(emptyList())

    /**
     * Pick up to [max] result links: http(s) only, parseable host, no engine/ad-infra
     * host families, one link per host, [isAllowed] host allow-list, [isBlocked] URL
     * denylist, position-biased toward earlier (higher-ranked) results.
     */
    fun select(
        hrefs: List<String>,
        random: Random,
        max: Int,
        isAllowed: (host: String) -> Boolean,
        isBlocked: (url: String) -> Boolean,
    ): List<String> {
        if (max <= 0) return emptyList()
        val pool = hrefs.asSequence()
            .filter { it.startsWith("https://") || it.startsWith("http://") }
            .mapNotNull { href ->
                val host = runCatching { URI(href).host }.getOrNull() ?: return@mapNotNull null
                href to host.lowercase()
            }
            .filterNot { (_, host) -> host.split('.').any { it in ENGINE_HOST_FAMILIES } }
            .distinctBy { (_, host) -> host }
            .filter { (_, host) -> isAllowed(host) }
            .map { (href, _) -> href }
            .filterNot(isBlocked)
            .toMutableList()

        val picked = mutableListOf<String>()
        while (picked.size < max && pool.isNotEmpty()) {
            var index = 0
            while (index < pool.size - 1 && random.nextFloat() < POSITION_ADVANCE_P) index++
            picked += pool.removeAt(index)
        }
        return picked
    }

    /** Collects up to 60 absolute http(s) anchor hrefs from the loaded document. */
    const val COLLECT_LINKS_JS =
        "(function(){var a=[];var l=document.links;" +
            "for(var i=0;i<l.length&&a.length<60;i++){var h=l[i].href;" +
            "if(h&&h.indexOf('http')===0){a.push(h);}}return JSON.stringify(a);})()"

    /**
     * JS that CLICKS the anchor whose resolved href equals [href], rather than
     * loading the URL programmatically: a real click fires the SERP's own click
     * instrumentation (ping attributes, beacons, redirect interstitials) and produces
     * genuine navigation/referrer semantics — a direct loadUrl of a harvested href is
     * an impression with no click, which is itself a bot tell. The resulting
     * navigation still passes PhantomWebViewClient's blocklist re-gate.
     */
    fun buildClickJs(href: String): String {
        val escaped = href.replace("\\", "\\\\").replace("'", "\\'")
        return "(function(){var l=document.links;for(var i=0;i<l.length;i++){" +
            "if(l[i].href==='$escaped'){l[i].click();return 'clicked';}}return 'missing';})()"
    }
}
