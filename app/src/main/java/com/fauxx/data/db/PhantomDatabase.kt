package com.fauxx.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.fauxx.data.model.ActionType
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.targeting.layer1.DemographicProfileDao
import com.fauxx.targeting.layer1.UserDemographicProfile
import com.fauxx.targeting.layer2.PlatformProfileCache
import com.fauxx.targeting.layer2.PlatformProfileDao
import com.fauxx.targeting.layer2.ProfileSnapshot
import com.fauxx.targeting.layer2.ProfileSnapshotDao
import com.fauxx.targeting.layer2.SnapshotSource
import com.fauxx.targeting.layer3.PersonaHistoryDao
import com.fauxx.targeting.layer3.PersonaHistoryEntity

/**
 * Room database for Fauxx. Encrypted via SQLCipher with AndroidKeyStore-backed key.
 *
 * Tables:
 * - action_log: Audit log of all synthetic actions
 * - user_demographic_profile: Optional self-reported user demographics (single row)
 * - platform_profile_cache: Cached ad-platform assigned categories
 * - persona_history: History of generated synthetic personas
 */
@Database(
    entities = [
        ActionLogEntity::class,
        UserDemographicProfile::class,
        PlatformProfileCache::class,
        ProfileSnapshot::class,
        PersonaHistoryEntity::class
    ],
    version = 5,
    exportSchema = true
)
@TypeConverters(PhantomTypeConverters::class)
abstract class PhantomDatabase : RoomDatabase() {
    abstract fun actionLogDao(): ActionLogDao
    abstract fun demographicProfileDao(): DemographicProfileDao
    abstract fun platformProfileDao(): PlatformProfileDao
    abstract fun profileSnapshotDao(): ProfileSnapshotDao
    abstract fun personaHistoryDao(): PersonaHistoryDao
}

/** Migration from v1 to v2: add composite index on action_log(timestamp, success). */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_action_log_timestamp_success` ON `action_log` (`timestamp`, `success`)")
    }
}

/** Migration from v2 to v3: add customInterestsJson column to user_demographic_profile. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `user_demographic_profile` ADD COLUMN `customInterestsJson` TEXT DEFAULT NULL")
    }
}

/** Migration from v3 to v4: add nullable metadata column to action_log (issue #73). */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `action_log` ADD COLUMN `metadata` TEXT DEFAULT NULL")
    }
}

/** Migration from v4 to v5: add the append-only profile_snapshot history table (issue #170 E1). */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `profile_snapshot` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`platformName` TEXT NOT NULL, " +
                "`source` TEXT NOT NULL, " +
                "`scrapedCategoriesJson` TEXT NOT NULL, " +
                "`capturedAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_profile_snapshot_platformName_capturedAt` " +
                "ON `profile_snapshot` (`platformName`, `capturedAt`)"
        )
    }
}

/** Room type converters for enum types. */
class PhantomTypeConverters {
    @TypeConverter
    fun fromActionType(value: ActionType): String = value.name

    @TypeConverter
    fun toActionType(value: String): ActionType =
        // Tolerate values from retired enum members (e.g. the former AD_CLICK) on
        // existing rows so an upgrade never crashes on deserialization.
        runCatching { ActionType.valueOf(value) }.getOrDefault(ActionType.PAGE_VISIT)

    @TypeConverter
    fun fromCategoryPool(value: CategoryPool): String = value.name

    @TypeConverter
    fun toCategoryPool(value: String): CategoryPool = CategoryPool.valueOf(value)

    @TypeConverter
    fun fromSnapshotSource(value: SnapshotSource): String = value.name

    @TypeConverter
    fun toSnapshotSource(value: String): SnapshotSource =
        runCatching { SnapshotSource.valueOf(value) }.getOrDefault(SnapshotSource.IMPORT)
}
