package com.fauxx.di

import com.fauxx.engine.modules.AdPollutionModule
import com.fauxx.engine.modules.LocationSpoofModule
import com.fauxx.engine.modules.Module
import dagger.Binds
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides the full (unrestricted) module implementations for F-Droid / GitHub distribution. */
@dagger.Module
@InstallIn(SingletonComponent::class)
abstract class FlavorModulesModule {

    @Binds
    @Singleton
    @AdModuleImpl
    abstract fun bindAdModule(impl: AdPollutionModule): Module

    @Binds
    @Singleton
    @LocationModuleImpl
    abstract fun bindLocationModule(impl: LocationSpoofModule): Module
}
