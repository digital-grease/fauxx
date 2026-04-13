package com.fauxx.di

import javax.inject.Qualifier

/** Qualifier for the ad/browsing diversification module (flavor-specific). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AdModuleImpl

/** Qualifier for the location noise module (flavor-specific). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LocationModuleImpl
