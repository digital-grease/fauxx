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

// 12s is well above the legitimate signed-in path and bounds the worst-case
// Main-thread lag for the user. The previous 30s value compounded with
// GoogleAdsScraper to a 60-second user-perceived freeze (#44).
private const val SCRAPE_TIMEOUT_MS = 12_000L
private const val AD_PREFS_URL = "https://www.facebook.com/adpreferences/ad_topics"
private const val FAUXX_BRIDGE = "FauxxBridge"

/**
 * Reads the user's Facebook/Meta ad interest categories from facebook.com/adpreferences.
 * Extracts categories from the Ad Topics section.
 *
 * SAFETY: Read-only. Never clicks, never modifies settings.
 * Requires the user to be logged into Facebook in the scraper WebView.
 */
class FacebookAdsScraper @Inject constructor() : PlatformScraper {

    override val platformId: String = "facebook"

    override suspend fun scrape(webView: WebView): Set<String> {
        return withTimeoutOrNull(SCRAPE_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                extractCategories(webView)
            }
        } ?: run {
            Timber.w("Facebook scrape timed out")
            emptySet()
        }
    }

    private suspend fun extractCategories(webView: WebView): Set<String> {
        try {
            return suspendCancellableCoroutine { cont ->
                // Resume-once gate: a late `window.FauxxBridge.onResult(...)` call
                // (e.g. JS that fires after a timeout has already cancelled the
                // continuation) would otherwise throw IllegalStateException.
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

                // Cleanup on cancellation (timeout) — stops the WebView's lingering
                // page work so the next scraper / about:blank doesn't queue behind
                // it (#44) and removes the bridge so a stale callback can't resume
                // a dead coroutine (#6).
                cont.invokeOnCancellation {
                    runCatching {
                        webView.stopLoading()
                        webView.removeJavascriptInterface(FAUXX_BRIDGE)
                    }
                }

                webView.addJavascriptInterface(bridge, FAUXX_BRIDGE)
                webView.loadUrl(AD_PREFS_URL)

                webView.evaluateJavascript("""
                    (function() {
                        function extract() {
                            try {
                                // Try to find ad topic items in the preferences page
                                var items = document.querySelectorAll('[data-pagelet] span');
                                var names = [];
                                items.forEach(function(el) {
                                    var text = el.textContent.trim();
                                    if (text.length > 2 && text.length < 50) {
                                        names.push(text);
                                    }
                                });
                                if (names.length === 0) {
                                    // Fallback: grab list items in the ad topics section
                                    var listItems = document.querySelectorAll('li');
                                    listItems.forEach(function(li) {
                                        var text = li.textContent.trim();
                                        if (text.length > 2 && text.length < 50) names.push(text);
                                    });
                                }
                                window.FauxxBridge.onResult(names.slice(0, 100).join('|||'));
                            } catch(e) {
                                window.FauxxBridge.onError(e.message);
                            }
                        }
                        setTimeout(extract, 3000);
                    })();
                """.trimIndent(), null)
            }
        } finally {
            // Belt-and-braces cleanup on the happy path. Safe to call even when
            // invokeOnCancellation already ran — removeJavascriptInterface on a
            // missing key is a no-op.
            runCatching { webView.removeJavascriptInterface(FAUXX_BRIDGE) }
        }
    }
}
