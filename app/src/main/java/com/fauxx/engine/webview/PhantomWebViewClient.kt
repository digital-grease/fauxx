package com.fauxx.engine.webview

import android.graphics.Bitmap
import android.net.http.SslError
import timber.log.Timber
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fauxx.data.crawllist.DomainBlocklist

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
    private val onPageFinished: ((String) -> Unit)? = null
) : WebViewClient() {

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        // Inject fingerprint noise scripts
        view.evaluateJavascript(JSInjector.ALL_SCRIPTS) { result ->
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

        return null
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        // Never proceed on SSL errors — abort the request
        Timber.w("SSL error on ${error.url}, aborting")
        handler.cancel()
    }

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
