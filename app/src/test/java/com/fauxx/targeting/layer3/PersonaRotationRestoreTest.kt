package com.fauxx.targeting.layer3

import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks the persona-restore contract for issue #63: when the FGS resume cycle
 * kills our process, `_currentPersona` was lost and setEnabled(true) generated
 * a fresh persona on every restart — making the user-visible persona rotate
 * roughly daily instead of weekly.
 *
 * [PersonaRotationLayer.restoreMostRecentActivePersona] resolves this by walking
 * the history DAO for the most-recent persona whose `activeUntil` window has not
 * yet expired.
 */
class PersonaRotationRestoreTest {

    private val gson = Gson()
    private val generator: PersonaGenerator = mockk(relaxed = true)

    private fun persona(name: String, activeUntilOffsetMs: Long): SyntheticPersona {
        val now = System.currentTimeMillis()
        return SyntheticPersona(
            id = name,
            name = name,
            ageRange = "35-44",
            profession = "Engineer",
            region = "US_MIDWEST",
            interests = setOf(CategoryPool.TECHNOLOGY),
            createdAt = now,
            activeUntil = now + activeUntilOffsetMs
        )
    }

    private fun layerWithHistory(entries: List<PersonaHistoryEntity>): PersonaRotationLayer {
        val dao: PersonaHistoryDao = mockk(relaxed = true) {
            coEvery { getRecentPersonas(any()) } returns entries
        }
        return PersonaRotationLayer(generator, dao)
    }

    private fun entry(persona: SyntheticPersona, id: Long = persona.createdAt): PersonaHistoryEntity =
        PersonaHistoryEntity(
            id = id,
            personaJson = gson.toJson(persona),
            createdAt = persona.createdAt
        )

    @Test
    fun `restore returns most-recent still-active persona`() = runTest {
        val oneDayMs = 24L * 60 * 60 * 1000
        val active = persona("Active Alex", activeUntilOffsetMs = 3 * oneDayMs)
        val layer = layerWithHistory(listOf(entry(active)))

        val restored = layer.restoreMostRecentActivePersona()
        assertEquals(active.name, restored?.name)
    }

    @Test
    fun `restore returns null when history is empty`() = runTest {
        val layer = layerWithHistory(emptyList())
        assertNull(layer.restoreMostRecentActivePersona())
    }

    @Test
    fun `restore returns null when every entry has expired activeUntil`() = runTest {
        // Past-active personas: activeUntil already elapsed. None should restore;
        // the layer must fall through to generating a new persona.
        val past = persona("Past Pat", activeUntilOffsetMs = -1)
        val layer = layerWithHistory(listOf(entry(past)))
        assertNull(layer.restoreMostRecentActivePersona())
    }

    @Test
    fun `restore picks the most-recent eligible persona when multiple are active`() = runTest {
        // DAO sorts DESC by createdAt — first eligible entry wins. This guards against
        // a future refactor that reorders or filters before the iteration.
        val oneDayMs = 24L * 60 * 60 * 1000
        val olderActive = persona("Older Olivia", activeUntilOffsetMs = 1 * oneDayMs)
            .copy(createdAt = System.currentTimeMillis() - 5 * oneDayMs)
        val newerActive = persona("Newer Nina", activeUntilOffsetMs = 6 * oneDayMs)

        // Insert in DESC order so the DAO emits newerActive first.
        val layer = layerWithHistory(listOf(entry(newerActive), entry(olderActive)))

        assertEquals("Newer Nina", layer.restoreMostRecentActivePersona()?.name)
    }

    @Test
    fun `restore skips entries whose JSON cannot deserialize`() = runTest {
        val active = persona("Active Alex", activeUntilOffsetMs = 7L * 24 * 60 * 60 * 1000)
        val garbage = PersonaHistoryEntity(id = 99, personaJson = "{ not valid json", createdAt = active.createdAt + 1)

        // DAO orders DESC by createdAt; garbage is newer so it appears first. The
        // restore must skip it and fall through to the valid entry.
        val layer = layerWithHistory(listOf(garbage, entry(active)))

        assertEquals(active.name, layer.restoreMostRecentActivePersona()?.name)
    }

    @Test
    fun `restore returns null when DAO query throws`() = runTest {
        val dao: PersonaHistoryDao = mockk {
            coEvery { getRecentPersonas(any()) } throws RuntimeException("DB unavailable")
        }
        val layer = PersonaRotationLayer(generator, dao)
        assertNull(layer.restoreMostRecentActivePersona())
    }
}
