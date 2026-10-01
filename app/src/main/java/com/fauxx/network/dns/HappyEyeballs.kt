package com.fauxx.network.dns

import java.io.IOException
import java.io.InterruptedIOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Connect to the first reachable address of a host, RFC 8305 style (#227).
 *
 * Chromium normally races IPv6 against IPv4 itself, so a network with broken IPv6 never stalls a
 * page. Behind Fauxx's proxy Chromium never sees addresses, only a hostname, so the proxy has to
 * do the racing or every connection on such a network would sit out a full IPv6 timeout first.
 *
 * Addresses are interleaved IPv6-first; each attempt gets a [staggerMs] head start before the
 * next begins, and a failure starts the next immediately. The first socket to connect wins and
 * every other attempt is closed.
 */
object HappyEyeballs {

    /** One connection attempt. Injectable so tests can script reachability and timing. */
    fun interface Connector {
        fun connect(address: InetAddress, port: Int, timeoutMs: Int): Socket
    }

    val PLAIN_SOCKETS = Connector { address, port, timeoutMs ->
        Socket().apply {
            try {
                connect(InetSocketAddress(address, port), timeoutMs)
            } catch (e: IOException) {
                runCatching { close() }
                throw e
            }
        }
    }

    /** IPv6 and IPv4 interleaved, IPv6 first (RFC 8305 section 4), order otherwise preserved. */
    fun order(addresses: List<InetAddress>): List<InetAddress> {
        val v6 = addresses.filterIsInstance<Inet6Address>()
        val v4 = addresses.filter { it !is Inet6Address }
        val out = ArrayList<InetAddress>(addresses.size)
        for (i in 0 until maxOf(v6.size, v4.size)) {
            v6.getOrNull(i)?.let(out::add)
            v4.getOrNull(i)?.let(out::add)
        }
        return out
    }

    fun connect(
        addresses: List<InetAddress>,
        port: Int,
        timeoutMs: Int,
        staggerMs: Long = 250L,
        connector: Connector = PLAIN_SOCKETS,
    ): Socket {
        require(addresses.isNotEmpty()) { "no addresses to connect to" }
        val ordered = order(addresses)
        if (ordered.size == 1) return connector.connect(ordered[0], port, timeoutMs)

        val results = LinkedBlockingQueue<Result<Socket>>()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
        var started = 0
        var finished = 0
        var lastError: Throwable? = null
        val winners = mutableListOf<Socket>()

        fun startNext() {
            val address = ordered[started++]
            thread(isDaemon = true, name = "fauxx-he-$port") {
                results.put(runCatching { connector.connect(address, port, timeoutMs) })
            }
        }

        startNext()
        try {
            raceLoop@ while (finished < started || started < ordered.size) {
                val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (remainingMs <= 0) break
                val wait = if (started < ordered.size) minOf(staggerMs, remainingMs) else remainingMs
                val next = results.poll(wait, TimeUnit.MILLISECONDS)
                when {
                    next == null -> if (started < ordered.size) startNext()
                    next.isSuccess -> {
                        finished++
                        winners += next.getOrThrow()
                        break@raceLoop
                    }
                    else -> {
                        finished++
                        lastError = next.exceptionOrNull()
                        if (started < ordered.size) startNext()
                    }
                }
            }
        } catch (e: InterruptedException) {
            // The proxy is stopping (its executor interrupts workers). This must not escape as an
            // InterruptedException: the caller's thread is a pool worker, and an uncaught throwable
            // there reaches the app's default handler and kills the process. Close what connected,
            // reap what is still in flight, and report it as an ordinary I/O failure.
            winners.forEach { runCatching { it.close() } }
            reap(results, started - finished, timeoutMs)
            Thread.currentThread().interrupt()
            throw InterruptedIOException("connect interrupted").apply { initCause(e) }
        }

        // Any attempt still in flight may connect later; close it when it does.
        reap(results, started - finished, timeoutMs)

        return winners.firstOrNull()
            ?: throw IOException("could not connect to any of ${ordered.size} addresses on port $port", lastError)
    }

    private fun reap(results: LinkedBlockingQueue<Result<Socket>>, stragglers: Int, timeoutMs: Int) {
        if (stragglers <= 0) return
        thread(isDaemon = true, name = "fauxx-he-reaper") {
            repeat(stragglers) {
                results.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)?.getOrNull()?.let { runCatching { it.close() } }
            }
        }
    }
}
