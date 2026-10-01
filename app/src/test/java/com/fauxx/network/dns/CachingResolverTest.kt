package com.fauxx.network.dns

import com.fauxx.support.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

class CachingResolverTest {

    private val clock = FakeClock(0L)
    private var next: Resolution = Resolution.Addresses(listOf(InetAddress.getByName("192.0.2.1")))
    private var calls = 0
    private val cache = CachingResolver({ calls++; next }, clock, ttlMs = 60_000L, maxEntries = 2)

    @Test
    fun `an answer is reused within the TTL and refreshed after it`() {
        cache.resolve("a.example")
        cache.resolve("A.EXAMPLE")
        assertEquals("case-insensitive hit inside the TTL", 1, calls)
        clock.nowMs += 60_001L
        cache.resolve("a.example")
        assertEquals(2, calls)
    }

    @Test
    fun `no such host is an answer and is cached too`() {
        next = Resolution.NoSuchHost
        cache.resolve("blocked.example")
        cache.resolve("blocked.example")
        assertEquals(1, calls)
    }

    @Test
    fun `failures are never cached, so a recovering resolver is retried at once`() {
        next = Resolution.Failure(IOException("down"))
        cache.resolve("a.example")
        cache.resolve("a.example")
        assertEquals(2, calls)
    }

    @Test
    fun `the cache is bounded`() {
        cache.resolve("a.example")
        cache.resolve("b.example")
        cache.resolve("c.example") // evicts a
        cache.resolve("a.example")
        assertEquals(4, calls)
    }
}
