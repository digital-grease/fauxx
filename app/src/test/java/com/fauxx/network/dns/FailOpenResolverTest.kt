package com.fauxx.network.dns

import com.fauxx.support.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class FailOpenResolverTest {

    private val clock = FakeClock(1_000_000L)
    private val customAddr = Resolution.Addresses(listOf(InetAddress.getByName("10.0.0.1")))
    private val systemAddr = Resolution.Addresses(listOf(InetAddress.getByName("10.0.0.2")))

    private var customResult: Resolution = customAddr
    private var customDelayMs = 0L
    private var customCalls = 0
    private var systemCalls = 0

    private val custom = HostResolver {
        customCalls++
        clock.nowMs += customDelayMs
        customResult
    }
    private val system = HostResolver { systemCalls++; systemAddr }

    private val resolver = FailOpenResolver(custom, system, clock, failureThreshold = 3, cooldownMs = 60_000L, slowMs = 3_000L)

    private val failure = Resolution.Failure(IOException("unreachable"))

    @Test
    fun `a healthy custom resolver answers and the system resolver is never asked`() {
        assertEquals(customAddr, resolver.resolve("a.example"))
        assertEquals(0, systemCalls)
        assertEquals(DnsHealth.Healthy, resolver.health.value)
    }

    @Test
    fun `NXDOMAIN from the custom resolver is final, never retried on the system resolver`() {
        customResult = Resolution.NoSuchHost
        assertEquals(Resolution.NoSuchHost, resolver.resolve("blocked.example"))
        assertEquals(0, systemCalls)
    }

    @Test
    fun `a failure falls back to the system resolver for that lookup`() {
        customResult = failure
        assertEquals(systemAddr, resolver.resolve("a.example"))
        assertEquals(1, systemCalls)
        // One failure is not yet degraded: no flapping notice for a single blip.
        assertEquals(DnsHealth.Healthy, resolver.health.value)
    }

    @Test
    fun `repeated failures open the breaker, report degraded, and skip the custom resolver`() {
        customResult = failure
        repeat(3) { resolver.resolve("a.example") }
        assertTrue(resolver.health.value is DnsHealth.Degraded)

        val callsBefore = customCalls
        assertEquals(systemAddr, resolver.resolve("b.example"))
        assertEquals("open breaker must not wait on the custom resolver", callsBefore, customCalls)
    }

    @Test
    fun `after the cooldown one good answer closes the breaker and clears the notice`() {
        customResult = failure
        repeat(3) { resolver.resolve("a.example") }

        clock.nowMs += 60_001L
        customResult = customAddr
        assertEquals(customAddr, resolver.resolve("a.example"))
        assertEquals(DnsHealth.Healthy, resolver.health.value)
    }

    @Test
    fun `slow answers are used but count toward the breaker`() {
        customDelayMs = 3_500L
        repeat(3) { assertEquals(customAddr, resolver.resolve("a.example")) }
        assertTrue(resolver.health.value is DnsHealth.Degraded)

        val callsBefore = customCalls
        assertEquals(systemAddr, resolver.resolve("b.example"))
        assertEquals(callsBefore, customCalls)
    }

    @Test
    fun `a success between failures resets the count`() {
        customResult = failure
        repeat(2) { resolver.resolve("a.example") }
        customResult = customAddr
        resolver.resolve("a.example")
        customResult = failure
        repeat(2) { resolver.resolve("a.example") }
        assertEquals(DnsHealth.Healthy, resolver.health.value)
    }

    @Test
    fun `after the cooldown only ONE lookup probes the custom resolver, the rest use the system`() {
        val dead = AtomicBoolean(true)
        val probing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val r = FailOpenResolver(
            custom = HostResolver {
                if (dead.get()) failure else { probing.countDown(); release.await(); customAddr }
            },
            system = system, clock = clock, failureThreshold = 1, cooldownMs = 1_000L,
        )
        r.resolve("open-it")
        assertTrue(r.health.value is DnsHealth.Degraded)

        dead.set(false)
        clock.nowMs += 1_001L
        val probe = thread { r.resolve("probe") }
        assertTrue(probing.await(5, TimeUnit.SECONDS))

        val before = systemCalls
        assertEquals(systemAddr, r.resolve("concurrent"))
        assertEquals("a second lookup during the probe must use the system resolver", before + 1, systemCalls)

        release.countDown()
        probe.join(5_000)
        assertEquals("a good probe closes the breaker", DnsHealth.Healthy, r.health.value)
    }

    @Test
    fun `a success from a lookup that started before the breaker opened does not close it`() {
        val failing = AtomicBoolean(false)
        val stragglerStarted = CountDownLatch(1)
        val finishStraggler = CountDownLatch(1)
        val r = FailOpenResolver(
            custom = HostResolver { host ->
                if (host == "straggler") { stragglerStarted.countDown(); finishStraggler.await(); customAddr }
                else if (failing.get()) failure else customAddr
            },
            system = system, clock = clock, failureThreshold = 1, cooldownMs = 60_000L, slowMs = Long.MAX_VALUE,
        )
        val straggler = thread { r.resolve("straggler") }
        assertTrue(stragglerStarted.await(5, TimeUnit.SECONDS))

        failing.set(true)
        r.resolve("a.example")
        assertTrue(r.health.value is DnsHealth.Degraded)

        finishStraggler.countDown()
        straggler.join(5_000)
        assertTrue("a straggler's success must not close the breaker", r.health.value is DnsHealth.Degraded)
    }
}
