package com.fauxx.sync.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Thin repository over [PairedPeerDao] that maps between the Room entity and the domain model. */
@Singleton
class PairedPeerRepository @Inject constructor(
    private val dao: PairedPeerDao
) {
    suspend fun upsert(peer: PairedPeer) = dao.upsert(peer.toEntity())

    suspend fun getByPublicKey(publicKey: String): PairedPeer? = dao.getByPublicKey(publicKey)?.toDomain()

    suspend fun getAll(): List<PairedPeer> = dao.getAll().map { it.toDomain() }

    fun observeAll(): Flow<List<PairedPeer>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    /** Revoke a peer; returns true if a record was removed (mirror the desktop `unpair`). */
    suspend fun unpair(publicKey: String): Boolean = dao.delete(publicKey) > 0
}
