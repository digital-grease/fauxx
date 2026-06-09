package com.fauxx.engine.webview

/**
 * HTTP headers attached to every synthetic main-frame WebView load.
 *
 * `Sec-GPC: 1` is the Global Privacy Control signal, a lawful, machine-readable
 * opt-out of sale and sharing. It is a fixed value and is never randomized, unlike
 * the anti-fingerprint headers in [com.fauxx.network.HeaderRandomizerInterceptor]
 * (which covers the OkHttp path). The matching DOM signal
 * (`navigator.globalPrivacyControl`) is injected by [JSInjector].
 */
val SYNTHETIC_WEBVIEW_HEADERS: Map<String, String> = mapOf("Sec-GPC" to "1")
