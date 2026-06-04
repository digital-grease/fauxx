package com.fauxx.util

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Hilt-provided source of behavioral randomness so generators, schedulers, and modules
 * can be exercised deterministically in tests. Production uses [Random.Default]; tests can
 * substitute a seeded [Random] either by constructing the consumer directly (the consumers
 * default this parameter to [Random.Default]) or via a `@TestInstallIn` module that replaces
 * [RandomModule], so probabilistic branches (burst chance, Markov split, weighted sampling,
 * persona jitter) can be pinned.
 *
 * This binding is for behavioral randomness only. Cryptographic randomness (database
 * passphrase / key generation in [com.fauxx.di.TinkKeyManager]) uses [java.security.SecureRandom]
 * directly and must never be routed through this seam or seeded.
 */
@Module
@InstallIn(SingletonComponent::class)
object RandomModule {
    @Provides
    @Singleton
    fun provideRandom(): Random = Random.Default
}
