package com.fauxx.locale

import kotlin.random.Random

/**
 * Per-locale Accept-Language header pools, shared by the OkHttp
 * [com.fauxx.network.HeaderRandomizerInterceptor] and the WebView search path
 * ([com.fauxx.engine.modules.SearchPoisonModule]) so both emit a value coherent with
 * the active locale and with the locale-aware SERP hl/gl parameters. The primary
 * language always matches the locale; secondary languages mirror regional default
 * browser preference sets so a profile inspecting these headers sees plausible
 * ordering. A locale/language mismatch (e.g. en-US against hl=es&gl=ES) is itself a
 * fingerprintable inconsistency brokers can flag as bot activity.
 */
object AcceptLanguageVariants {

    private val VARIANTS: Map<SupportedLocale, List<String>> = mapOf(
        SupportedLocale.EN to listOf(
            "en-US,en;q=0.9",
            "en-US,en;q=0.8",
            "en-GB,en;q=0.9",
            "en-US,en;q=0.9,es;q=0.8",
            "en-US,en;q=0.9,fr;q=0.7",
        ),
        SupportedLocale.ES to listOf(
            "es-ES,es;q=0.9,en;q=0.6",
            "es-MX,es;q=0.9,en;q=0.6",
            "es-AR,es;q=0.9",
            "es-ES,es;q=0.9,en-US;q=0.7,en;q=0.5",
            "es-419,es;q=0.9,en;q=0.6",
        ),
        SupportedLocale.FR to listOf(
            "fr-FR,fr;q=0.9,en;q=0.6",
            "fr-CA,fr;q=0.9,en;q=0.6",
            "fr-FR,fr;q=0.9",
            "fr-FR,fr;q=0.9,en-US;q=0.7,en;q=0.5",
            "fr-BE,fr;q=0.9,en;q=0.6",
        ),
        SupportedLocale.RU to listOf(
            "ru-RU,ru;q=0.9,en;q=0.6",
            "ru-RU,ru;q=0.9",
            "ru,en;q=0.7",
            "ru-RU,ru;q=0.9,en-US;q=0.7,en;q=0.5",
            "ru-RU,ru;q=0.8,en;q=0.6",
        ),
    )

    /**
     * A locale-appropriate Accept-Language value. Falls back to the EN pool if a
     * locale somehow has no table entry, so the header is never empty.
     */
    fun forLocale(locale: SupportedLocale, random: Random): String {
        val variants = VARIANTS[locale] ?: VARIANTS.getValue(SupportedLocale.EN)
        return variants.random(random)
    }
}
