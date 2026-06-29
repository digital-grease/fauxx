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
 * - Injects fingerprint-noise JavaScript on page start
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
    private val onRenderGone: ((WebView) -> Unit)? = null
) : WebViewClient() {

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        // On high-scrutiny endpoints (search engines) inject only the benign GPC signal;
        // the automation-shaped overrides are themselves a detection tell there (#168/#169).
        val scripts = if (isHighScrutiny(url)) JSInjector.MINIMAL_SCRIPTS else JSInjector.ALL_SCRIPTS
        view.evaluateJavascript(scripts) { result ->
            if (result != null && result != "null" && result.contains("error", ignoreCase = true)) {
                Timber.w("JS injection may have failed on $url: $result")
            }
        }
    }

    private fun isHighScrutiny(url: String): Boolean {
        val host = runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: return false
        return HIGH_SCRUTINY_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }
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

    companion object {
        /**
         * Hosts where the automation-shaped JSInjector overrides are suppressed because the
         * endpoint runs aggressive bot-detection (search engines, #168/#169). Mirrors the
         * SEARCH_ENGINES in SearchPoisonModule.
         */
        val HIGH_SCRUTINY_HOST_SUFFIXES = setOf(
            "google.com", "bing.com", "duckduckgo.com", "yahoo.com", "yandex.com"
        )
    }
}
