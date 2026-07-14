package com.fauxx.targeting.layer3

import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool
import com.google.gson.Gson
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the auto-adopt contract for issue #234: a persona pushed from a paired device must become
 * the receiver's ACTIVE persona (so "pushed to 1 peer(s)" actually makes the phones match), not
 * just land in the synced-personas side table. Adoption is on all channels at once (a push is an
 * explicit convergence event, not the staggered weekly change-point) and must survive a process
 * restart even when the sender's persona is OLDER (by createdAt) than a local one.
 */
class PersonaRotationAdoptTest {

    private val gson = Gson()
    private val generator: PersonaGenerator = mockk(relaxed = true)
    private val oneDay = 24L * 60 * 60 * 1000

    /** In-memory [PersonaHistoryDao] that models autoincrement ids and both orderings. */
    private class FakeHistoryDao : PersonaHistoryDao {
        val rows = mutableListOf<PersonaHistoryEntity>()
        private var nextId = 1L
        override suspend fun insert(entry: PersonaHistoryEntity) {
            rows += if (entry.id == 0L) {
                entry.copy(id = nextId++)
            } else {
                nextId = maxOf(nextId, entry.id + 1)
                entry
            }
        }
        override suspend fun getRecentPersonas(sinceMillis: Long) =
            rows.filter { it.createdAt > sinceMillis }.sortedByDescending { it.createdAt }
        override suspend fun getRecentByInsertOrder(sinceMillis: Long) =
            rows.filter { it.createdAt > sinceMillis }.sortedByDescending { it.id }
        override suspend fun deleteAll() = rows.clear()
        override suspend fun pruneOlderThan(beforeMillis: Long) {
            rows.removeAll { it.createdAt < beforeMillis }
        }
    }

    private fun persona(name: String, createdAtOffsetMs: Long, activeUntilOffsetMs: Long): SyntheticPersona {
        val now = System.currentTimeMillis()
        return SyntheticPersona(
            id = name,
            name = name,
            ageRange = "35-44",
            profession = "Engineer",
            region = "US_MIDWEST",
            interests = setOf(CategoryPool.TECHNOLOGY),
            createdAt = now + createdAtOffsetMs,
            activeUntil = now + activeUntilOffsetMs
        )
    }

    private fun historyEntry(persona: SyntheticPersona) =
        PersonaHistoryEntity(personaJson = gson.toJson(persona), createdAt = persona.createdAt)

    @Test
    fun `adopting a synced persona makes it the active persona on all channels`() = runTest {
        val layer = PersonaRotationLayer(generator, FakeHistoryDao())
        val synced = persona("Synced Sam", createdAtOffsetMs = -oneDay, activeUntilOffsetMs = 5 * oneDay)

        layer.adoptSyncedPersonaInternal(synced)

        assertEquals("Synced Sam", layer.currentPersona.first()?.id)
        // No staggering for an explicit convergence event: bound channels serve it immediately.
        layer.setEnabled(true)
        assertEquals("Synced Sam", layer.personaForChannel(PersonaChannel.WEIGHTS)?.id)
    }

    @Test
    fun `adopted synced persona survives restart over a local persona with a newer createdAt`() = runTest {
        val dao = FakeHistoryDao()
        val layer = PersonaRotationLayer(generator, dao)

        // A local persona already in history, created MORE recently and still active. Under the old
        // createdAt-DESC restore this would win on restart and silently revert the adoption (#234).
        val local = persona("Local Lee", createdAtOffsetMs = -oneDay, activeUntilOffsetMs = 5 * oneDay)
        dao.insert(historyEntry(local))

        // Synced persona from a paired device: OLDER createdAt, adopted now (so stored later -> higher id).
        val synced = persona("Synced Sam", createdAtOffsetMs = -3 * oneDay, activeUntilOffsetMs = 4 * oneDay)
        layer.adoptSyncedPersonaInternal(synced)

        assertEquals("Synced Sam", layer.currentPersona.first()?.id)
        // The core regression: restore (insert order) returns the adopted persona, not the newer local one.
        assertEquals("Synced Sam", layer.restoreMostRecentActivePersona()?.id)
    }

    @Test
    fun `re-delivering the already-active persona is a no-op`() = runTest {
        val dao = FakeHistoryDao()
        val layer = PersonaRotationLayer(generator, dao)
        val p = persona("Sam", createdAtOffsetMs = 0, activeUntilOffsetMs = 5 * oneDay)

        layer.adoptSyncedPersonaInternal(p)
        layer.adoptSyncedPersonaInternal(p)

        assertEquals("Sam", layer.currentPersona.first()?.id)
        val stored = dao.rows.count { gson.fromJson(it.personaJson, SyntheticPersona::class.java).id == "Sam" }
        assertEquals("idempotent: the persona is stored once, not once per delivery", 1, stored)
    }

    @Test
    fun `an already-expired synced persona is not adopted`() = runTest {
        val dao = FakeHistoryDao()
        val layer = PersonaRotationLayer(generator, dao)
        val expired = persona("Expired Ed", createdAtOffsetMs = -10 * oneDay, activeUntilOffsetMs = -oneDay)

        layer.adoptSyncedPersonaInternal(expired)

        assertNull("a dead identity must not become active", layer.currentPersona.first())
        assertTrue("nothing persisted for an expired adopt", dao.rows.isEmpty())
    }
}
