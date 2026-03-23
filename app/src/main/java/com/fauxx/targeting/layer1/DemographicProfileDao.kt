package com.fauxx.targeting.layer1

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the single-row user demographic profile table.
 */
@Dao
interface DemographicProfileDao {

    /** Insert or replace the profile (single-row table, id=1). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: UserDemographicProfile)

    /** Observe the profile reactively. Emits null if not set. */
    @Query("SELECT * FROM user_demographic_profile WHERE id = 1")
    fun observe(): Flow<UserDemographicProfile?>

    /** Get the profile once. Returns null if not set. */
    @Query("SELECT * FROM user_demographic_profile WHERE id = 1")
    suspend fun get(): UserDemographicProfile?

    /** Delete the profile (e.g., "Clear My Profile" action). */
    @Query("DELETE FROM user_demographic_profile")
    suspend fun delete()
}
