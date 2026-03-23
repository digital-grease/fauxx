package com.fauxx.network

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp interceptor that rotates User-Agent and randomizes other HTTP headers to reduce
 * fingerprinting consistency across requests.
 *
 * Applied to all OkHttp requests made by Fauxx modules. Does not modify requests made by
 * WebView (those are handled by [com.fauxx.engine.webview.PhantomWebViewClient]).
 */
@Singleton
class HeaderRandomizerInterceptor @Inject constructor(
    private val uaPool: UserAgentPool
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val request = original.newBuilder()
            .header("User-Agent", uaPool.random())
            .header("Accept", randomAccept())
            .header("Accept-Language", randomAcceptLanguage())
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("DNT", "1")
            .build()
        return chain.proceed(request)
    }

    private fun randomAccept(): String = ACCEPT_VARIANTS.random()

    private fun randomAcceptLanguage(): String = LANGUAGE_VARIANTS.random()

    companion object {
        private val ACCEPT_VARIANTS = listOf(
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8"
        )
        private val LANGUAGE_VARIANTS = listOf(
            "en-US,en;q=0.9",
            "en-US,en;q=0.8",
            "en-GB,en;q=0.9",
            "en-US,en;q=0.9,es;q=0.8",
            "en-US,en;q=0.9,fr;q=0.7"
        )
    }
}
