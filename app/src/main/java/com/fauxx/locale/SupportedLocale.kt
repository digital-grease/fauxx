package com.fauxx.locale

import java.util.Locale

/**
 * Locales for which fauxx ships dedicated UI strings, query banks, harmful_queries
 * blocklists, persona templates, and crawl URL sets.
 *
 * All locale-aware components key off this enum, never raw [java.util.Locale]. Adding
 * a new value here is necessary but not sufficient to ship a locale — the locale must
 * also be added to `BuildConfig.SHIPPED_LOCALES` after its `harmful_queries/<tag>.json`
 * has signed-off native-speaker review (see `.devloop/spikes/multilingual-support.md`).
 */
enum class SupportedLocale(
    val tag: String,
    val displayName: String,
    /**
     * Default 2-letter region (uppercase) used when building search-engine URL parameters
     * that require a `<lang>-<region>` pair (Bing `setmkt`, DDG `kl`, Google `gl`).
     * fauxx picks one canonical region per language for plausibility and entropy budget;
     * `Accept-Language` headers still rotate across multiple regions independently.
     */
    val defaultRegion: String,
    /**
     * Yahoo localization is keyed off subdomain rather than a query parameter:
     *   en → "search.yahoo.com"
     *   es → "es.search.yahoo.com"
     *   fr → "fr.search.yahoo.com"
     * Empty string for the default (English) endpoint.
     */
    val yahooSubdomainPrefix: String
) {
    EN("en", "English", "US", ""),
    ES("es", "Español", "ES", "es."),
    FR("fr", "Français", "FR", "fr."),
    RU("ru", "Русский", "RU", "ru.");

    /** Build a [java.util.Locale] for use with system APIs (Configuration, formatters, etc.). */
    fun toLocale(): Locale = Locale.forLanguageTag(tag)

    companion object {
        /**
         * Resolve a [SupportedLocale] from any [Locale], falling back to [EN] for
         * unsupported language tags. Matches by language code only (ignoring region):
         * `fr-CA`, `fr-FR`, and `fr-BE` all resolve to [FR].
         */
        fun fromLocale(locale: Locale): SupportedLocale {
            val lang = locale.language.lowercase()
            return values().firstOrNull { it.tag == lang } ?: EN
        }

        /** Resolve from a BCP-47 tag (e.g. `"es-ES"`). Falls back to [EN]. */
        fun fromTag(tag: String?): SupportedLocale {
            if (tag.isNullOrBlank()) return EN
            return fromLocale(Locale.forLanguageTag(tag))
        }
    }
}
