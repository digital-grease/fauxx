package com.fauxx.network

import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
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
 *
 * Accept-Language is locale-aware: the active [LocaleManager] locale selects which
 * primary-language strings are eligible for emission. A Spanish-mode install never
 * sends `en-US` as primary, because the mismatch with locale-aware search-engine URL
 * params (`hl=es&gl=ES`) and translated query content would itself be a fingerprintable
 * inconsistency that data brokers can use to flag the device as bot activity.
 */
@Singleton
class HeaderRandomizerInterceptor @Inject constructor(
    private val uaPool: UserAgentPool,
    private val localeManager: LocaleManager
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

    private fun randomAcceptLanguage(): String {
        val locale = localeManager.currentLocale
        // Fall back to EN if a locale somehow has no variants table (shouldn't happen
        // since the enum and the table are co-defined, but a missing entry should not
        // emit an empty header).
        val variants = LANGUAGE_VARIANTS[locale] ?: LANGUAGE_VARIANTS.getValue(SupportedLocale.EN)
        return variants.random()
    }

    companion object {
        private val ACCEPT_VARIANTS = listOf(
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8"
        )

        /**
         * Per-locale Accept-Language pools. Primary language always matches the active
         * locale; secondary languages mirror the regional default browser preference
         * sets so a profile inspecting these headers sees plausible language ordering.
         */
        private val LANGUAGE_VARIANTS: Map<SupportedLocale, List<String>> = mapOf(
            SupportedLocale.EN to listOf(
                "en-US,en;q=0.9",
                "en-US,en;q=0.8",
                "en-GB,en;q=0.9",
                "en-US,en;q=0.9,es;q=0.8",
                "en-US,en;q=0.9,fr;q=0.7"
            ),
            SupportedLocale.ES to listOf(
                "es-ES,es;q=0.9,en;q=0.6",
                "es-MX,es;q=0.9,en;q=0.6",
                "es-AR,es;q=0.9",
                "es-ES,es;q=0.9,en-US;q=0.7,en;q=0.5",
                "es-419,es;q=0.9,en;q=0.6"
            ),
            SupportedLocale.FR to listOf(
                "fr-FR,fr;q=0.9,en;q=0.6",
                "fr-CA,fr;q=0.9,en;q=0.6",
                "fr-FR,fr;q=0.9",
                "fr-FR,fr;q=0.9,en-US;q=0.7,en;q=0.5",
                "fr-BE,fr;q=0.9,en;q=0.6"
            )
        )
    }
}
