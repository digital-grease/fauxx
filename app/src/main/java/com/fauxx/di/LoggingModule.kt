package com.fauxx.di

import android.content.Context
import com.fauxx.logging.CrashDetector
import com.fauxx.logging.CrashReportWriter
import com.fauxx.logging.EncryptedFileTree
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing logging infrastructure: [EncryptedFileTree] for persistent
 * log capture, [CrashReportWriter] for crash reports, and [CrashDetector] for
 * post-crash UI flow.
 */
@Module
@InstallIn(SingletonComponent::class)
object LoggingModule {

    @Provides
    @Singleton
    fun provideEncryptedFileTree(
        @ApplicationContext context: Context,
        tinkKeyManager: TinkKeyManager
    ): EncryptedFileTree = EncryptedFileTree(context, tinkKeyManager)

    @Provides
    @Singleton
    fun provideCrashReportWriter(
        @ApplicationContext context: Context,
        encryptedFileTree: EncryptedFileTree
    ): CrashReportWriter = CrashReportWriter(context, encryptedFileTree)

    @Provides
    @Singleton
    fun provideCrashDetector(
        @ApplicationContext context: Context
    ): CrashDetector = CrashDetector(context)
}
