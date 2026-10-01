package com.fauxx.network.dns

import com.fauxx.util.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How custom DNS is doing, for the dashboard's quiet notice (#227, U2). */
sealed interface DnsHealth {
    /** No custom resolver configured; the system resolver is in use by choice. */
    data object Off : DnsHealth

    /** The custom resolver is answering. */
    data object Healthy : DnsHealth

    /** Custom DNS stopped working at [sinceMs] (wall clock); lookups use the system resolver. */
    data class Degraded(val sinceMs: Long, val reason: String) : DnsHealth
}

/**
 * Fail-OPEN wrapper around the user's custom resolver (owner decision, #227): when the custom
 * resolver cannot answer, fall back to the system resolver so the engine keeps running, and report
 * it through [health] so the dashboard can say so quietly.
 *
 * - An ANSWER is never second-guessed. [Resolution.NoSuchHost] (NXDOMAIN) and sinkhole addresses
 *   are the custom resolver doing its job; only [Resolution.Failure] falls back. Otherwise a
 *   filtering custom resolver would silently become the blocking system one.
 * - A SLOW resolver is treated like a dead one, behind a three-state breaker. CLOSED: lookups go
 *   to the custom resolver; [failureThreshold] consecutive failures or slow answers OPEN it.
 *   OPEN: lookups go straight to the system resolver for [cooldownMs], so pages do not wait out a
 *   timeout each. Then exactly ONE lookup probes the custom resolver while every other lookup
 *   keeps using the system resolver; a good probe closes the breaker, a bad one reopens it. A page
 *   fans out dozens of lookups at once, so letting them all probe a still-dead resolver would stall
 *   every one of them for the full timeout, once per cooldown.
 * - A success from a lookup that started before the breaker opened is used but does not close it:
 *   only the probe decides that, so health does not flap.
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
    private var open = false
    private var openUntilMs = 0L
    private var probeInFlight = false

    private enum class Route { CUSTOM, PROBE, SYSTEM }

    override fun resolve(host: String): Resolution {
        val route = synchronized(lock) {
            when {
                !open -> Route.CUSTOM
                clock.elapsedRealtime() < openUntilMs || probeInFlight -> Route.SYSTEM
                else -> {
                    probeInFlight = true
                    Route.PROBE
                }
            }
        }
        if (route == Route.SYSTEM) return system.resolve(host)

        val started = clock.elapsedRealtime()
        val result = try {
            custom.resolve(host)
        } catch (e: RuntimeException) {
            Resolution.Failure(e)
        }
        val slow = clock.elapsedRealtime() - started > slowMs

        return when {
            result is Resolution.Failure -> {
                recordFailure(route, "${result.cause.javaClass.simpleName}: ${result.cause.message}")
                system.resolve(host)
            }
            slow -> {
                // A slow answer is still an answer; use it, but count it toward the breaker.
                recordFailure(route, "slow answers")
                result
            }
            else -> {
                recordSuccess(route)
                result
            }
        }
    }

    private fun recordFailure(route: Route, reason: String) = synchronized(lock) {
        if (route == Route.PROBE) probeInFlight = false
        consecutiveFailures++
        if (route == Route.PROBE || consecutiveFailures >= failureThreshold) {
            open = true
            openUntilMs = clock.elapsedRealtime() + cooldownMs
            if (_health.value !is DnsHealth.Degraded) {
                _health.value = DnsHealth.Degraded(clock.currentTimeMillis(), reason)
            }
        }
    }

    private fun recordSuccess(route: Route) = synchronized(lock) {
        if (route == Route.PROBE) probeInFlight = false
        // A straggler that began before the breaker opened does not get to close it.
        if (open && route != Route.PROBE) return@synchronized
        consecutiveFailures = 0
        open = false
        _health.value = DnsHealth.Healthy
    }
}
