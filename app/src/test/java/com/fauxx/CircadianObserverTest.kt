package com.fauxx

import android.content.Context
import com.fauxx.engine.scheduling.CircadianObserver
import com.fauxx.engine.scheduling.CircadianUsageDao
import com.fauxx.support.FakeClock
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E10 (#177): the in-memory histogram logic of [CircadianObserver] — the bits that don't need
 * a live screen-on broadcast. The receiver registration and DB round-trip are exercised by the
 * instrumented tests; here we pin the counting, drift-rescale, snapshot-isolation, and wipe
 * behavior that the rate modulator depends on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CircadianObserverTest {

    private fun observer(
        dao: CircadianUsageDao = mockk(relaxed = true),
    ): CircadianObserver = CircadianObserver(
        context = mockk<Context>(relaxed = true),
        dao = dao,
        clock = FakeClock(0L),
    )

    @Test
    fun `recordEvent increments the bucket for the given hour`() {
        val o = observer()
        o.recordEvent(9)
        o.recordEvent(9)
        o.recordEvent(14)
        val counts = o.hourlyCounts()
        assertEquals(2L, counts[9])
        assertEquals(1L, counts[14])
        assertEquals(0L, counts[0])
        assertEquals(24, counts.size)
    }

    @Test
    fun `out-of-range hours are ignored`() {
        val o = observer()
        o.recordEvent(-1)
        o.recordEvent(24)
        o.recordEvent(99)
        assertEquals(0L, o.hourlyCounts().sum())
    }

    @Test
    fun `reaching the rescale cap halves every bucket - drift decay`() {
        val o = observer()
        o.recordEvent(3) // a stale observation in another hour
        repeat(CircadianObserver.RESCALE_CAP.toInt()) { o.recordEvent(10) }
        val counts = o.hourlyCounts()
        // On the cap-th increment hour 10 hit the cap and all buckets were halved.
        assertEquals(CircadianObserver.RESCALE_CAP / 2, counts[10])
        // The lone older observation decayed away under the same halving.
        assertEquals(0L, counts[3])
    }

    @Test
    fun `hourlyCounts returns an isolated copy`() {
        val o = observer()
        o.recordEvent(5)
        val snap = o.hourlyCounts()
        snap[5] = 999L // mutating the returned array must not affect internal state
        assertEquals(1L, o.hourlyCounts()[5])
    }

    @Test
    fun `clear zeroes the histogram and wipes the persisted rows`() = runTest {
        val dao: CircadianUsageDao = mockk(relaxed = true)
        val o = observer(dao)
        o.recordEvent(7)
        o.recordEvent(7)
        assertTrue(o.hourlyCounts().sum() > 0)

        o.clear()

        assertEquals(0L, o.hourlyCounts().sum())
        coVerify { dao.deleteAll() }
    }

    @Test
    fun `clear advances the generation so an in-flight persist is voided`() = runTest {
        // Regression guard for the wipe race: persistSnapshot() captures the generation with
        // its snapshot and skips the write if it has advanced, so a "Clear My Profile" cannot
        // be silently undone by a persist that was queued before the wipe.
        val o = observer()
        val before = o.generationForTest()
        o.clear()
        assertTrue("clear() must bump the generation", o.generationForTest() > before)
    }
}
