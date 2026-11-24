package com.fauxx.engine.modules

import android.webkit.WebView
import com.fauxx.data.crawllist.CrawlListManager
import com.fauxx.data.crawllist.DomainBlocklist
import com.fauxx.data.querybank.QueryBlocklist
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.engine.scheduling.SessionHabitsSource
import com.fauxx.engine.webview.PhantomWebViewPool
import com.fauxx.util.Clock
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Browses on from a landed page to a few more pages of the same site (#322 item 2).
 *
 * In the history shared in #270, 74% of the navigations inside a session stay on the same site,
 * and pages are mostly reached by following links, often deep ones with query strings. Fauxx
 * loaded one page per site, straight from its list, usually a home or section page. Following the
 * page's own links gives real referrers, deeper paths and query strings without changing the list.
 */
interface SiteBrowsing {
    /**
     * Maybe follow a few same-site links from the page now loaded in [webView], which started
     * loading [sinceLoadMs] ago. With a [deadlineMs] (on the injected clock), no step starts that
     * could run past it. Returns how many pages were followed.
     */
    suspend fun browseOn(webView: WebView, sinceLoadMs: Long, deadlineMs: Long = Long.MAX_VALUE): Int

    companion object {
        /** Never browses on: one page per visit, as before. */
        val NONE = object : SiteBrowsing {
            override suspend fun browseOn(webView: WebView, sinceLoadMs: Long, deadlineMs: Long) = 0
        }
    }
}

/**
 * The [SiteBrowsing] the modules use. Whether a visit browses on and how far are the persona's
 * habits ([com.fauxx.engine.scheduling.SessionHabits.browseChance] and `browseMean`), capped by
 * the tier ([maxPages]) so the longer visits cannot starve the displayed rate (#76).
 *
 * Each step collects the page's links, picks one with [SameSiteLinks], and clicks the page's own
 * anchor, so the navigation, its referrer and the site's click instrumentation are genuine; the
 * WebView client still re-checks every navigation against the blocklist. On a shop's site it never
 * follows a link to a sensitive purchase ([CrawlListManager.isSensitiveShopPage], #322 item 7). Steps on one site are
 * more than [MIN_SAME_SITE_GAP_MS] apart, the crawl list's per-domain minimum, and browsing stops
 * at a page that failed to load (an error page or a bot check), or when no link qualifies.
 */
@Singleton
class SiteBrowser @Inject constructor(
    private val pool: PhantomWebViewPool,
    private val crawlListManager: CrawlListManager,
    private val blocklist: DomainBlocklist,
    private val queryBlocklist: QueryBlocklist,
    private val profileRepo: PoisonProfileRepository,
    private val habits: SessionHabitsSource,
    private val clock: Clock,
    private val random: Random,
) : SiteBrowsing {

    override suspend fun browseOn(webView: WebView, sinceLoadMs: Long, deadlineMs: Long): Int {
        val profile = profileRepo.getProfile()
        val ceiling = maxOf(
            profile.intensity.actionsPerHour,
            profile.mobileIntensity?.actionsPerHour ?: 0,
            profile.chargingIntensity?.actionsPerHour ?: 0,
        )
        val max = maxPages(ceiling)
        if (max == 0) return 0
        val h = runCatching { habits.habits() }.getOrNull() ?: return 0
        if (random.nextDouble() >= h.browseChance) return 0
        val pages = pageCount(h.browseMean, max)

        val visited = mutableSetOf<String>()
        var lastLoadAt = clock.currentTimeMillis() - sinceLoadMs
        var followed = 0
        while (followed < pages) {
            val pageUrl = withContext(Dispatchers.Main) { webView.url } ?: break
            visited += SameSiteLinks.normalize(pageUrl)
            val link = SameSiteLinks.pick(
                SameSiteLinks.candidates(
                    collectLinks(webView), pageUrl, visited,
                    // On a shop's site, never into a section whose purchase intent is sensitive (#322 item 7).
                    isBlocked = { blocklist.isUrlBlocked(it) || crawlListManager.isSensitiveShopPage(it) },
                    isSensitive = { queryBlocklist.isBlockedInAnyLocale(it) },
                ),
                random,
            ) ?: break
            // Below the minimum gap, wait past it by a random margin rather than to it exactly, so
            // the gaps do not pile up at the minimum (#382).
            val short = lastLoadAt + MIN_SAME_SITE_GAP_MS - clock.currentTimeMillis()
            val wait = if (short > 0) short + random.nextLong(0L, GAP_MARGIN_MS) else 0L
            if (clock.currentTimeMillis() + wait + MIN_SAME_SITE_GAP_MS > deadlineMs) break
            if (wait > 0) delay(wait)
            pool.clearLoadError(webView)
            if (!click(webView, link)) break
            lastLoadAt = clock.currentTimeMillis()
            runCatching { URI(link).host }.getOrNull()?.let(crawlListManager::markVisited)
            visited += SameSiteLinks.normalize(link)
            followed++
            delay(dwellMs())
            if (pool.lastLoadFailure(webView) != null) break
        }
        return followed
    }

    /**
     * Reading time on a browsed page: the per-domain minimum plus a lognormal stretch (median
     * [DWELL_STRETCH_MEDIAN_MS]), so it never falls under the minimum and has no spike at it.
     */
    private fun dwellMs(): Long {
        val z = sqrt(-2.0 * ln(random.nextDouble().coerceAtLeast(1e-12))) * cos(2.0 * PI * random.nextDouble())
        val stretch = DWELL_STRETCH_MEDIAN_MS * exp(DWELL_STRETCH_SIGMA * z)
        return MIN_SAME_SITE_GAP_MS + stretch.toLong().coerceAtMost(MAX_DWELL_STRETCH_MS)
    }

    /** 1 plus a geometric count with mean [mean], at most [max]. */
    private fun pageCount(mean: Double, max: Int): Int {
        val more = (1.0 - 1.0 / mean.coerceAtLeast(1.0)).coerceIn(0.0, 0.9)
        var n = 1
        while (n < max && random.nextDouble() < more) n++
        return n
    }

    private suspend fun collectLinks(webView: WebView): List<String> =
        withTimeoutOrNull(EVAL_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    webView.evaluateJavascript(SerpLinkSelector.COLLECT_LINKS_JS) { result ->
                        if (cont.isActive) cont.resume(SerpLinkSelector.parseHrefs(result))
                    }
                }
            }
        } ?: emptyList()

    private suspend fun click(webView: WebView, href: String): Boolean =
        withTimeoutOrNull(EVAL_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    webView.evaluateJavascript(SerpLinkSelector.buildClickJs(href)) { result ->
                        if (cont.isActive) cont.resume(result?.contains("clicked") == true)
                    }
                }
            }
        } ?: false

    companion object {
        /** The crawl list's minimum between two loads on one domain; every browsed page dwells longer. */
        const val MIN_SAME_SITE_GAP_MS = 5_000L

        /** Median reading time beyond the minimum, and its spread. */
        private const val DWELL_STRETCH_MEDIAN_MS = 4_000.0
        private const val DWELL_STRETCH_SIGMA = 0.6
        private const val MAX_DWELL_STRETCH_MS = 40_000L

        /** Most a first step waits past the minimum gap when the landing was read briefly. */
        private const val GAP_MARGIN_MS = 3_000L

        private const val EVAL_TIMEOUT_MS = 5_000L

        /**
         * Most pages browsed on from one landing, by the highest configured tier, as search sessions
         * are sized: two at Low (the battery-sensitive tier), three at Medium, one at High, and none
         * at Max, whose rate needs every action short.
         */
        internal fun maxPages(actionsPerHour: Int): Int = when {
            actionsPerHour <= 12 -> 2
            actionsPerHour <= 60 -> 3
            actionsPerHour <= 200 -> 1
            else -> 0
        }
    }
}

/**
 * Which link on a page to follow next (#322 item 2). Pure, so the gating is unit-testable.
 *
 * A candidate is an http(s) link on the page's own host (with or without `www.`), not a page
 * already seen in this visit, and not one of: account, login, cart, wishlist, order, checkout, subscription,
 * sharing, printing, admin or feed pages, files such as PDFs, images and archives, a URL the
 * domain blocklist blocks, or one whose words match the harmful-query blocklist (a path is often
 * a slug of the page's title). Deeper paths are likelier, since articles sit below the sections
 * that link to them.
 */
object SameSiteLinks {

    private val SKIPPED_WORDS = setOf(
        "login", "logout", "log-in", "log-out", "signin", "sign-in", "signout", "sign-out", "signup", "sign-up",
        "register", "registration", "account", "my-account", "myaccount", "profile", "password", "auth", "oauth",
        "sso", "cart", "basket", "checkout", "subscribe", "unsubscribe", "subscription", "newsletter", "donate",
        "payment", "billing", "share", "print", "email", "admin", "wp-admin", "wp-login", "feed", "rss",
        "preferences", "settings", "privacy-settings", "cookie-settings",
        // Shops (#322 item 7): carts and accounts as Apple, Nike and Zara and the es, fr and ru shops name them.
        "bag", "shopping-bag", "shopping-cart", "wishlist", "favorites", "favourites", "orders", "order-status",
        "panier", "compte", "mon-compte", "connexion", "commande", "commandes", "liste-de-souhaits",
        "carrito", "cesta", "cuenta", "mi-cuenta", "pedido", "pedidos", "iniciar-sesion", "favoritos",
        "korzina", "lk", "zakaz", "zakazy", "izbrannoe", "vhod",
    )

    private val SKIPPED_EXTENSIONS = setOf(
        "pdf", "zip", "rar", "7z", "gz", "apk", "exe", "dmg", "msi", "jpg", "jpeg", "png", "gif", "webp", "svg",
        "mp3", "mp4", "mov", "avi", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "xml", "json", "ics",
    )

    /** [url] without its fragment, for telling pages apart. */
    fun normalize(url: String): String = url.substringBefore('#').removeSuffix("/")

    fun candidates(
        hrefs: List<String>,
        pageUrl: String,
        visited: Set<String>,
        isBlocked: (String) -> Boolean,
        isSensitive: (String) -> Boolean,
    ): List<String> {
        val pageHost = hostOf(pageUrl) ?: return emptyList()
        return hrefs.asSequence()
            .filter { it.startsWith("https://") || it.startsWith("http://") }
            .filter { hostOf(it) == pageHost }
            .map { it.substringBefore('#') }
            .distinctBy(::normalize)
            .filterNot { normalize(it) in visited || normalize(it) == normalize(pageUrl) }
            .filterNot(::isSkipped)
            .filterNot(isBlocked)
            .filterNot { isSensitive(wordsOf(it)) }
            .toList()
    }

    /** One of [candidates], deeper paths likelier, or null when there is none. */
    fun pick(candidates: List<String>, random: Random): String? {
        if (candidates.isEmpty()) return null
        val weights = candidates.map { 1.0 + depthOf(it).coerceAtMost(MAX_DEPTH_WEIGHT) }
        var point = random.nextDouble() * weights.sum()
        for ((link, w) in candidates.zip(weights)) {
            point -= w
            if (point < 0) return link
        }
        return candidates.last()
    }

    private const val MAX_DEPTH_WEIGHT = 3

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host }.getOrNull()?.lowercase()?.removePrefix("www.")

    private fun depthOf(url: String): Int =
        runCatching { URI(url).path }.getOrNull().orEmpty().split('/').count { it.isNotBlank() }

    private fun isSkipped(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return true
        val segments = uri.path.orEmpty().lowercase().split('/').filter { it.isNotBlank() }
        if (segments.any { it in SKIPPED_WORDS }) return true
        if (segments.lastOrNull()?.substringAfterLast('.', "") in SKIPPED_EXTENSIONS) return true
        val query = uri.rawQuery.orEmpty().lowercase()
        return SKIPPED_WORDS.any { word -> query.contains("$word=") || query.contains("=$word") }
    }

    /** The words of [url]'s path and query, as a slug would read, for the harmful-phrase check. */
    private fun wordsOf(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val raw = listOfNotNull(uri.rawPath, uri.rawQuery).joinToString(" ")
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        return decoded.replace(Regex("[-_/+.=&?]+"), " ").trim()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SiteBrowsingModule {
    @Binds
    abstract fun bindSiteBrowsing(browser: SiteBrowser): SiteBrowsing
}
