package com.fauxx

import android.content.Context
import android.content.SharedPreferences
import com.fauxx.logging.BootGuard
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BootGuardTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private val store = mutableMapOf<String, Any>()

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        editor = mockk(relaxed = true)

        every {
            context.getSharedPreferences(BootGuard.PREFS_NAME, Context.MODE_PRIVATE)
        } returns prefs

        every { prefs.getInt(any(), any()) } answers {
            store[firstArg<String>()] as? Int ?: secondArg()
        }
        every { prefs.getBoolean(any(), any()) } answers {
            store[firstArg<String>()] as? Boolean ?: secondArg()
        }
        every { prefs.edit() } returns editor
        every { editor.putInt(any(), any()) } answers {
            store[firstArg<String>()] = secondArg<Int>()
            editor
        }
        every { editor.putBoolean(any(), any()) } answers {
            store[firstArg<String>()] = secondArg<Boolean>()
            editor
        }
        every { editor.commit() } returns true
    }

    @Test
    fun `recordBootStart increments counter from zero`() {
        val guard = BootGuard(context)

        guard.recordBootStart()

        assertEquals(1, store[BootGuard.KEY_BOOT_COUNTER])
    }

    @Test
    fun `recordBootStart increments existing counter`() {
        store[BootGuard.KEY_BOOT_COUNTER] = 1
        val guard = BootGuard(context)

        guard.recordBootStart()

        assertEquals(2, store[BootGuard.KEY_BOOT_COUNTER])
    }

    @Test
    fun `recordBootSuccess resets counter to zero`() {
        store[BootGuard.KEY_BOOT_COUNTER] = 5
        val guard = BootGuard(context)

        guard.recordBootSuccess()

        assertEquals(0, store[BootGuard.KEY_BOOT_COUNTER])
    }

    @Test
    fun `isInSafeMode false on fresh install`() {
        val guard = BootGuard(context)

        assertFalse(guard.isInSafeMode())
    }

    @Test
    fun `isInSafeMode false at one failed boot`() {
        val guard = BootGuard(context)
        guard.recordBootStart()

        assertFalse(guard.isInSafeMode())
    }

    @Test
    fun `isInSafeMode true at threshold`() {
        val guard = BootGuard(context)
        guard.recordBootStart()
        guard.recordBootStart()

        assertTrue(guard.isInSafeMode())
        assertEquals(BootGuard.SAFE_MODE_THRESHOLD, store[BootGuard.KEY_BOOT_COUNTER])
    }

    @Test
    fun `isInSafeMode true above threshold`() {
        store[BootGuard.KEY_BOOT_COUNTER] = 10
        val guard = BootGuard(context)

        assertTrue(guard.isInSafeMode())
    }

    @Test
    fun `successful boot after near-miss recovers normal state`() {
        val guard = BootGuard(context)
        // First boot trips counter to 1 but main thread is responsive long enough
        // for recordBootSuccess to fire.
        guard.recordBootStart()
        guard.recordBootSuccess()
        // Next boot starts from zero again.
        guard.recordBootStart()

        assertEquals(1, store[BootGuard.KEY_BOOT_COUNTER])
        assertFalse(guard.isInSafeMode())
    }

    @Test
    fun `recovery notice pending after markRecoveryTriggered`() {
        val guard = BootGuard(context)

        guard.markRecoveryTriggered()

        assertTrue(guard.consumePendingRecoveryNotice())
    }

    @Test
    fun `recovery notice cleared after consume`() {
        val guard = BootGuard(context)
        guard.markRecoveryTriggered()

        guard.consumePendingRecoveryNotice()

        assertFalse(guard.consumePendingRecoveryNotice())
    }

    @Test
    fun `consumePendingRecoveryNotice returns false when never triggered`() {
        val guard = BootGuard(context)

        assertFalse(guard.consumePendingRecoveryNotice())
    }

    @Test
    fun `writes use commit not apply`() {
        val guard = BootGuard(context)

        guard.recordBootStart()
        guard.recordBootSuccess()
        guard.markRecoveryTriggered()
        guard.consumePendingRecoveryNotice()

        verify(atLeast = 4) { editor.commit() }
        verify(exactly = 0) { editor.apply() }
    }
}
