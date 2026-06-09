package com.fauxx.targeting.layer2

import com.fauxx.data.querybank.CategoryPool
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln

/** Whether enough snapshots exist to show a drift number yet (issue #171 E2). */
enum class DriftState { COLLECTING, AVAILABLE }

/** Result of the profile-drift computation; [klDivergence] is null while [state] is COLLECTING. */
data class DriftResult(val state: DriftState, val klDivergence: Double?)

/**
 * Computes profile drift for the dashboard (issue #171 E2): the KL divergence of the user's
 * latest imported ad-interest distribution from their baseline (earliest) import, over the
 * CategoryPool support. Needs at least two snapshots for some platform; until then it reports
 * COLLECTING.
 *
 * Honest caveat (surfaced in the dashboard help text): drift can reflect the platform retraining
 * its own model, not only Fauxx's effect. It is an indicator of movement, not proof of efficacy.
 */
@Singleton
class ProfileDriftMetric @Inject constructor() {

    private val gson = Gson()

    fun compute(snapshots: List<ProfileSnapshot>): DriftResult {
        val baselineSets = mutableListOf<Set<CategoryPool>>()
        val currentSets = mutableListOf<Set<CategoryPool>>()
        for ((_, snaps) in snapshots.groupBy { it.platformName }) {
            val sorted = snaps.sortedBy { it.capturedAt }
            if (sorted.size < 2) continue
            baselineSets.add(parse(sorted.first().scrapedCategoriesJson))
            currentSets.add(parse(sorted.last().scrapedCategoriesJson))
        }
        if (baselineSets.isEmpty()) return DriftResult(DriftState.COLLECTING, null)
        val baseline = baselineSets.flatten().toSet()
        val current = currentSets.flatten().toSet()
        return DriftResult(DriftState.AVAILABLE, kl(current, baseline))
    }

    /** KL(current || baseline) over the CategoryPool support, Laplace-smoothed so it stays finite. */
    fun kl(current: Set<CategoryPool>, baseline: Set<CategoryPool>): Double {
        val cats = CategoryPool.values()
        val p = dist(current, cats)
        val q = dist(baseline, cats)
        var sum = 0.0
        for (c in cats) {
            val pi = p.getValue(c)
            val qi = q.getValue(c)
            if (pi > 0.0) sum += pi * ln(pi / qi)
        }
        return sum
    }

    private fun dist(set: Set<CategoryPool>, cats: Array<CategoryPool>): Map<CategoryPool, Double> {
        val alpha = 0.5
        val denom = set.size + alpha * cats.size
        return cats.associateWith { c -> ((if (c in set) 1.0 else 0.0) + alpha) / denom }
    }

    private fun parse(json: String): Set<CategoryPool> = try {
        val type = object : TypeToken<List<String>>() {}.type
        val names: List<String> = gson.fromJson(json, type)
        names.mapNotNull { runCatching { CategoryPool.valueOf(it) }.getOrNull() }.toSet()
    } catch (e: Exception) {
        emptySet()
    }
}
