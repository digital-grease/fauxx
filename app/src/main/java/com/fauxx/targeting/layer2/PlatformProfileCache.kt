package com.fauxx.targeting.layer2

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity caching the categories an ad platform has assigned to the user.
 * One row per platform. Stored in the SQLCipher-encrypted database.
 *
 * @property platformName Identifier for the platform (e.g., "google", "facebook").
 * @property scrapedCategoriesJson JSON array of CategoryPool names scraped from the platform.
 * @property lastScraped Epoch millis when this data was last scraped.
 */
@Entity(tableName = "platform_profile_cache")
data class PlatformProfileCache(
    @PrimaryKey val platformName: String,
    val scrapedCategoriesJson: String = "[]",
    val lastScraped: Long = 0L
)
