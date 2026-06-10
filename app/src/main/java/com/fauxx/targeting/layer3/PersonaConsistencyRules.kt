package com.fauxx.targeting.layer3

import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool

/**
 * Validates that a [SyntheticPersona] has internally consistent trait combinations.
 * Prevents generation of demographically implausible personas that could be detected
 * as synthetic (e.g., a "retiree" interested in college courses).
 */
object PersonaConsistencyRules {

    /**
     * Returns true if [persona] passes all consistency checks.
     */
    fun isValid(persona: SyntheticPersona): Boolean {
        return !hasIncompatibleTraits(persona) && hasRequiredFields(persona)
    }

    private fun hasRequiredFields(persona: SyntheticPersona): Boolean {
        return persona.name.isNotBlank() &&
            persona.ageRange.isNotBlank() &&
            persona.profession.isNotBlank() &&
            persona.region.isNotBlank() &&
            persona.interests.isNotEmpty()
    }

    /**
     * Check for known incompatible trait pairs. Returns true if incompatible.
     *
     * Age comparisons use [com.fauxx.targeting.layer1.AgeRange] enum names — the
     * canonical [SyntheticPersona.ageRange] format. Before that canonicalization these
     * compared display strings ("65+"), which never matched template-sourced personas,
     * so the rules were silently inert on the dominant generation path.
     */
    private fun hasIncompatibleTraits(persona: SyntheticPersona): Boolean {
        val age = persona.ageRange
        val interests = persona.interests

        // Retiree-age personas shouldn't have strong academic/student interests
        if (age == "AGE_65_PLUS" && interests.contains(CategoryPool.ACADEMIC) &&
            interests.size < 3
        ) return true

        // Very young persona (18-24) shouldn't dominate retirement interests
        if (age == "AGE_18_24" && interests.contains(CategoryPool.RETIREMENT) &&
            !interests.any { it in setOf(CategoryPool.FINANCE, CategoryPool.REAL_ESTATE) }
        ) return true

        // Parenting interests should co-occur with age groups where parenting is plausible
        if (interests.contains(CategoryPool.PARENTING) && age == "AGE_18_24" &&
            interests.size == 1
        ) return true

        return false
    }

    /** Fraction above which two personas are considered too similar. */
    const val OVERLAP_THRESHOLD = 0.60f

    /**
     * Compute trait overlap between two personas as a fraction of shared interests
     * out of the union of both interest sets.
     */
    fun overlapFraction(a: SyntheticPersona, b: SyntheticPersona): Float {
        if (a.interests.isEmpty() || b.interests.isEmpty()) return 0f
        val intersection = a.interests.intersect(b.interests).size.toFloat()
        val union = a.interests.union(b.interests).size.toFloat()
        return intersection / union
    }
}
