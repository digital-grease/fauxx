package com.fauxx.targeting.layer1

import android.content.Context
import com.fauxx.data.querybank.CategoryPool
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import javax.inject.Inject
import javax.inject.Singleton

/** Weight assigned to categories considered "close" to the user's actual demographics. */
private const val CLOSE_WEIGHT = 0.15f

/** Weight assigned to categories considered "distant" from the user's actual demographics. */
private const val DISTANT_WEIGHT = 2.5f

/** Weight for categories with no specific mapping in the rules. */
private const val NEUTRAL_WEIGHT = 1.0f

/**
 * Loads and evaluates demographic distance rules from [assets/demographic_distance_rules.json].
 * Maps demographic attribute combinations to [CategoryPool] weight assignments.
 *
 * Rules use a simple rule-based approach (no ML). Categories are classified as CLOSE (suppress
 * to 0.15), DISTANT (boost to 2.5), or NEUTRAL (1.0) based on the combination of demographic
 * attributes the user has reported.
 *
 * IMPORTANT: Rules must NEVER reference race, ethnicity, religion, sexual orientation,
 * disability status, or political affiliation. Only ageRange, gender, profession, and region
 * are permitted dimensions.
 */
@Singleton
class DemographicDistanceMap @Inject constructor(
    private val context: Context
) {
    private val rules: List<DistanceRule> by lazy { loadRules() }

    /**
     * Compute weight multipliers for all categories given a demographic profile.
     * Returns neutral weights (1.0) for all categories if [profile] is null.
     */
    fun getWeights(profile: UserDemographicProfile?): Map<CategoryPool, Float> {
        if (profile == null) return neutralWeights()

        val close = mutableSetOf<CategoryPool>()
        val distant = mutableSetOf<CategoryPool>()

        for (rule in rules) {
            if (!rule.matches(profile)) continue
            rule.close.forEach { close.add(it) }
            rule.distant.forEach { distant.add(it) }
        }

        return CategoryPool.values().associateWith { category ->
            when {
                close.contains(category) -> CLOSE_WEIGHT
                distant.contains(category) -> DISTANT_WEIGHT
                else -> NEUTRAL_WEIGHT
            }
        }
    }

    private fun neutralWeights(): Map<CategoryPool, Float> =
        CategoryPool.values().associateWith { NEUTRAL_WEIGHT }

    private fun loadRules(): List<DistanceRule> {
        return try {
            val json = context.assets.open("demographic_distance_rules.json")
                .bufferedReader()
                .readText()
            val type = object : TypeToken<List<RuleJson>>() {}.type
            val rawRules: List<RuleJson> = Gson().fromJson(json, type)
            rawRules.mapNotNull { it.toDistanceRule() }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/** Internal model matching the JSON structure of demographic_distance_rules.json. */
private data class RuleJson(
    val ageRange: String? = null,
    val gender: String? = null,
    val profession: String? = null,
    val region: String? = null,
    val close: List<String> = emptyList(),
    val distant: List<String> = emptyList()
) {
    fun toDistanceRule(): DistanceRule? {
        val closeCats = close.mapNotNull { runCatching { CategoryPool.valueOf(it) }.getOrNull() }.toSet()
        val distantCats = distant.mapNotNull { runCatching { CategoryPool.valueOf(it) }.getOrNull() }.toSet()
        return DistanceRule(
            ageRange = ageRange?.let { runCatching { AgeRange.valueOf(it) }.getOrNull() },
            gender = gender?.let { runCatching { Gender.valueOf(it) }.getOrNull() },
            profession = profession?.let { runCatching { Profession.valueOf(it) }.getOrNull() },
            region = region?.let { runCatching { Region.valueOf(it) }.getOrNull() },
            close = closeCats,
            distant = distantCats
        )
    }
}

/** Compiled distance rule. */
private data class DistanceRule(
    val ageRange: AgeRange?,
    val gender: Gender?,
    val profession: Profession?,
    val region: Region?,
    val close: Set<CategoryPool>,
    val distant: Set<CategoryPool>
) {
    /** Returns true if this rule applies to [profile] (all non-null dimensions must match). */
    fun matches(profile: UserDemographicProfile): Boolean {
        if (ageRange != null && profile.ageRange != ageRange) return false
        if (gender != null && profile.gender != gender) return false
        if (profession != null && profile.profession != profession) return false
        if (region != null && profile.region != region) return false
        return true
    }
}
