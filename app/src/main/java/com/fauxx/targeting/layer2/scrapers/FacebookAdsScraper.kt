package com.fauxx.targeting.layer2.scrapers

import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.coroutines.resume

private const val TAG = "FacebookAdsScraper"
private const val SCRAPE_TIMEOUT_MS = 30_000L
private const val AD_PREFS_URL = "https://www.facebook.com/adpreferences/ad_topics"

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
            Log.w(TAG, "Facebook scrape timed out")
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
                    Log.d(TAG, "JS error: $error")
                    cont.resume(emptySet())
                }
            }

            webView.addJavascriptInterface(bridge, "FauxxBridge")
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
    }
}
