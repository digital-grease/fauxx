package com.fauxx.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for accessing the action audit log.
 */
@Dao
interface ActionLogDao {

    /** Insert a new action log entry (write-ahead, before execution). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: ActionLogEntity): Long

    /** Stream all log entries ordered by most recent first. */
    @Query("SELECT * FROM action_log ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<ActionLogEntity>>

    /** Count of actions in the past 24 hours. */
    @Query("SELECT COUNT(*) FROM action_log WHERE timestamp > :sinceMillis AND success = 1")
    fun countSince(sinceMillis: Long): Flow<Int>

    /** Delete all entries older than [beforeMillis]. */
    @Query("DELETE FROM action_log WHERE timestamp < :beforeMillis")
    suspend fun deleteOlderThan(beforeMillis: Long)

    /** All entries for export, ordered by timestamp ascending. */
    @Query("SELECT * FROM action_log ORDER BY timestamp ASC")
    suspend fun getAllForExport(): List<ActionLogEntity>

    /** Count per category for chart display. */
    @Query("""
        SELECT category, COUNT(*) as count
        FROM action_log
        WHERE timestamp > :sinceMillis
        GROUP BY category
    """)
    suspend fun countPerCategorySince(sinceMillis: Long): List<CategoryCount>
}

/** Projection for category count queries. */
data class CategoryCount(
    val category: com.fauxx.data.querybank.CategoryPool,
    val count: Int
)
