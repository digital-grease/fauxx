package com.fauxx.support

import com.fauxx.util.Clock

/**
 * [Clock] backed by a mutable field so tests can advance it in sync with the runTest
 * scheduler. Both wall-clock and elapsed-realtime are slaved to the same value, which is
 * sufficient for tests that assert relative time deltas. Shared by unit and instrumented
 * tests via the `src/sharedTest` source set.
 */
class FakeClock(var nowMs: Long = 0L) : Clock {
    override fun currentTimeMillis(): Long = nowMs
    override fun elapsedRealtime(): Long = nowMs
}
