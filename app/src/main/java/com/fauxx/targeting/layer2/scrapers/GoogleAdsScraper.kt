package com.fauxx.targeting.layer2.scrapers

import timber.log.Timber
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.coroutines.resume

// 12s is well above the legitimate signed-in path (page + JS extract finishes in ~2-4s
// in normal cases) and bounds the worst-case Main-thread lag for the user. The previous
// 30s value compounded with two scrapers to a 60-second user-perceived freeze (#44).
private const val SCRAPE_TIMEOUT_MS = 12_000L
private const val ADS_SETTINGS_URL = "https://adssettings.google.com/authenticated"
private const val FAUXX_BRIDGE = "FauxxBridge"

/**
 * Reads the user's Google ad interest categories from adssettings.google.com.
 * Extracts interest chips via JavaScript: document.querySelectorAll('[data-topic]')
 *
 * SAFETY: Read-only. Never clicks, never modifies settings.
 * Requires the user to be logged into Google in the scraper WebView.
 */
class GoogleAdsScraper @Inject constructor() : PlatformScraper {

    override val platformId: String = "google"

    override suspend fun scrape(webView: WebView): Set<String> {
        return withTimeoutOrNull(SCRAPE_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                extractCategories(webView)
            }
        } ?: run {
            Timber.w("Scrape timed out after ${SCRAPE_TIMEOUT_MS}ms")
            emptySet()
        }
    }

    private suspend fun extractCategories(webView: WebView): Set<String> {
        try {
            return suspendCancellableCoroutine { cont ->
            // Resume-once gate: protects against the bridge firing after a timeout
            // cancellation has already cleaned up. Without this, a late
            // `window.FauxxBridge.onResult(...)` call could resume a continuation
            // that's already cancelled, throwing IllegalStateException.
            val resumed = java.util.concurrent.atomic.AtomicBoolean(false)
            fun resumeOnce(value: Set<String>) {
                if (resumed.compareAndSet(false, true)) cont.resume(value)
            }

            val bridge = object {
                @JavascriptInterface
                fun onResult(result: String) {
                    val categories = result.split("|||")
                        .filter { it.isNotBlank() }
                        .toSet()
                    resumeOnce(categories)
                }

                @JavascriptInterface
                fun onError(error: String) {
                    Timber.d("JS error: $error")
                    resumeOnce(emptySet())
                }
            }

            // Cleanup runs on both the cancellation path (timeout) and on normal
            // completion. Removes the JavascriptInterface so a stale callback from
            // a later page load can't resume a dead coroutine (#6) and stops the
            // WebView's lingering page work so the next scraper / about:blank
            // navigation doesn't queue behind it (#44).
            cont.invokeOnCancellation {
                runCatching {
                    webView.stopLoading()
                    webView.removeJavascriptInterface(FAUXX_BRIDGE)
                }
            }

            webView.addJavascriptInterface(bridge, FAUXX_BRIDGE)
            webView.loadUrl(ADS_SETTINGS_URL)

            webView.evaluateJavascript("""
                (function() {
                    function extract() {
                        try {
                            var topics = document.querySelectorAll('[data-topic]');
                            if (topics.length === 0) {
                                // Try alternate selectors for different page versions
                                topics = document.querySelectorAll('.U3FZlb');
                            }
                            var names = [];
                            topics.forEach(function(el) {
                                var name = el.getAttribute('data-topic') || el.textContent.trim();
                                if (name) names.push(name);
                            });
                            window.FauxxBridge.onResult(names.join('|||'));
                        } catch(e) {
                            window.FauxxBridge.onError(e.message);
                        }
                    }
                    // Wait for dynamic content
                    if (document.readyState === 'complete') {
                        setTimeout(extract, 2000);
                    } else {
                        window.addEventListener('load', function() {
                            setTimeout(extract, 2000);
                        });
                    }
                })();
            """.trimIndent(), null)
            }
        } finally {
            // Belt-and-braces cleanup that ALSO runs on the happy path (the
            // invokeOnCancellation above only fires when the coroutine is
            // cancelled). Without this, a successful scrape would leave the
            // bridge attached for the next page navigation to invoke (#6).
            // Safe to call even when invokeOnCancellation already ran:
            // removeJavascriptInterface on a missing key is a no-op.
            runCatching { webView.removeJavascriptInterface(FAUXX_BRIDGE) }
        }
    }
}
