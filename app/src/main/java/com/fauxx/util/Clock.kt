package com.fauxx.util

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin abstraction over wall-clock and monotonic-clock time so tests can run engine
 * code paths under virtual time without taking real wall-clock seconds. Production
 * code uses [SystemClockImpl]; tests can substitute a fake whose values they control.
 *
 * - [currentTimeMillis] is wall-clock epoch ms; used for "what time of day is it" logic
 *   (quiet-hours boundary, schedule-resume-at-time).
 * - [elapsedRealtime] is monotonic since boot; used for measuring elapsed durations
 *   (FGS uptime budget, time-spent-in-current-pause-state). Matches Android's
 *   [android.os.SystemClock.elapsedRealtime].
 */
interface Clock {
    fun currentTimeMillis(): Long
    fun elapsedRealtime(): Long
}

@Singleton
class SystemClockImpl @Inject constructor() : Clock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
    override fun elapsedRealtime(): Long = android.os.SystemClock.elapsedRealtime()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ClockModule {
    @Binds
    @Singleton
    abstract fun bindClock(impl: SystemClockImpl): Clock
}
