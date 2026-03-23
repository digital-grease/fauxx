package com.fauxx.targeting.layer3

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Room entity storing past synthetic personas, used to avoid repetition within a 90-day window.
 *
 * @property id Auto-generated primary key.
 * @property personaJson Full [com.fauxx.data.model.SyntheticPersona] serialized as JSON.
 * @property createdAt Epoch millis when this persona was created.
 */
@Entity(tableName = "persona_history")
data class PersonaHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val personaJson: String,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * DAO for persona history tracking.
 */
@Dao
interface PersonaHistoryDao {

    /** Store a newly generated persona in history. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: PersonaHistoryEntity)

    /** Get all personas created within the last [sinceMillis] epoch. */
    @Query("SELECT * FROM persona_history WHERE createdAt > :sinceMillis ORDER BY createdAt DESC")
    suspend fun getRecentPersonas(sinceMillis: Long): List<PersonaHistoryEntity>

    /** Delete all persona history (e.g., "Clear My Profile" action). */
    @Query("DELETE FROM persona_history")
    suspend fun deleteAll()

    /** Prune entries older than [beforeMillis] to keep table size manageable. */
    @Query("DELETE FROM persona_history WHERE createdAt < :beforeMillis")
    suspend fun pruneOlderThan(beforeMillis: Long)
}
