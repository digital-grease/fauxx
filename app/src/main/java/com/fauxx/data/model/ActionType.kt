package com.fauxx.data.model

/**
 * Enum representing the types of synthetic actions the Fauxx engine can execute.
 * Each action type corresponds to a different privacy-poisoning technique.
 */
enum class ActionType {
    /** Executes search queries on search engines. */
    SEARCH_QUERY,

    /** Loads ad-heavy pages and simulates ad interactions. */
    AD_CLICK,

    /** Visits URLs from the crawl corpus to accumulate tracker cookies. */
    PAGE_VISIT,

    /** Feeds synthetic GPS coordinates via MockLocationProvider. */
    LOCATION_SPOOF,

    /** Resolves diverse domain names to generate DNS query noise. */
    DNS_LOOKUP,

    /** Accumulates diverse tracker cookies across many domains. */
    COOKIE_HARVEST,

    /** Opens deep links and app store pages for off-profile apps. */
    DEEP_LINK_VISIT,

    /** Rotates User-Agent, canvas fingerprint, and related signals. */
    FINGERPRINT_ROTATE
}
