package com.fauxx.targeting.layer2

import com.fauxx.data.querybank.CategoryPool
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure, injectable drift analysis over the [ProfileSnapshot] history (issue #170 E1).
 *
 * The roadmap's insight: a confirmed category that "won't budge" across successive snapshots
 * is a diagnostic that the current noise is not moving it, so Layer 2 should push harder there.
 * Snapshots are category sets, so per-category drift is presence-change; "sticky" means present
 * in both of a platform's two most recent snapshots.
 */
@Singleton
class ProfileDriftCalculator @Inject constructor() {

    /**
     * Categories that are present in BOTH of a platform's two most recent snapshots (unioned
     * across platforms): confirmed and not responding to the current noise. Returns empty when
     * no platform has at least two snapshots yet, so callers fall back to static behavior.
     *
     * [parse] converts a snapshot's stored JSON to a category set (reuses the caller's parser).
     */
    fun stickyConfirmed(
        snapshots: List<ProfileSnapshot>,
        parse: (String) -> Set<CategoryPool>,
    ): Set<CategoryPool> {
        val sticky = mutableSetOf<CategoryPool>()
        for ((_, snaps) in snapshots.groupBy { it.platformName }) {
            val latestTwo = snaps.sortedByDescending { it.capturedAt }.take(2)
            if (latestTwo.size < 2) continue
            val current = parse(latestTwo[0].scrapedCategoriesJson)
            val prior = parse(latestTwo[1].scrapedCategoriesJson)
            sticky.addAll(current intersect prior)
        }
        return sticky
    }
}
