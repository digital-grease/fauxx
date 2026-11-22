package com.fauxx.engine.webview

import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import androidx.annotation.RequiresApi
import timber.log.Timber
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fauxx.data.crawllist.DomainBlocklist
import java.util.concurrent.atomic.AtomicInteger

/** MIME types that should not be loaded in background WebViews. */
private val BLOCKED_MIME_TYPES = setOf(
    "application/pdf", "application/zip", "application/octet-stream",
    "video/", "audio/", "application/x-download"
)

/**
 * Custom WebViewClient for background Fauxx WebView instances.
 *
 * - Blocks dangerous/non-HTML content types
 * - Checks all URLs against [DomainBlocklist]
 * - Injects the Global Privacy Control DOM signal ([JSInjector.PAGE_SCRIPT]) when the pool could
 *   not register it as a document-start script
 * - Handles SSL errors conservatively (aborts on error rather than proceeding)
 * - Counts requests to tracker hosts and their network-level failures (#341, [TrackerVisitCounter])
 */
class PhantomWebViewClient(
    private val blocklist: DomainBlocklist,
    // Issue #73: incremented for each allowed (non-blocked) resource request so the pool can
    // report a "resources loaded" count in the action-log metadata. Null = don't count.
    private val resourceCounter: AtomicInteger? = null,
    private val onPageFinished: ((String) -> Unit)? = null,
    // Issue #210: invoked with the affected WebView when its renderer process dies, so the pool
    // can destroy the broken instance and swap in a fresh one. onRenderProcessGone ALWAYS returns
    // true regardless, so Android never terminates the whole app process on a renderer death.
    private val onRenderGone: ((WebView) -> Unit)? = null,
    // False when the pool registered JSInjector.PAGE_SCRIPT as a document-start script, which runs
    // earlier and in every frame. True is the fallback for WebViews without DOCUMENT_START_SCRIPT.
    private val injectOnPageStarted: Boolean = true,
    // Issue #268: told when the MAIN FRAME fails to load (DNS failure, connection refused, HTTP
    // 4xx/5xx). Without this the failure was logged and dropped, and the module still recorded the
    // action as a success: a DNS-blocked load (Pi-hole and friends, which most Fauxx users run)
    // produced an error page but a "Success" action-log line. Issue #353: also told when each
    // main-frame navigation starts, so a later navigation that loads (a passed bot challenge)
    // replaces an earlier failure instead of being shadowed by it.
    private val mainFrame: MainFrameLoadTracker? = null,
    // Issue #227: answers the loopback DNS proxy's password challenge, and nothing else's.
    private val proxyAuth: PhantomProxyAuth = PhantomProxyAuth.NONE,
    // Issue #341: counts this visit's requests to tracker hosts and how many of them fail at the
    // network level, so the dashboard can say when a tracker blocker is discarding Fauxx's effect.
    // Null = don't count.
    private val trackerCounter: TrackerVisitCounter? = null,
    // Issue #341: whether the pool has images off for this visit. Image requests are then never
    // fetched, so they must not count as tracker attempts (or, worse, as blocked ones).
    private val imagesBlocked: () -> Boolean = { true },
    // #322 item 6: told the host of every allowed request (and whether it is the main frame), so
    // the pool can learn what a site's pages load for DNS noise that mirrors a page load.
    private val onRequestHost: ((host: String, mainFrame: Boolean) -> Unit)? = null,
) : WebViewClient() {

    /**
     * Issue #227: the custom-DNS loopback proxy demands a per-run password so other apps on the
     * device cannot use it to slip past a firewall. Answer only the proxy's own challenge; any
     * other HTTP auth prompt is cancelled, as WebView does by default.
     */
    override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
        val credentials = proxyAuth.credentialsFor(host, realm)
        if (credentials != null) handler.proceed(credentials.first, credentials.second) else handler.cancel()
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (!injectOnPageStarted) return
        view.evaluateJavascript(JSInjector.PAGE_SCRIPT) { result ->
            if (result != null && result != "null" && result.contains("error", ignoreCase = true)) {
                Timber.w("JS injection may have failed on $url: $result")
            }
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        onPageFinished?.invoke(url)
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val url = request.url.toString()
        // #353: every http(s) main-frame navigation passes through here exactly once (WebView
        // skips interception only for redirects, content:// and android_asset URLs), so this is
        // where a new navigation, with an outcome not known yet, begins.
        mainFrame?.let { if (request.isForMainFrame && request.url.isWebScheme()) it.onNavigationStart() }
        val host = request.url.host ?: return null

        // Block domains on the blocklist
        if (blocklist.isBlocked(host)) {
            Timber.d("Blocked request to: $host")
            return WebResourceResponse("text/plain", "utf-8", null)
        }

        // Block non-HTML/non-essential content types
        val acceptHeader = request.requestHeaders["Accept"] ?: ""
        if (BLOCKED_MIME_TYPES.any { acceptHeader.contains(it) }) {
            return WebResourceResponse("text/plain", "utf-8", null)
        }

        // Allowed resource — count it for the "resources loaded" action-log metadata (issue #73).
        resourceCounter?.incrementAndGet()
        if (request.url.isWebScheme()) onRequestHost?.invoke(host, request.isForMainFrame)
        trackerCounter?.let { counter ->
            if (isTrackerCandidate(request, acceptHeader)) counter.recordAttempt(url, host)
        }
        return null
    }

    /**
     * Issue #341: whether an allowed request can count as a tracker attempt at all. Only https:
     * the pool never allows mixed content, so an http:// request from an https page is refused by
     * WebView itself and its error would look like a block. Not images while images are off,
     * because WebView never fetches them. Everything Fauxx intercepts above never gets here.
     */
    private fun isTrackerCandidate(request: WebResourceRequest, acceptHeader: String): Boolean {
        if (!request.url.scheme.equals("https", ignoreCase = true)) return false
        return !(acceptHeader.startsWith("image/") && imagesBlocked())
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        // Issue #341: any request, main frame or not. The counter ignores URLs it never counted
        // as tracker attempts, so this is a map lookup for everything else.
        if (request != null && error != null) {
            trackerCounter?.recordError(request.url.toString(), error.errorCode, error.description)
        }
        if (request?.isForMainFrame != true) return
        trackerCounter?.markMainFrameFailed()
        val description = error?.description?.toString()
        val code = error?.errorCode ?: 0
        Timber.w("WebView load error on ${request.url} (code=$code): $description")
        mainFrame?.onFailure(LoadFailureClassifier.fromNetwork(code, description))
    }

    /**
     * Issue #268: an HTTP error status on the main frame is a failed load too. The server answered,
     * so [onReceivedError] does not fire, but the page the module dwelled on is an error page, not
     * the content it meant to visit. Sub-resource 4xx/5xx are ignored — those are routine on real
     * pages and say nothing about whether the visit itself worked.
     */
    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        if (request?.isForMainFrame != true) return
        val status = errorResponse?.statusCode ?: return
        if (status < 400) return
        Timber.w("WebView HTTP error on ${request.url}: $status")
        mainFrame?.onFailure(LoadFailureClassifier.fromHttp(status, runCatching { errorResponse.responseHeaders }.getOrNull()))
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        // A renderer-process death (Chromium renderer OOM, or the OS evicting a backgrounded
        // renderer — common on memory-constrained/foldable devices and hardened OSes like
        // GrapheneOS) takes down the ENTIRE app process unless this returns true. Issue #210:
        // the SIGTRAP abort "Render process crash wasn't handled by all associated webviews,
        // triggering application crash". Handle it: log, hand the dead instance to the pool for
        // replacement, and tell Android we recovered so the app keeps running.
        Timber.w("WebView renderer gone (didCrash=${detail.didCrash()}); recovering pool slot instead of crashing")
        onRenderGone?.invoke(view)
        return true
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        // Never proceed on SSL errors — abort the request
        Timber.w("SSL error on ${error.url}, aborting")
        handler.cancel()
    }

    @RequiresApi(Build.VERSION_CODES.O_MR1)
    override fun onSafeBrowsingHit(
        view: WebView,
        request: WebResourceRequest,
        threatType: Int,
        callback: SafeBrowsingResponse
    ) {
        // Silently back away from any URL flagged by Safe Browsing — no interstitial needed
        // in a background WebView, just stop loading.
        Timber.w("Safe Browsing hit (threat=$threatType) on ${request.url}, backing to safety")
        callback.backToSafety(false)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        // #353: only http(s) is ever loaded. A page that sends a Chrome-on-Android visitor to its
        // app (intent://, market://, a custom scheme with a host) used to get through here, and
        // WebView then failed the main frame with ERR_UNKNOWN_URL_SCHEME, failing a visit whose
        // page had loaded fine. Refusing the navigation leaves the page in place, like Chrome
        // does when the app is not installed.
        val refuse = !request.url.isWebScheme() || request.url.host.let { it == null || blocklist.isBlocked(it) }
        if (!refuse) return false
        Timber.d("Refused navigation (scheme=${request.url.scheme})")
        // A refused SERVER redirect of the main frame means the page itself never loads. WebView
        // reports nothing for it (ERR_ABORTED is never passed on), so say so here.
        mainFrame?.let { tracker ->
            if (request.isForMainFrame && runCatching { request.isRedirect }.getOrDefault(false)) {
                tracker.onFailure(LoadFailureClassifier.refusedRedirect())
            }
        }
        return true
    }

    private fun Uri.isWebScheme(): Boolean =
        scheme.equals("https", ignoreCase = true) || scheme.equals("http", ignoreCase = true)
}
