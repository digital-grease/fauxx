package com.fauxx.engine.scheduling

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One row per hour-of-day (0-23) holding the locally-observed screen-on count for that
 * hour (E10 #177). This is the ONLY persisted form of the circadian signal: a 24-bucket
 * aggregate histogram, never raw event timestamps, so the stored footprint reveals a coarse
 * daily rhythm and nothing about *when* any individual unlock happened. Lives in the
 * SQLCipher-encrypted [com.fauxx.data.db.PhantomDatabase] and is never transmitted off-device.
 *
 * @property hourOfDay Local hour of day, 0-23 (primary key — at most 24 rows ever exist).
 * @property count Decayed observation count for that hour (see [CircadianObserver] rescaling).
 */
@Entity(tableName = "circadian_usage")
data class CircadianUsageEntity(
    @PrimaryKey val hourOfDay: Int,
    val count: Long,
)

/**
 * DAO for the circadian usage histogram. The in-memory snapshot in [CircadianObserver] is the
 * read path used by the scheduler; this DAO is the persistence path so a learned rhythm
 * survives process restarts.
 */
@Dao
interface CircadianUsageDao {

    /** Load the full histogram (0-24 rows). Absent hours mean a zero count. */
    @Query("SELECT * FROM circadian_usage")
    suspend fun getAll(): List<CircadianUsageEntity>

    /**
     * Persist the full 24-bucket snapshot in one transaction. REPLACE on the hour-of-day
     * primary key, so this upserts every bucket to the current in-memory value — which also
     * applies the periodic rescale uniformly without a separate decay query.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<CircadianUsageEntity>)

    /** Wipe the learned rhythm (part of "Clear My Profile" / reset-to-defaults). */
    @Query("DELETE FROM circadian_usage")
    suspend fun deleteAll()
}
