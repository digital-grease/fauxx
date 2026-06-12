package com.fauxx.targeting.layer2

import android.app.Application
import androidx.room.Room
import com.fauxx.data.db.PhantomDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Retention (#172 audit fix): the keep-baseline prune must preserve the earliest AND latest of
 * EACH (platform, series), so the control series survives independently of the poisoned series and
 * the E2 drift / E3 control-divergence cards don't silently revert to "collecting" after the daily
 * RetentionWorker runs. Exercises the real SQL on an in-memory Room DB — the existing
 * RetentionWorkerTest mocks the DAO and never runs the query.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProfileSnapshotRetentionTest {

    private lateinit var db: PhantomDatabase
    private lateinit var dao: ProfileSnapshotDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), PhantomDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.profileSnapshotDao()
    }

    @After
    fun tearDown() = db.close()

    private fun snap(series: SnapshotSeries, at: Long) = ProfileSnapshot(
        platformName = "google", source = SnapshotSource.IMPORT,
        scrapedCategoriesJson = "[]", capturedAt = at, series = series,
    )

    @Test
    fun `retention keeps earliest and latest of each platform-series past the cutoff`() = runBlocking {
        dao.insert(snap(SnapshotSeries.POISONED, 1000L))
        dao.insert(snap(SnapshotSeries.POISONED, 2000L))
        dao.insert(snap(SnapshotSeries.POISONED, 3000L))
        dao.insert(snap(SnapshotSeries.CONTROL, 1500L))
        dao.insert(snap(SnapshotSeries.CONTROL, 3500L))

        // Cutoff is past every row. A series-blind prune (keep MIN(id) per platform only) would
        // leave ONE row total for "google"; the fixed per-(platform, series) prune must not.
        dao.deleteOlderThanKeepingBaseline(cutoff = 10_000L)

        val all = dao.observeAll().first()
        val poisoned = all.filter { it.series == SnapshotSeries.POISONED }.map { it.capturedAt }.toSet()
        val control = all.filter { it.series == SnapshotSeries.CONTROL }.map { it.capturedAt }.toSet()

        // POISONED keeps earliest(1000) + latest(3000) — so E2 drift still has its >= 2 rows;
        // the middle(2000) is pruned.
        assertEquals(setOf(1000L, 3000L), poisoned)
        // CONTROL survives INDEPENDENTLY (earliest 1500 + latest 3500) — the bug deleted it.
        assertEquals(setOf(1500L, 3500L), control)
    }
}
