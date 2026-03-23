package com.fauxx.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt module for WorkManager integration. Worker factory binding is handled automatically
 * by Hilt via @HiltWorker annotation on individual worker classes.
 */
@Module
@InstallIn(SingletonComponent::class)
object WorkerModule
