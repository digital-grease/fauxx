package com.fauxx.di

import com.fauxx.engine.modules.DiverseBrowsingModule
import com.fauxx.engine.modules.LocationSignalModule
import com.fauxx.engine.modules.Module
import dagger.Binds
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides Play Store-safe module implementations. */
@dagger.Module
@InstallIn(SingletonComponent::class)
abstract class FlavorModulesModule {

    @Binds
    @Singleton
    @AdModuleImpl
    abstract fun bindAdModule(impl: DiverseBrowsingModule): Module

    @Binds
    @Singleton
    @LocationModuleImpl
    abstract fun bindLocationModule(impl: LocationSignalModule): Module
}
