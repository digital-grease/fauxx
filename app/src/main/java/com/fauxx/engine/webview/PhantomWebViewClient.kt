package com.fauxx.engine.webview

import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import androidx.annotation.RequiresApi
import timber.log.Timber
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
    // Issue #268: invoked with a short description when the MAIN FRAME fails to load (DNS failure,
    // connection refused, HTTP 4xx/5xx). Without this the failure was logged and dropped, and the
    // module still recorded the action as a success — a DNS-blocked load (Pi-hole and friends,
    // which most Fauxx users run) produced an error page but a "Success" action-log line.
    private val onMainFrameError: ((String) -> Unit)? = null
) : WebViewClient() {

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
        return null
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame != true) return
        val description = error?.description ?: "unknown error"
        val code = error?.errorCode ?: 0
        Timber.w("WebView load error on ${request.url} (code=$code): $description")
        onMainFrameError?.invoke("net($code): $description")
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
        onMainFrameError?.invoke("http($status)")
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
        val host = request.url.host ?: return true
        if (blocklist.isBlocked(host)) {
            Timber.d("Blocked navigation to: $host")
            return true
        }
        return false
    }
}
