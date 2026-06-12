package com.fauxx.targeting.layer2

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** DAO for the append-only profile-snapshot history (issues #170 E1, #171 E2). */
@Dao
interface ProfileSnapshotDao {

    @Insert
    suspend fun insert(snapshot: ProfileSnapshot): Long

    /** All snapshots, oldest first (drives drift + KL computation reactively). */
    @Query("SELECT * FROM profile_snapshot ORDER BY capturedAt ASC")
    fun observeAll(): Flow<List<ProfileSnapshot>>

    /** The two most recent snapshots for a platform (current + prior), for week-over-week drift. */
    @Query("SELECT * FROM profile_snapshot WHERE platformName = :platform ORDER BY capturedAt DESC LIMIT 2")
    suspend fun latestTwoForPlatform(platform: String): List<ProfileSnapshot>

    /** The earliest snapshot for a platform (the immutable KL baseline). */
    @Query("SELECT * FROM profile_snapshot WHERE platformName = :platform ORDER BY capturedAt ASC LIMIT 1")
    suspend fun earliestForPlatform(platform: String): ProfileSnapshot?

    /**
     * Retention: delete snapshots older than [cutoff], but always keep the earliest AND latest
     * snapshot of each (platform, series). Per-(platform, series) so the control series (#172)
     * survives independently of the poisoned series: E2 drift needs >=2 rows (earliest + latest)
     * per platform-series, and E3 control-divergence needs a surviving latest row in each series.
     * Series-blind pruning would silently revert both dashboard cards to "collecting" (audit fix).
     * (MIN/MAX(id) per group is the earliest/latest, since id auto-increments in capture order.)
     */
    @Query(
        "DELETE FROM profile_snapshot WHERE capturedAt < :cutoff " +
            "AND id NOT IN (SELECT MIN(id) FROM profile_snapshot GROUP BY platformName, series) " +
            "AND id NOT IN (SELECT MAX(id) FROM profile_snapshot GROUP BY platformName, series)"
    )
    suspend fun deleteOlderThanKeepingBaseline(cutoff: Long)

    @Query("DELETE FROM profile_snapshot")
    suspend fun deleteAll()
}
