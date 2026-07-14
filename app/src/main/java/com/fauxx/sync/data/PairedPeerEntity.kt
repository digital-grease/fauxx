package com.fauxx.sync.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A device this one has paired with (E13 #178). Persisted in the encrypted SQLCipher store. The
 * base64url [publicKey] is the trust anchor: frames are sealed to and authenticated from this key.
 * Mirrors the desktop `PairedPeer` (`peer.rs`).
 */
@Entity(tableName = "paired_peers")
data class PairedPeerEntity(
    @PrimaryKey val publicKey: String,
    val name: String,
    val fingerprint: String,
    val host: String?,
    val port: Int,
    val pairedAt: Long
)

/** DAO for the paired-peer trust set. */
@Dao
interface PairedPeerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(peer: PairedPeerEntity)

    @Query("SELECT * FROM paired_peers WHERE publicKey = :publicKey")
    suspend fun getByPublicKey(publicKey: String): PairedPeerEntity?

    @Query("SELECT * FROM paired_peers ORDER BY pairedAt DESC")
    suspend fun getAll(): List<PairedPeerEntity>

    @Query("SELECT * FROM paired_peers ORDER BY pairedAt DESC")
    fun observeAll(): Flow<List<PairedPeerEntity>>

    /** Revoke a peer (mirror the desktop `unpair`). Returns the number of rows removed. */
    @Query("DELETE FROM paired_peers WHERE publicKey = :publicKey")
    suspend fun delete(publicKey: String): Int
}
