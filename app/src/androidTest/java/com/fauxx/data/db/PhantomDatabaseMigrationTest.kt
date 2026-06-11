package com.fauxx.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises [PhantomDatabase]'s real migrations under SQLCipher on a device — the path that
 * actually runs on a user's phone when they update the app. A broken migration here is silent
 * data loss or an open-time crash for every existing install, so this verifies both that the
 * 1->2->3->4->5->6 chain applies its schema changes AND that rows written before the update survive it.
 *
 * Room's exported schemas only include 3.json, so [androidx.room.testing.MigrationTestHelper]
 * (which needs 1.json/2.json to stand up an old DB) can't be used. Instead we hand-seed an
 * encrypted v1 database from the historical v1 schema (v3 minus the index added in 1->2 and the
 * column added in 2->3), then open it with Room at v3 and let the production migrations run.
 * Opening alone triggers Room's schema validation, so a migration that produces the wrong final
 * shape fails the test even before the explicit assertions.
 */
@RunWith(AndroidJUnit4::class)
class PhantomDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Throwaway key — the seeded DB and the Room open just have to agree; not the prod passphrase. */
    private val passphrase = "migration-test-passphrase".toByteArray()

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        context.deleteDatabase(TEST_DB)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun migrate1To6_preservesSeededRows_andAppliesSchemaChanges() {
        seedEncryptedV1Database()

        val db = buildRoomDatabase()
        try {
            // First access runs the 1->2->3->4->5->6 migration chain and validates the result against v6.
            val sdb = db.openHelper.writableDatabase

            // Rows written at v1 must survive the migration.
            sdb.query("SELECT detail, success FROM action_log").use { c ->
                assertTrue("seeded action_log row must survive the migration", c.moveToFirst())
                assertEquals("exactly one action_log row after migration", 1, c.count)
                assertEquals("v1-seeded-action", c.getString(0))
                assertEquals(1, c.getInt(1))
            }
            sdb.query("SELECT ageRange, customInterestsJson FROM user_demographic_profile WHERE id = 0").use { c ->
                assertTrue("seeded demographic row must survive the migration", c.moveToFirst())
                assertEquals("25-34", c.getString(0))
                assertTrue("customInterestsJson added by 2->3 defaults to NULL on existing rows", c.isNull(1))
            }

            // MIGRATION_1_2 created the composite index.
            sdb.query(
                "SELECT name FROM sqlite_master WHERE type='index' AND name='index_action_log_timestamp_success'"
            ).use { c ->
                assertTrue("MIGRATION_1_2 must create index_action_log_timestamp_success", c.moveToFirst())
            }
            // MIGRATION_2_3 added the column.
            assertTrue(
                "MIGRATION_2_3 must add user_demographic_profile.customInterestsJson",
                columnExists(sdb, "user_demographic_profile", "customInterestsJson")
            )
            // MIGRATION_3_4 added the nullable metadata column (issue #73).
            assertTrue(
                "MIGRATION_3_4 must add action_log.metadata",
                columnExists(sdb, "action_log", "metadata")
            )
            sdb.query("SELECT metadata FROM action_log").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue("metadata defaults to NULL on pre-existing rows", c.isNull(0))
            }
            // MIGRATION_4_5 created the profile_snapshot history table (issue #170 E1).
            sdb.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='profile_snapshot'"
            ).use { c ->
                assertTrue("MIGRATION_4_5 must create profile_snapshot", c.moveToFirst())
            }
            // MIGRATION_5_6 created the circadian_usage histogram table (issue #177 E10).
            sdb.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='circadian_usage'"
            ).use { c ->
                assertTrue("MIGRATION_5_6 must create circadian_usage", c.moveToFirst())
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun freshCreateAtV6_roundTripsThroughSqlcipher() {
        val db = buildRoomDatabase()
        try {
            val sdb = db.openHelper.writableDatabase
            sdb.execSQL(
                "INSERT INTO action_log (timestamp, actionType, category, detail, success) " +
                    "VALUES (1700000000000, 'SEARCH_POISON', 'COOKING', 'fresh-create-row', 1)"
            )
            sdb.query("SELECT count(*) FROM action_log").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
            // The index ships on a fresh v6 create too, not just via the migration path.
            sdb.query(
                "SELECT name FROM sqlite_master WHERE type='index' AND name='index_action_log_timestamp_success'"
            ).use { c ->
                assertTrue("fresh v6 create must include the composite index", c.moveToFirst())
            }
            assertTrue(columnExists(sdb, "user_demographic_profile", "customInterestsJson"))
            assertTrue(
                "fresh v6 create must include action_log.metadata",
                columnExists(sdb, "action_log", "metadata")
            )
            // A fresh create at v6 ships the circadian_usage table directly (issue #177 E10).
            sdb.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='circadian_usage'"
            ).use { c ->
                assertTrue("fresh v6 create must include circadian_usage", c.moveToFirst())
            }
        } finally {
            db.close()
        }
    }

    private fun buildRoomDatabase(): PhantomDatabase =
        Room.databaseBuilder(context, PhantomDatabase::class.java, TEST_DB)
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .build()

    /**
     * Stand up an encrypted database at schema version 1 — the historical shape, i.e. v3 without
     * the `index_action_log_timestamp_success` index (added in 1->2) and without the
     * `user_demographic_profile.customInterestsJson` column (added in 2->3) — and seed one row in
     * each migrated table.
     */
    private fun seedEncryptedV1Database() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB)
            .callback(object : SupportSQLiteOpenHelper.Callback(VERSION_1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `action_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`timestamp` INTEGER NOT NULL, `actionType` TEXT NOT NULL, `category` TEXT NOT NULL, " +
                            "`detail` TEXT NOT NULL, `success` INTEGER NOT NULL)"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `user_demographic_profile` (`id` INTEGER NOT NULL, `ageRange` TEXT, " +
                            "`gender` TEXT, `profession` TEXT, `region` TEXT, `interestsJson` TEXT, PRIMARY KEY(`id`))"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `platform_profile_cache` (`platformName` TEXT NOT NULL, " +
                            "`scrapedCategoriesJson` TEXT NOT NULL, `lastScraped` INTEGER NOT NULL, PRIMARY KEY(`platformName`))"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `persona_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`personaJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val helper = SupportOpenHelperFactory(passphrase).create(config)
        val sdb = helper.writableDatabase
        sdb.execSQL(
            "INSERT INTO action_log (timestamp, actionType, category, detail, success) " +
                "VALUES (1700000000000, 'SEARCH_POISON', 'COOKING', 'v1-seeded-action', 1)"
        )
        sdb.execSQL(
            "INSERT INTO user_demographic_profile (id, ageRange, gender, profession, region, interestsJson) " +
                "VALUES (0, '25-34', NULL, NULL, NULL, NULL)"
        )
        helper.close()
    }

    private fun columnExists(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
        db.query("PRAGMA table_info(`$table`)").use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) {
                if (c.getString(nameIdx) == column) return true
            }
        }
        return false
    }

    private companion object {
        const val TEST_DB = "phantom-migration-test.db"
        const val VERSION_1 = 1
    }
}
