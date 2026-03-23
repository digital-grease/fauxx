package com.fauxx.data.querybank

/**
 * All content categories the Fauxx engine can target when generating synthetic activity.
 * These categories are used for weighted sampling by the TargetingEngine and ActionDispatcher.
 *
 * Each category maps to:
 * - A query bank JSON file in assets/query_banks/
 * - URL entries in crawl_urls.json with matching category tags
 */
enum class CategoryPool {
    MEDICAL,
    LEGAL,
    AUTOMOTIVE,
    PARENTING,
    RETIREMENT,
    GAMING,
    AGRICULTURE,
    FASHION,
    ACADEMIC,
    REAL_ESTATE,
    COOKING,
    SPORTS,
    FINANCE,
    TRAVEL,
    TECHNOLOGY,
    PETS,
    HOME_IMPROVEMENT,
    BEAUTY,
    MUSIC,
    FITNESS,
    ENTERTAINMENT,
    FOOD,
    POLITICS,
    SCIENCE,
    BUSINESS
}
