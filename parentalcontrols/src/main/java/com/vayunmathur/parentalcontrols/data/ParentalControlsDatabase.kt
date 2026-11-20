package com.vayunmathur.parentalcontrols.data

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Delete
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

const val DB_NAME = "parentalcontrols-db"

/** Room DAO for per-app supervision rules. */
@Dao
interface AppRuleDao {
    @Query("SELECT * FROM AppRule ORDER BY packageName")
    fun allFlow(): Flow<List<AppRule>>

    @Query("SELECT * FROM AppRule")
    suspend fun all(): List<AppRule>

    @Query("SELECT * FROM AppRule WHERE packageName = :packageName")
    suspend fun byPackage(packageName: String): AppRule?

    @Upsert
    suspend fun upsert(rule: AppRule)

    @Delete
    suspend fun delete(rule: AppRule)
}

/** Room DAO for the singleton bedtime schedule. */
@Dao
interface BedtimeDao {
    @Query("SELECT * FROM BedtimeSchedule WHERE id = :id")
    fun scheduleFlow(id: Int = BedtimeSchedule.SINGLETON_ID): Flow<BedtimeSchedule?>

    @Query("SELECT * FROM BedtimeSchedule WHERE id = :id")
    suspend fun schedule(id: Int = BedtimeSchedule.SINGLETON_ID): BedtimeSchedule?

    @Upsert
    suspend fun upsert(schedule: BedtimeSchedule)
}

/** Room DAO for the singleton downtime schedule. */
@Dao
interface DowntimeDao {
    @Query("SELECT * FROM DowntimeSchedule WHERE id = :id")
    fun scheduleFlow(id: Int = DowntimeSchedule.SINGLETON_ID): Flow<DowntimeSchedule?>

    @Query("SELECT * FROM DowntimeSchedule WHERE id = :id")
    suspend fun schedule(id: Int = DowntimeSchedule.SINGLETON_ID): DowntimeSchedule?

    @Upsert
    suspend fun upsert(schedule: DowntimeSchedule)
}

/** Room DAO for the singleton school-time schedule. */
@Dao
interface SchoolTimeDao {
    @Query("SELECT * FROM SchoolTimeSchedule WHERE id = :id")
    fun scheduleFlow(id: Int = SchoolTimeSchedule.SINGLETON_ID): Flow<SchoolTimeSchedule?>

    @Query("SELECT * FROM SchoolTimeSchedule WHERE id = :id")
    suspend fun schedule(id: Int = SchoolTimeSchedule.SINGLETON_ID): SchoolTimeSchedule?

    @Upsert
    suspend fun upsert(schedule: SchoolTimeSchedule)
}

/** Room DAO for the singleton device-wide daily limit. */
@Dao
interface DailyLimitDao {
    @Query("SELECT * FROM DailyLimit WHERE id = :id")
    fun limitFlow(id: Int = DailyLimit.SINGLETON_ID): Flow<DailyLimit?>

    @Query("SELECT * FROM DailyLimit WHERE id = :id")
    suspend fun limit(id: Int = DailyLimit.SINGLETON_ID): DailyLimit?

    @Upsert
    suspend fun upsert(limit: DailyLimit)
}

/** Room DAO for one-day bonus-time grants. */
@Dao
interface BonusGrantDao {
    @Query("SELECT * FROM BonusGrant WHERE day = :day")
    suspend fun forDay(day: String): List<BonusGrant>

    @Upsert
    suspend fun upsert(grant: BonusGrant)

    @Query("DELETE FROM BonusGrant WHERE day != :day")
    suspend fun pruneForeignDays(day: String)
}

/** Room database holding all parental-controls rules and schedules. */
@Database(
    entities = [
        AppRule::class,
        BedtimeSchedule::class,
        DowntimeSchedule::class,
        SchoolTimeSchedule::class,
        DailyLimit::class,
        BonusGrant::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class ParentalControlsDatabase : RoomDatabase() {
    abstract fun appRuleDao(): AppRuleDao
    abstract fun bedtimeDao(): BedtimeDao
    abstract fun downtimeDao(): DowntimeDao
    abstract fun schoolTimeDao(): SchoolTimeDao
    abstract fun dailyLimitDao(): DailyLimitDao
    abstract fun bonusGrantDao(): BonusGrantDao
}
