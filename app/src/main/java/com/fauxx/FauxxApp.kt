package com.fauxx

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.fauxx.logging.CrashReportWriter
import com.fauxx.logging.EncryptedFileTree
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

/**
 * Application class for Fauxx. Serves as the Hilt entry point and configures WorkManager
 * with Hilt worker factory for dependency injection in background workers.
 */
@HiltAndroidApp
class FauxxApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var encryptedFileTree: EncryptedFileTree

    @Inject
    lateinit var crashReportWriter: CrashReportWriter

    override fun onCreate() {
        super.onCreate()
        // Load SQLCipher native library early, before any Room database access.
        // Must happen at app startup — not lazily in a Hilt provider — to avoid
        // UnsatisfiedLinkError when a DAO is accessed before the DB singleton is created.
        try {
            System.loadLibrary("sqlcipher")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("FauxxApp", "Failed to load SQLCipher native library", e)
            throw e
        }

        // Plant Timber trees: DebugTree for logcat in debug builds, EncryptedFileTree always.
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        try {
            Timber.plant(encryptedFileTree)
        } catch (e: Exception) {
            Log.e("FauxxApp", "Failed to initialize encrypted file logging", e)
        }

        // Install crash handler that writes stack trace + recent logs to a file.
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            crashReportWriter.writeCrashReport(throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
