package com.fauxx.targeting.layer2

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the platform ad-profile cache table.
 */
@Dao
interface PlatformProfileDao {

    /** Upsert a scraped platform profile. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: PlatformProfileCache)

    /** Get a specific platform's cached profile. */
    @Query("SELECT * FROM platform_profile_cache WHERE platformName = :platform")
    suspend fun getByPlatform(platform: String): PlatformProfileCache?

    /** Observe all cached profiles reactively. */
    @Query("SELECT * FROM platform_profile_cache")
    fun observeAll(): Flow<List<PlatformProfileCache>>

    /** Delete all cached profiles (e.g., "Clear My Profile" action). */
    @Query("DELETE FROM platform_profile_cache")
    suspend fun deleteAll()
}
