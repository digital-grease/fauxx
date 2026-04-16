package com.fauxx.ui.format

import com.fauxx.data.model.ActionType

/**
 * Short, human-readable display label for an [ActionType], used in filter chips
 * and other UI where the raw enum name is too long or underscore-heavy.
 *
 * The enum's `name` remains the canonical identifier for DB rows, exports, and
 * logs — this extension is purely a presentation concern.
 */
val ActionType.label: String
    get() = when (this) {
        ActionType.SEARCH_QUERY -> "SEARCH"
        ActionType.AD_CLICK -> "AD CLICK"
        ActionType.PAGE_VISIT -> "PAGE VISIT"
        ActionType.LOCATION_SPOOF -> "LOCATION"
        ActionType.DNS_LOOKUP -> "DNS"
        ActionType.COOKIE_HARVEST -> "COOKIES"
        ActionType.DEEP_LINK_VISIT -> "DEEP LINK"
        ActionType.FINGERPRINT_ROTATE -> "FINGERPRINT"
    }
