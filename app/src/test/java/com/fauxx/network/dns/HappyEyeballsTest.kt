package com.fauxx.network.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.Collections

class HappyEyeballsTest {

    private val v6a = InetAddress.getByName("2001:db8::1")
    private val v6b = InetAddress.getByName("2001:db8::2")
    private val v4a = InetAddress.getByName("192.0.2.1")
    private val v4b = InetAddress.getByName("192.0.2.2")

    @Test
    fun `orders IPv6 first, interleaved with IPv4`() {
        assertEquals(listOf(v6a, v4a, v6b, v4b), HappyEyeballs.order(listOf(v4a, v4b, v6a, v6b)))
        assertEquals(listOf(v4a, v4b), HappyEyeballs.order(listOf(v4a, v4b)))
    }

    @Test
    fun `a broken IPv6 path falls through to IPv4 without waiting out a timeout`() {
        val winner = Socket()
        val started = System.nanoTime()
        val result = HappyEyeballs.connect(
            listOf(v6a, v4a), 443, timeoutMs = 10_000, staggerMs = 5_000,
            connector = { address, _, _ -> if (address == v6a) throw IOException("no route") else winner },
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertSame(winner, result)
        assertTrue("a failure must start the next attempt at once, took ${elapsedMs}ms", elapsedMs < 2_000)
    }

    @Test
    fun `a hanging IPv6 path loses to IPv4 after the stagger`() {
        val winner = Socket()
        val attempted = Collections.synchronizedList(mutableListOf<InetAddress>())
        val result = HappyEyeballs.connect(
            listOf(v6a, v4a), 443, timeoutMs = 10_000, staggerMs = 100,
            connector = { address, _, _ ->
                attempted += address
                if (address == v6a) { Thread.sleep(5_000); throw IOException("timed out") } else winner
            },
        )
        assertSame(winner, result)
        assertEquals(listOf(v6a, v4a), attempted.toList())
    }

    @Test
    fun `every address failing is an IOException`() {
        try {
            HappyEyeballs.connect(listOf(v6a, v4a), 443, timeoutMs = 2_000, staggerMs = 50,
                connector = { _, _, _ -> throw IOException("refused") })
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("2 addresses"))
        }
    }
}
