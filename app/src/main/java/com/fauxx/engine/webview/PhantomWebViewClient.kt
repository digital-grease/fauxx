package com.fauxx.engine.webview

import android.graphics.Bitmap
import android.net.http.SslError
import android.util.Log
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fauxx.data.crawllist.DomainBlocklist

private const val TAG = "PhantomWebViewClient"

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
        view.evaluateJavascript(JSInjector.ALL_SCRIPTS, null)
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
            Log.d(TAG, "Blocked request to: $host")
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
        Log.w(TAG, "SSL error on ${error.url}, aborting")
        handler.cancel()
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val host = request.url.host ?: return true
        if (blocklist.isBlocked(host)) {
            Log.d(TAG, "Blocked navigation to: $host")
            return true
        }
        return false
    }
}
