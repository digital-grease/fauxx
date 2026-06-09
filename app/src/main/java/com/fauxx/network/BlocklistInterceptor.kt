package com.fauxx.network

import com.fauxx.data.crawllist.DomainBlocklist
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fail-closed blocklist gate for the OkHttp path. Every request made through the
 * injected [OkHttpClient] flows through this interceptor, so no module can issue an
 * HTTP request to a blocked host even if a future call site forgets an upstream
 * check. The WebView path is gated separately by
 * [com.fauxx.engine.webview.PhantomWebViewClient]; together these are the single
 * enforced chokepoints for the blocklist invariant (issue #165).
 *
 * [DomainBlocklist.isBlocked] fails closed (returns true for every host) when the
 * blocklist could not be loaded, so a load failure halts HTTP traffic rather than
 * letting it through unchecked.
 */
@Singleton
class BlocklistInterceptor @Inject constructor(
    private val blocklist: DomainBlocklist,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        if (blocklist.isBlocked(host)) {
            throw IOException("Blocked host rejected by blocklist: $host")
        }
        return chain.proceed(chain.request())
    }
}
