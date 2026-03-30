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
    BUSINESS,

    // --- Added categories for broader demographic distance coverage ---

    /** Hiking, camping, fishing, hunting, national parks, kayaking. Distinct from FITNESS (exercise) and SPORTS (spectator/competitive). */
    OUTDOOR_RECREATION,
    /** Knitting, pottery, scrapbooking, quilting, hobby woodworking, candle making. Distinct from HOME_IMPROVEMENT (structural). */
    CRAFTS,
    /** Historical events, museums, genealogy, antiques, historical documentaries. Distinct from ACADEMIC (formal education) and SCIENCE (research). */
    HISTORY,
    /** Climate change, sustainability, conservation, renewable energy, recycling, environmental policy. Distinct from SCIENCE (research-focused). */
    ENVIRONMENT,
    /** Veterans, defense technology, military history, VA benefits, service branches. */
    MILITARY_DEFENSE,
    /** Meditation lifestyle, crystals, astrology, holistic health, essential oils. Distinct from MEDICAL (evidence-based) and FITNESS (exercise). */
    WELLNESS_ALTERNATIVE,
    /** Dating apps, relationship advice, weddings, social skills, breakups. Strongly age-gated (18-34). */
    RELATIONSHIPS_DATING
}
