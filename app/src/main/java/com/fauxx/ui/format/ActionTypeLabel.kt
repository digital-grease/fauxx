package com.fauxx.ui.format

import androidx.annotation.StringRes
import com.fauxx.R
import com.fauxx.data.model.ActionType

/**
 * Short, human-readable display label for an [ActionType], used in filter chips
 * and other UI where the raw enum name is too long or underscore-heavy.
 *
 * The enum's `name` remains the canonical identifier for DB rows, exports, and
 * logs — this extension is purely a presentation concern.
 */
@StringRes
fun ActionType.displayNameRes(): Int = when (this) {
    ActionType.SEARCH_QUERY -> R.string.action_type_search_query
    ActionType.PAGE_VISIT -> R.string.action_type_page_visit
    ActionType.LOCATION_SPOOF -> R.string.action_type_location_spoof
    ActionType.DNS_LOOKUP -> R.string.action_type_dns_lookup
    ActionType.COOKIE_HARVEST -> R.string.action_type_cookie_harvest
    ActionType.DEEP_LINK_VISIT -> R.string.action_type_deep_link_visit
    ActionType.FINGERPRINT_ROTATE -> R.string.action_type_fingerprint_rotate
    ActionType.AD_PROFILE_IMPORT -> R.string.action_type_ad_profile_import
}
