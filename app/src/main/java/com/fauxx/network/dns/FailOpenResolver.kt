package com.fauxx.network.dns

import com.fauxx.util.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the custom resolver is doing, for the dashboard's quiet notice (#227, U2). */
sealed interface DnsHealth {
    /** No custom resolver configured; the system resolver is in use by choice. */
    data object Off : DnsHealth

    /** The custom resolver is answering. */
    data object Healthy : DnsHealth

    /** The custom resolver stopped answering at [sinceMs] (wall clock); lookups use the system resolver. */
    data class Degraded(val sinceMs: Long, val reason: String) : DnsHealth
}

/**
 * Fail-OPEN wrapper around the user's custom resolver (owner decision, #227): when the custom
 * resolver cannot answer, fall back to the system resolver so the engine keeps running, and report
 * it through [health] so the dashboard can say so quietly.
 *
 * Two details matter more than they look:
 * - An ANSWER is never second-guessed. [Resolution.NoSuchHost] (NXDOMAIN) and sinkhole addresses
 *   are the custom resolver doing its job; only [Resolution.Failure] falls back. Otherwise a
 *   filtering custom resolver would silently become the blocking system one.
 * - A SLOW resolver is treated like a dead one. Without a breaker, every connection would wait
 *   out the DoH timeout before falling back and pages would time out: an accidental fail-closed.
 *   So after [failureThreshold] consecutive failures or slow answers the breaker opens and lookups
 *   go straight to the system resolver for [cooldownMs]; the next lookup after that tries the
 *   custom resolver again (half-open), and one good answer closes the breaker.
 *
 * Thread-safe: the proxy resolves from many connection threads at once.
 */
class FailOpenResolver(
    private val custom: HostResolver,
    private val system: HostResolver,
    private val clock: Clock,
    private val failureThreshold: Int = 3,
    private val cooldownMs: Long = 60_000L,
    private val slowMs: Long = 3_000L,
) : HostResolver {

    private val _health = MutableStateFlow<DnsHealth>(DnsHealth.Healthy)
    val health: StateFlow<DnsHealth> = _health.asStateFlow()

    private val lock = Any()
    private var consecutiveFailures = 0
    private var openUntilMs = 0L

    override fun resolve(host: String): Resolution {
        if (breakerOpen()) return system.resolve(host)

        val started = clock.elapsedRealtime()
        val result = custom.resolve(host)
        val elapsed = clock.elapsedRealtime() - started

        return when {
            result is Resolution.Failure -> {
                recordFailure("${result.cause.javaClass.simpleName}: ${result.cause.message}")
                system.resolve(host)
            }
            elapsed > slowMs -> {
                // A slow answer is still an answer; use it, but count it toward the breaker.
                recordFailure("slow answer (${elapsed}ms)")
                result
            }
            else -> {
                recordSuccess()
                result
            }
        }
    }

    private fun breakerOpen(): Boolean = synchronized(lock) { clock.elapsedRealtime() < openUntilMs }

    private fun recordFailure(reason: String) = synchronized(lock) {
        consecutiveFailures++
        if (consecutiveFailures >= failureThreshold) {
            openUntilMs = clock.elapsedRealtime() + cooldownMs
            if (_health.value !is DnsHealth.Degraded) {
                _health.value = DnsHealth.Degraded(clock.currentTimeMillis(), reason)
            }
        }
    }

    private fun recordSuccess() = synchronized(lock) {
        consecutiveFailures = 0
        openUntilMs = 0L
        _health.value = DnsHealth.Healthy
    }
}
