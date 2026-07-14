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

    /**
     * Personas within retention ordered by INSERT ORDER (autoincrement `id` DESC), so the most
     * recently STORED persona is first. Used to restore the last-active persona (#234): a persona
     * adopted from a paired device carries the sender's `createdAt`, which may be older than a local
     * persona, so `createdAt` ordering would revert the adoption on restart. Insert order does not.
     * For local-only history, insert order matches `createdAt` order, so restore is unchanged.
     */
    @Query("SELECT * FROM persona_history WHERE createdAt > :sinceMillis ORDER BY id DESC")
    suspend fun getRecentByInsertOrder(sinceMillis: Long): List<PersonaHistoryEntity>

    /** Delete all persona history (e.g., "Clear My Profile" action). */
    @Query("DELETE FROM persona_history")
    suspend fun deleteAll()

    /** Prune entries older than [beforeMillis] to keep table size manageable. */
    @Query("DELETE FROM persona_history WHERE createdAt < :beforeMillis")
    suspend fun pruneOlderThan(beforeMillis: Long)
}
