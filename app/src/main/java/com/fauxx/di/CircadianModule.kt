package com.fauxx.di

import com.fauxx.engine.scheduling.CircadianObserver
import com.fauxx.engine.scheduling.UsageHistogram
import com.fauxx.engine.scheduling.UsageObserver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Exposes the single [CircadianObserver] singleton under the two roles it plays (E10 #177):
 * the [UsageObserver] lifecycle hook the engine drives, and the [UsageHistogram] read side the
 * rate modulator consumes. Both provider methods receive the same Hilt-managed singleton
 * (CircadianObserver has an `@Inject` constructor and is `@Singleton`), so the engine and the
 * modulator observe one shared histogram.
 */
@Module
@InstallIn(SingletonComponent::class)
object CircadianModule {

    @Provides
    @Singleton
    fun provideUsageObserver(observer: CircadianObserver): UsageObserver = observer

    @Provides
    @Singleton
    fun provideUsageHistogram(observer: CircadianObserver): UsageHistogram = observer
}
