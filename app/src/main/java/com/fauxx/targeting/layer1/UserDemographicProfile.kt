package com.fauxx.targeting.layer1

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.fauxx.data.querybank.CategoryPool

/** Age bracket options for self-report onboarding. */
enum class AgeRange { AGE_18_24, AGE_25_34, AGE_35_44, AGE_45_54, AGE_55_64, AGE_65_PLUS }

/** Coarse gender options — only binary options offered to limit sensitivity. */
enum class Gender { MALE, FEMALE, PREFER_NOT_TO_SAY }

/** Profession categories for demographic distance computation. */
enum class Profession {
    STUDENT, TEACHER, ENGINEER, HEALTHCARE, LEGAL, FINANCE_PROF,
    RETAIL, TRADES, CREATIVE, RETIRED, HOMEMAKER, OTHER
}

/** Broad geographic regions used for location-spoof targeting. */
enum class Region {
    US_NORTHEAST, US_SOUTHEAST, US_MIDWEST, US_SOUTHWEST, US_WEST,
    CANADA, UK, WESTERN_EUROPE, EASTERN_EUROPE, ASIA_PACIFIC,
    LATIN_AMERICA, MIDDLE_EAST_AFRICA, OTHER
}

/**
 * Room entity storing the user's voluntarily self-reported demographic profile.
 * All fields are nullable — the user may skip any or all questions.
 *
 * PRIVACY: This data NEVER leaves the device. It is stored encrypted via SQLCipher
 * with an AndroidKeyStore-backed key. It must not appear in any HTTP request, URL,
 * log, or analytics event.
 */
@Entity(tableName = "user_demographic_profile")
data class UserDemographicProfile(
    @PrimaryKey val id: Int = 1, // single-row table
    val ageRange: AgeRange? = null,
    val gender: Gender? = null,
    val profession: Profession? = null,
    val region: Region? = null,
    /** Serialized as comma-separated CategoryPool names. */
    val interestsJson: String? = null
) {
    /** Deserialize interests from stored JSON string. */
    fun getInterests(): Set<CategoryPool> {
        val raw = interestsJson ?: return emptySet()
        return raw.split(",")
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { CategoryPool.valueOf(it.trim()) }.getOrNull() }
            .toSet()
    }

    companion object {
        fun serializeInterests(interests: Set<CategoryPool>): String =
            interests.joinToString(",") { it.name }
    }
}
