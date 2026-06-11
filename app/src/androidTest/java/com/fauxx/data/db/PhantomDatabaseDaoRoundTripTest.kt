package com.fauxx.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fauxx.data.model.ActionType
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.scheduling.CircadianUsageEntity
import com.fauxx.targeting.layer1.AgeRange
import com.fauxx.targeting.layer1.DemographicProfileDao
import com.fauxx.targeting.layer1.Gender
import com.fauxx.targeting.layer1.Profession
import com.fauxx.targeting.layer1.Region
import com.fauxx.targeting.layer1.UserDemographicProfile
import com.fauxx.targeting.layer2.PlatformProfileCache
import com.fauxx.targeting.layer2.PlatformProfileDao
import com.fauxx.targeting.layer3.PersonaHistoryDao
import com.fauxx.targeting.layer3.PersonaHistoryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Round-trips every DAO of [PhantomDatabase] through a REAL SQLCipher-encrypted database, built
 * fresh at the current schema version (v6, no migrations). The point is to prove that values
 * survive the full Room <-> SQLCipher <-> Room path with their type-converted columns intact:
 *
 * - [ActionType] and [CategoryPool] persist via the explicit [PhantomTypeConverters] (enum.name).
 * - [AgeRange], [Gender], [Profession], [Region] have NO custom @TypeConverter anywhere; they
 *   persist via Room's BUILT-IN automatic enum adapter, which also stores `enum.name`. This test
 *   asserts those values come back as the exact same enum constants, which would catch any
 *   regression if someone ever swapped in a (mis-mapped) custom converter.
 *
 * Deliberately NON-Hilt and NON-test-Activity: the API 36 emulator throws START_CLASS_NOT_FOUND
 * for Hilt-annotated test activities, so this mirrors the plain-AndroidJUnit4 + SupportOpenHelper
 * setup of [PhantomDatabaseMigrationTest] (separate DB name so the two never collide).
 */
@RunWith(AndroidJUnit4::class)
class PhantomDatabaseDaoRoundTripTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Throwaway key — only this test process opens this DB, so any stable bytes work. */
    private val passphrase = "dao-roundtrip-test".toByteArray()

    private lateinit var db: PhantomDatabase

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        context.deleteDatabase(DB_NAME)
        db = Room.databaseBuilder(context, PhantomDatabase::class.java, DB_NAME)
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun actionLogDao_roundTripsActionTypeAndCategoryPool() = runBlocking {
        val dao = db.actionLogDao()

        val entry = ActionLogEntity(
            timestamp = 1_700_000_000_000L,
            actionType = ActionType.LOCATION_SPOOF,
            category = CategoryPool.OUTDOOR_RECREATION,
            detail = "round-trip detail string",
            success = true
        )
        val rowId = dao.insert(entry)
        assertTrue("insert must return a generated rowId", rowId > 0L)

        // getAllForExport: the full entity must come back with every field intact, which proves
        // the ActionType + CategoryPool converters survive the SQLCipher round-trip.
        val exported = dao.getAllForExport()
        assertEquals("exactly one row exported", 1, exported.size)
        val read = exported[0]
        assertEquals(rowId, read.id)
        assertEquals(1_700_000_000_000L, read.timestamp)
        assertEquals(ActionType.LOCATION_SPOOF, read.actionType)
        assertEquals(CategoryPool.OUTDOOR_RECREATION, read.category)
        assertEquals("round-trip detail string", read.detail)
        assertEquals(true, read.success)

        // countSince: counts only recent + successful rows (timestamp > since AND success = 1).
        val countRecent = dao.countSince(1_699_999_999_999L).first()
        assertEquals("the single recent successful row is counted", 1, countRecent)
        val countNone = dao.countSince(1_700_000_000_000L).first()
        assertEquals("strictly-greater cutoff excludes the row at the boundary", 0, countNone)

        // countPerCategorySince: the CategoryCount projection must map the TEXT column back to the
        // CategoryPool enum.
        val perCategory = dao.countPerCategorySince(1_699_999_999_999L)
        assertEquals("one category group", 1, perCategory.size)
        assertEquals(CategoryPool.OUTDOOR_RECREATION, perCategory[0].category)
        assertEquals(1, perCategory[0].count)

        // observeAll: first emission carries the inserted row.
        val observed = dao.observeAll().first()
        assertEquals(1, observed.size)
        assertEquals(rowId, observed[0].id)
        assertEquals(ActionType.LOCATION_SPOOF, observed[0].actionType)
        assertEquals(CategoryPool.OUTDOOR_RECREATION, observed[0].category)

        // deleteOlderThan: insert an old + a new row, delete strictly-older, assert only new remains.
        val oldId = dao.insert(
            ActionLogEntity(
                timestamp = 1_000L,
                actionType = ActionType.SEARCH_QUERY,
                category = CategoryPool.COOKING,
                detail = "old row",
                success = true
            )
        )
        val newId = dao.insert(
            ActionLogEntity(
                timestamp = 2_000_000_000_000L,
                actionType = ActionType.PAGE_VISIT,
                category = CategoryPool.TECHNOLOGY,
                detail = "new row",
                success = false
            )
        )
        assertTrue(oldId > 0L && newId > 0L)
        // Delete everything strictly older than the new row's timestamp: removes the original
        // 1.7e12 row and the 1_000L row, leaving only the 2.0e12 row.
        dao.deleteOlderThan(2_000_000_000_000L)
        val remaining = dao.getAllForExport()
        assertEquals("only rows at/after the cutoff survive", 1, remaining.size)
        assertEquals(newId, remaining[0].id)
        assertEquals("new row", remaining[0].detail)
        assertEquals(ActionType.PAGE_VISIT, remaining[0].actionType)
        assertEquals(CategoryPool.TECHNOLOGY, remaining[0].category)
        assertEquals(false, remaining[0].success)
    }

    @Test
    fun actionLogDao_roundTripsMetadataColumn() = runBlocking {
        val dao = db.actionLogDao()
        dao.insert(
            ActionLogEntity(
                timestamp = 1_700_000_000_001L,
                actionType = ActionType.COOKIE_HARVEST,
                category = CategoryPool.TECHNOLOGY,
                detail = "with-metadata",
                metadata = "{\"Page title\":\"Cart\"}"
            )
        )
        dao.insert(
            ActionLogEntity(
                timestamp = 1_700_000_000_002L,
                actionType = ActionType.DNS_LOOKUP,
                category = CategoryPool.FINANCE,
                detail = "no-metadata"
            )
        )
        val byDetail = dao.getAllForExport().associateBy { it.detail }
        assertEquals("{\"Page title\":\"Cart\"}", byDetail["with-metadata"]?.metadata)
        assertNull("default metadata column is null", byDetail["no-metadata"]?.metadata)
    }

    @Test
    fun demographicProfileDao_roundTripsAllDemographicEnums() = runBlocking {
        val dao: DemographicProfileDao = db.demographicProfileDao()

        // get() on an empty single-row table must be null.
        assertNull("no profile before upsert", dao.get())

        val profile = UserDemographicProfile(
            // id defaults to 1 (single-row table); DAO get()/observe() query WHERE id = 1.
            ageRange = AgeRange.AGE_25_34,
            gender = Gender.FEMALE,
            profession = Profession.ENGINEER,
            region = Region.WESTERN_EUROPE,
            interestsJson = UserDemographicProfile.serializeInterests(
                setOf(CategoryPool.TECHNOLOGY, CategoryPool.SCIENCE)
            ),
            customInterestsJson = UserDemographicProfile.serializeCustomInterests(
                listOf("vintage synths", "trail running")
            )
        )
        dao.upsert(profile)

        val read = dao.get()
        assertNotNull("profile must be readable after upsert", read)
        requireNotNull(read)
        assertEquals(1, read.id)
        // These four assertions prove the built-in enum adapters round-trip through SQLCipher.
        assertEquals(AgeRange.AGE_25_34, read.ageRange)
        assertEquals(Gender.FEMALE, read.gender)
        assertEquals(Profession.ENGINEER, read.profession)
        assertEquals(Region.WESTERN_EUROPE, read.region)
        assertEquals(profile.interestsJson, read.interestsJson)
        assertEquals(profile.customInterestsJson, read.customInterestsJson)
        // And the derived accessors reconstruct from the stored strings.
        assertEquals(setOf(CategoryPool.TECHNOLOGY, CategoryPool.SCIENCE), read.getInterests())
        assertEquals(listOf("vintage synths", "trail running"), read.getCustomInterests())
    }

    @Test
    fun platformProfileDao_roundTripsCachedPlatformProfile() = runBlocking {
        val dao: PlatformProfileDao = db.platformProfileDao()

        assertNull("no cached profile before upsert", dao.getByPlatform("google"))

        val cache = PlatformProfileCache(
            platformName = "google",
            scrapedCategoriesJson = "[\"TECHNOLOGY\",\"TRAVEL\"]",
            lastScraped = 1_700_000_123_456L
        )
        dao.upsert(cache)

        val read = dao.getByPlatform("google")
        assertNotNull("cached profile must be readable after upsert", read)
        requireNotNull(read)
        assertEquals("google", read.platformName)
        assertEquals("[\"TECHNOLOGY\",\"TRAVEL\"]", read.scrapedCategoriesJson)
        assertEquals(1_700_000_123_456L, read.lastScraped)

        // observeAll must also surface it.
        val all = dao.observeAll().first()
        assertEquals(1, all.size)
        assertEquals("google", all[0].platformName)
        assertEquals(1_700_000_123_456L, all[0].lastScraped)
    }

    @Test
    fun personaHistoryDao_roundTripsPersonaHistoryEntry() = runBlocking {
        val dao: PersonaHistoryDao = db.personaHistoryDao()

        val entry = PersonaHistoryEntity(
            personaJson = "{\"name\":\"synthetic-persona\",\"seed\":42}",
            createdAt = 1_700_000_555_000L
        )
        dao.insert(entry)

        // getRecentPersonas selects createdAt > since; use a cutoff strictly below our timestamp.
        val recent = dao.getRecentPersonas(1_700_000_554_999L)
        assertEquals("exactly one recent persona", 1, recent.size)
        val read = recent[0]
        assertTrue("insert must assign a generated id", read.id > 0L)
        assertEquals("{\"name\":\"synthetic-persona\",\"seed\":42}", read.personaJson)
        assertEquals(1_700_000_555_000L, read.createdAt)

        // A cutoff at/after the timestamp returns nothing (query is strictly-greater).
        val none = dao.getRecentPersonas(1_700_000_555_000L)
        assertTrue("strictly-greater cutoff excludes the boundary row", none.isEmpty())
    }

    @Test
    fun circadianUsageDao_roundTripsHistogramWithReplaceAndDelete() = runBlocking {
        val dao = db.circadianUsageDao()

        // Empty table to start.
        assertTrue("no circadian rows before upsert", dao.getAll().isEmpty())

        // Persist a full 24-bucket snapshot (the observer always writes all hours).
        val snapshot = (0 until 24).map { CircadianUsageEntity(hourOfDay = it, count = it.toLong()) }
        dao.upsertAll(snapshot)

        val read = dao.getAll().associateBy { it.hourOfDay }
        assertEquals("all 24 hour buckets round-trip", 24, read.size)
        assertEquals(0L, read[0]?.count)
        assertEquals(23L, read[23]?.count)

        // REPLACE on the hourOfDay primary key: re-upserting must overwrite, not duplicate.
        val updated = (0 until 24).map { CircadianUsageEntity(hourOfDay = it, count = (it * 2).toLong()) }
        dao.upsertAll(updated)
        val reread = dao.getAll()
        assertEquals("REPLACE must not create duplicate rows", 24, reread.size)
        assertEquals(46L, reread.first { it.hourOfDay == 23 }.count)

        // deleteAll wipes the learned rhythm (the "Clear My Profile" path).
        dao.deleteAll()
        assertTrue("deleteAll clears the histogram", dao.getAll().isEmpty())
    }

    private companion object {
        const val DB_NAME = "dao_roundtrip_test.db"
    }
}
