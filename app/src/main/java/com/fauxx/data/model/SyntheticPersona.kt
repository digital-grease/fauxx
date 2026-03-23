package com.fauxx.data.model

import com.fauxx.data.querybank.CategoryPool

/**
 * A generated synthetic persona used by Layer 3 to add temporal coherence to noise patterns.
 * Personas are rotated every 7±3 days to prevent pattern detection.
 *
 * @property id Unique identifier for this persona.
 * @property name Generated human-like name.
 * @property ageRange Approximate age bracket (e.g., "35-44").
 * @property profession Synthetic occupation.
 * @property region Geographic region code (e.g., "US_MIDWEST").
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
