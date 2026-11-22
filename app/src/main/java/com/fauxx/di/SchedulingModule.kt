package com.fauxx.di

import com.fauxx.engine.scheduling.ActionPacer
import com.fauxx.engine.scheduling.PersonaSessionHabits
import com.fauxx.engine.scheduling.SessionHabitsSource
import com.fauxx.engine.scheduling.SessionScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Paces the engine in sessions, with the active persona's session habits (#322 item 1). */
@Module
@InstallIn(SingletonComponent::class)
abstract class SchedulingModule {
    @Binds
    abstract fun bindActionPacer(scheduler: SessionScheduler): ActionPacer

    @Binds
    abstract fun bindSessionHabitsSource(habits: PersonaSessionHabits): SessionHabitsSource
}
