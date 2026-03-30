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

private const val SCRAPE_TIMEOUT_MS = 30_000L
private const val ADS_SETTINGS_URL = "https://adssettings.google.com/authenticated"

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
        return suspendCancellableCoroutine { cont ->
            val bridge = object {
                @JavascriptInterface
                fun onResult(result: String) {
                    val categories = result.split("|||")
                        .filter { it.isNotBlank() }
                        .toSet()
                    cont.resume(categories)
                }

                @JavascriptInterface
                fun onError(error: String) {
                    Timber.d("JS error: $error")
                    cont.resume(emptySet())
                }
            }

            webView.addJavascriptInterface(bridge, "FauxxBridge")
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
    }
}
