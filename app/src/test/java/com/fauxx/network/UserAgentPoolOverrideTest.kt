package com.fauxx.network

import android.content.Context
import com.fauxx.data.model.PoisonProfile
import com.fauxx.engine.PoisonProfileRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Locks the override path added for issue #7: when the user pastes a custom
 * User-Agent in Settings, [UserAgentPool.random] must return that string for
 * every call. Without this, the synthetic-traffic UA continues rotating across
 * the bundled pool — defeating the whole point of "match my browser" mode.
 */
class UserAgentPoolOverrideTest {

    private val realUaJson = """["UA1","UA2","UA3","UA4","UA5"]""".toByteArray()
    private val context: Context = mockk {
        every { assets } returns mockk {
            every { open("user_agents.json") } answers { ByteArrayInputStream(realUaJson) }
        }
    }

    @Test
    fun `random returns custom override when profile has one set`() {
        val customUa = "Mozilla/5.0 (My Real Browser)"
        val profileRepo: PoisonProfileRepository = mockk {
            every { getProfile() } returns PoisonProfile(customUserAgent = customUa)
        }
        val pool = UserAgentPool(context, profileRepo)

        // Override should win on every call (no random sampling from the pool).
        repeat(20) {
            assertEquals(customUa, pool.random())
        }
    }

    @Test
    fun `random falls back to pool when override is null`() {
        val profileRepo: PoisonProfileRepository = mockk {
            every { getProfile() } returns PoisonProfile(customUserAgent = null)
        }
        val pool = UserAgentPool(context, profileRepo)

        val seen = (1..50).map { pool.random() }.toSet()
        // Should pick from the test pool — at least one should NOT be the override
        // (if any override slipped through, this set would contain only that string).
        seen.forEach { ua ->
            assertNotEquals("Override leaked into pool result", "Mozilla/5.0 (My Real Browser)", ua)
        }
    }

    @Test
    fun `random falls back to pool when override is blank`() {
        // Blank string treated the same as null — the SettingsViewModel collapses
        // an empty TextField to null on persist, but defense-in-depth here.
        val profileRepo: PoisonProfileRepository = mockk {
            every { getProfile() } returns PoisonProfile(customUserAgent = "   ")
        }
        val pool = UserAgentPool(context, profileRepo)

        val seen = (1..50).map { pool.random() }.toSet()
        assertEquals("Pool should produce all 5 entries given enough samples", 5, seen.size)
    }
}
