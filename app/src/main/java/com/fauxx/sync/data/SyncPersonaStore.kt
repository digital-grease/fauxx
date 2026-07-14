package com.fauxx.sync.data

import com.fauxx.data.model.SyntheticPersona
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists inbound, authenticated synced personas into the encrypted store, idempotently keyed on
 * the persona `id` (re-delivery converges to the same row; AC-4). Uses the same plain [Gson]
 * serialization the rest of the app uses for [SyntheticPersona], so the stored JSON is byte-identical
 * to what is persisted elsewhere.
 */
@Singleton
class SyncPersonaStore @Inject constructor(
    private val dao: SyncedPersonaDao
) {
    private val gson = Gson()

    /** Insert or replace the synced persona, keyed on its `id`. */
    suspend fun upsert(persona: SyntheticPersona, receivedAt: Long = System.currentTimeMillis()) {
        dao.upsert(SyncedPersonaEntity(persona.id, gson.toJson(persona), receivedAt))
    }

    /** Read back a synced persona by id, or null if absent / unparseable. */
    suspend fun get(id: String): SyntheticPersona? =
        dao.getById(id)?.let { runCatching { gson.fromJson(it.personaJson, SyntheticPersona::class.java) }.getOrNull() }

    /** Observe all synced personas, newest first. */
    fun observeAll(): Flow<List<SyntheticPersona>> =
        dao.observeAll().map { rows ->
            rows.mapNotNull { runCatching { gson.fromJson(it.personaJson, SyntheticPersona::class.java) }.getOrNull() }
        }
}
