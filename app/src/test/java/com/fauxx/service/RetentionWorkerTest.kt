package com.fauxx.service

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.fauxx.data.db.ActionLogDao
import com.fauxx.di.PreferenceKeys
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.abs

/** Tests for the issue #73 log-retention worker: cutoff derived from the setting, default, retry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RetentionWorkerTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val dao: ActionLogDao = mockk(relaxed = true)
    private val dayMs = 24L * 60 * 60 * 1000

    private fun worker(dataStore: DataStore<Preferences>): RetentionWorker =
        TestListenableWorkerBuilder<RetentionWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): RetentionWorker = RetentionWorker(appContext, workerParameters, dao, mockk(relaxed = true), dataStore)
            })
            .build()

    private fun dataStoreWith(prefs: Preferences): DataStore<Preferences> =
        mockk { every { data } returns flowOf(prefs) }

    @Test
    fun `prunes entries older than the configured retention window`() = runBlocking {
        val ds = dataStoreWith(mutablePreferencesOf(PreferenceKeys.LOG_RETENTION_DAYS to 3))
        val cutoff = slot<Long>()
        val before = System.currentTimeMillis()

        val result = worker(ds).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        coVerify { dao.deleteOlderThan(capture(cutoff)) }
        assertTrue("cutoff ~ now - 3d", abs(cutoff.captured - (before - 3 * dayMs)) < 10_000L)
    }

    @Test
    fun `defaults to 7 days when the setting is unset`() = runBlocking {
        val ds = dataStoreWith(mutablePreferencesOf())
        val cutoff = slot<Long>()
        val before = System.currentTimeMillis()

        worker(ds).doWork()

        coVerify { dao.deleteOlderThan(capture(cutoff)) }
        assertTrue("cutoff ~ now - 7d", abs(cutoff.captured - (before - 7 * dayMs)) < 10_000L)
    }

    @Test
    fun `retries when the delete throws`() = runBlocking {
        val ds = dataStoreWith(mutablePreferencesOf(PreferenceKeys.LOG_RETENTION_DAYS to 5))
        coEvery { dao.deleteOlderThan(any()) } throws RuntimeException("db down")

        val result = worker(ds).doWork()

        assertTrue(result is ListenableWorker.Result.Retry)
    }
}
