package com.fauxx.engine.webview

import androidx.webkit.WebViewFeature

/**
 * What the installed WebView lets Fauxx do, for code that has to act on or explain its limits.
 * Support depends on the installed WebView APK, not the Android version, and a WebView update
 * restarts the app process, so an answer holds for the life of the process.
 */
interface WebViewCapabilities {

    /**
     * Whether the pool can stop `X-Requested-With` from naming Fauxx on every request. False means
     * every site Fauxx visits can see the requests come from `com.fauxx.full`, until the user
     * updates their WebView.
     */
    fun canHidePackageName(): Boolean

    companion object {
        /** The real answer from the installed WebView. */
        val SYSTEM: WebViewCapabilities = object : WebViewCapabilities {
            override fun canHidePackageName(): Boolean = supportsPackageNameHiding()
        }

        /**
         * The pool blanks the header with a per-profile custom header, which needs both features.
         * Measured: WebView 151 has both; WebView 113 (the API 34 AOSP image) has neither.
         */
        fun supportsPackageNameHiding(): Boolean =
            isSupported(WebViewFeature.MULTI_PROFILE) && isSupported(WebViewFeature.CUSTOM_REQUEST_HEADERS)

        /** Feature check that answers false instead of throwing where no WebView provider exists (Robolectric). */
        fun isSupported(feature: String): Boolean =
            runCatching { WebViewFeature.isFeatureSupported(feature) }.getOrDefault(false)
    }
}
