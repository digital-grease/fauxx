package com.fauxx.network.dns

import java.net.InetAddress

/**
 * The outcome of one lookup. Three-way rather than "addresses or exception" because the fail-open
 * policy (#227) hinges on the difference between the last two: a resolver that ANSWERS "no such
 * host" has done its job and must not be second-guessed by the system resolver, or a filtering
 * custom resolver would silently turn into the blocking one the user was trying to get away from.
 * Only a resolver that could not answer at all is a failure.
 */
sealed interface Resolution {
    /** The resolver answered with addresses (a sinkhole's 0.0.0.0 included: that is an answer). */
    data class Addresses(val addresses: List<InetAddress>) : Resolution

    /** The resolver answered that the name does not exist (NXDOMAIN, or an empty answer). */
    data object NoSuchHost : Resolution

    /** The resolver could not be reached or did not answer in time. */
    data class Failure(val cause: Throwable) : Resolution
}

/** Blocking name resolution, called from the loopback proxy's connection threads. */
fun interface HostResolver {
    fun resolve(host: String): Resolution
}
