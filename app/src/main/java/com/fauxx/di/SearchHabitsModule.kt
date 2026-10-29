package com.fauxx.di

import com.fauxx.engine.modules.PersonaSearchHabits
import com.fauxx.engine.modules.SearchHabitsSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Gives the search module the active persona's search habits (#322 item 4). */
@Module
@InstallIn(SingletonComponent::class)
abstract class SearchHabitsModule {
    @Binds
    abstract fun bindSearchHabitsSource(habits: PersonaSearchHabits): SearchHabitsSource
}
