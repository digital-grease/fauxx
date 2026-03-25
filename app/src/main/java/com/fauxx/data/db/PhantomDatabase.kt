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
        PersonaHistoryEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(PhantomTypeConverters::class)
abstract class PhantomDatabase : RoomDatabase() {
    abstract fun actionLogDao(): ActionLogDao
    abstract fun demographicProfileDao(): DemographicProfileDao
    abstract fun platformProfileDao(): PlatformProfileDao
    abstract fun personaHistoryDao(): PersonaHistoryDao
}

/** Migration from v1 to v2: add composite index on action_log(timestamp, success). */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_action_log_timestamp_success` ON `action_log` (`timestamp`, `success`)")
    }
}

/** Room type converters for enum types. */
class PhantomTypeConverters {
    @TypeConverter
    fun fromActionType(value: ActionType): String = value.name

    @TypeConverter
    fun toActionType(value: String): ActionType = ActionType.valueOf(value)

    @TypeConverter
    fun fromCategoryPool(value: CategoryPool): String = value.name

    @TypeConverter
    fun toCategoryPool(value: String): CategoryPool = CategoryPool.valueOf(value)
}
