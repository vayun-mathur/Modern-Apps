package com.vayunmathur.screentime.data

import android.content.Context
import androidx.room3.ColumnTypeConverters
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Delete
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import com.vayunmathur.library.room.RoomRepository
import com.vayunmathur.library.util.DefaultConverters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

const val DB_NAME = "screentime-db"

@Dao
interface AppTimerDao {
    @Query("SELECT * FROM AppTimer ORDER BY packageName")
    fun allFlow(): Flow<List<AppTimer>>

    @Query("SELECT * FROM AppTimer")
    suspend fun all(): List<AppTimer>

    @Query("SELECT * FROM AppTimer WHERE packageName = :packageName")
    suspend fun byPackage(packageName: String): AppTimer?

    @Upsert
    suspend fun upsert(timer: AppTimer)

    @Delete
    suspend fun delete(timer: AppTimer)
}

@Dao
interface PausedAppsDao {
    @Query("SELECT * FROM PausedApps WHERE id = :id")
    fun pausedFlow(id: Int = PausedApps.SINGLETON_ID): Flow<PausedApps?>

    @Query("SELECT * FROM PausedApps WHERE id = :id")
    suspend fun paused(id: Int = PausedApps.SINGLETON_ID): PausedApps?

    @Upsert
    suspend fun upsert(paused: PausedApps)
}

@Dao
interface WindDownDao {
    @Query("SELECT * FROM WindDownSchedule WHERE id = :id")
    fun scheduleFlow(id: Int = WindDownSchedule.SINGLETON_ID): Flow<WindDownSchedule?>

    @Query("SELECT * FROM WindDownSchedule WHERE id = :id")
    suspend fun schedule(id: Int = WindDownSchedule.SINGLETON_ID): WindDownSchedule?

    @Upsert
    suspend fun upsert(schedule: WindDownSchedule)
}

@ColumnTypeConverters(DefaultConverters::class)
@Database(
    entities = [AppTimer::class, PausedApps::class, WindDownSchedule::class],
    version = 2,
    exportSchema = false,
)
abstract class ScreenTimeDatabase : RoomDatabase() {
    abstract fun appTimerDao(): AppTimerDao
    abstract fun pausedAppsDao(): PausedAppsDao
    abstract fun windDownDao(): WindDownDao
}

/**
 * The single owner of [ScreenTimeDatabase], shared by the UI, the schedule receiver, the
 * usage observers and the dashboard.
 *
 * Mirrors parental controls' `SupervisionRules`: enforcement runs with no Activity alive, so
 * this is a process-wide singleton rather than a ViewModel dependency.
 */
class ScreenTimeRules private constructor(context: Context) :
    RoomRepository<ScreenTimeDatabase>(
        context,
        ScreenTimeDatabase::class,
        DB_NAME,
        migrations = listOf(MIGRATION_1_2),
    ) {

    private val timers get() = db.appTimerDao()
    private val paused get() = db.pausedAppsDao()
    private val windDown get() = db.windDownDao()

    // Getters, not vals: a val here would open the database at construction,
    // before any caller has asked for data.
    val allTimers: Flow<List<AppTimer>> get() = timers.allFlow()

    suspend fun allTimersNow(): List<AppTimer> = timers.all()

    suspend fun timer(packageName: String): AppTimer? = timers.byPackage(packageName)

    suspend fun upsert(timer: AppTimer) = timers.upsert(timer)

    suspend fun delete(timer: AppTimer) = timers.delete(timer)

    /** The user-paused set, defaulted rather than nullable. */
    val pausedApps: Flow<PausedApps> get() =
        paused.pausedFlow().map { it ?: PausedApps() }

    suspend fun pausedNow(): PausedApps = paused.paused() ?: PausedApps()

    suspend fun setPaused(paused: PausedApps) = this.paused.upsert(paused)

    /** The wind-down schedule, defaulted rather than nullable. */
    val windDownSchedule: Flow<WindDownSchedule> get() =
        windDown.scheduleFlow().map { it ?: WindDownSchedule() }

    suspend fun windDownNow(): WindDownSchedule = windDown.schedule() ?: WindDownSchedule()

    suspend fun setWindDown(schedule: WindDownSchedule) = windDown.upsert(schedule)

    companion object {
        @Volatile private var instance: ScreenTimeRules? = null

        fun get(context: Context): ScreenTimeRules =
            instance ?: synchronized(this) {
                instance ?: ScreenTimeRules(context).also { instance = it }
            }
    }
}
