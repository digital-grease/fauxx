package com.fauxx.data.model

import com.fauxx.data.querybank.CategoryPool

/**
 * A generated synthetic persona used by Layer 3 to add temporal coherence to noise patterns.
 * Personas are rotated every 7±3 days to prevent pattern detection.
 *
 * @property id Unique identifier for this persona.
 * @property name Generated human-like name.
 * @property ageRange Age bracket as a layer-1 AgeRange enum name (e.g., "AGE_35_44").
 * @property profession Synthetic occupation as a Profession enum name (e.g., "FINANCE_PROF").
 * @property region Geographic region as a Region enum name (e.g., "US_MIDWEST").
 *   Demographics are stored as enum names so they are locale-independent on disk;
 *   display labels resolve in the UI via DemographicLabels. Personas persisted before
 *   this canonicalization may carry legacy display strings ("35-44") — readers must
 *   tolerate unparseable values.
 * @property interests Set of 3-5 interest categories this persona focuses on.
 * @property createdAt Epoch millis when this persona was generated.
 * @property activeUntil Epoch millis when this persona should be rotated.
 */
data class SyntheticPersona(
    val id: String,
    val name: String,
    val ageRange: String,
    val profession: String,
    val region: String,
    val interests: Set<CategoryPool>,
    val createdAt: Long = System.currentTimeMillis(),
    val activeUntil: Long
) {
    companion object {
        /** Fraction of actions that follow persona interest weights vs uniform noise. */
        const val PERSONA_FOLLOW_FRACTION = 0.70f
    }
}
