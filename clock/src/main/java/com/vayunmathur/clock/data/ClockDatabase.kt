package com.vayunmathur.clock.data

import androidx.room3.ColumnTypeConverters
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Delete
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import androidx.sqlite.execSQL
import com.vayunmathur.library.util.DatabaseItem
import com.vayunmathur.library.util.DefaultConverters
import androidx.room3.migration.Migration
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalTime
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * A persisted countdown timer, with its remaining length measured from the
 * dual anchors while running: [remainingStartTime] is the wall-clock anchor
 * and [remainingStartElapsedRealtime] the monotonic (`elapsedRealtime`)
 * anchor taken at the same moment. Remaining time is measured on the
 * monotonic base when that anchor is still usable (immune to wall-clock /
 * timezone jumps) and on the wall base after a reboot resets the monotonic
 * clock (see `platform/DualTimeBase`). Null monotonic anchor = a row written
 * before the dual base existed (or written paused): wall base only.
 */
@Entity
data class Timer(
    var isRunning: Boolean,
    var name: String,
    var remainingStartTime: Instant,
    var remainingLength: Duration,
    var totalLength: Duration,
    var remainingStartElapsedRealtime: Long? = null,
    @PrimaryKey(autoGenerate = true)
    override var id: Long = 0,
): DatabaseItem {
    fun stopped(
        wallNow: Instant = Clock.System.now(),
        monoNowMs: Long? = com.vayunmathur.clock.platform.DualTimeBase.elapsedRealtimeMs(),
    ): Timer {
        // Pausing after the finish line (e.g. the finish broadcast is still
        // in flight) must not persist a negative remaining length; the
        // notification pause path already clamps, this one did not.
        val remainingMs = com.vayunmathur.clock.platform.DualTimeBase.timerRemainingMs(
            remainingLength.inWholeMilliseconds,
            remainingStartTime.toEpochMilliseconds(),
            remainingStartElapsedRealtime,
            wallNow.toEpochMilliseconds(),
            monoNowMs,
        )
        return copy(
            isRunning = false,
            remainingLength = remainingMs.milliseconds,
            remainingStartElapsedRealtime = null,
        )
    }
    fun started(
        wallNow: Instant = Clock.System.now(),
        monoNowMs: Long? = com.vayunmathur.clock.platform.DualTimeBase.elapsedRealtimeMs(),
    ): Timer = copy(
        isRunning = true,
        remainingStartTime = wallNow,
        remainingStartElapsedRealtime = monoNowMs,
    )
}

/** A persisted alarm; [days] is a weekday bitmask with bit 0 as Sunday. */
@Entity
data class Alarm(
    var time: LocalTime,
    var name: String,
    var enabled: Boolean,
    var days: Int, // bitmask bit 0 = sunday
    var ringtoneUri: String? = null, // null = system default alarm sound; "silent" = no sound
    var vibrate: Boolean = true,
    var snoozeMinutes: Int = 5,
    var gradualVolumeSeconds: Int = 0, // 0 = play at full volume immediately
    /**
     * When a snoozed ring is due. Set when the user snoozes, cleared when that
     * snooze fires (or is superseded); the snooze PendingIntent lives only in
     * AlarmManager, so without this a reboot would silently drop it.
     */
    var snoozedUntil: Instant? = null,
    @PrimaryKey(autoGenerate = true)
    override var id: Long = 0,
): DatabaseItem

/** Room access for persisted [Timer] rows. */
@Dao
interface TimerDao {
    @Query("SELECT * FROM Timer")
    fun getAllFlow(): Flow<List<Timer>>

    @Query("SELECT * FROM Timer")
    suspend fun getAll(): List<Timer>

    @Query("SELECT * FROM Timer WHERE id = :id")
    suspend fun get(id: Long): Timer

    @Upsert
    suspend fun upsert(value: Timer): Long

    @Delete
    suspend fun delete(value: Timer): Int
}

/** Room access for persisted [Alarm] rows, ordered by time. */
@Dao
interface AlarmDao {
    @Query("SELECT * FROM Alarm ORDER BY time")
    fun getAllFlow(): Flow<List<Alarm>>

    @Query("SELECT * FROM Alarm ORDER BY time")
    suspend fun getAll(): List<Alarm>

    @Query("SELECT * FROM Alarm WHERE id = :id")
    suspend fun get(id: Long): Alarm

    @Upsert
    suspend fun upsert(value: Alarm): Long

    @Delete
    suspend fun delete(value: Alarm): Int
}

/** Room database holding the clock app's timers and alarms. */
@ColumnTypeConverters(DefaultConverters::class)
@Database(entities = [Timer::class, Alarm::class], version = 4, exportSchema = true)
abstract class ClockDatabase: RoomDatabase() {
    abstract fun timerDao(): TimerDao
    abstract fun alarmDao(): AlarmDao

    /** Migration definitions upgrading the clock database schema between versions. */
    companion object : com.vayunmathur.library.util.DatabaseMigrations {
        override val migrations: List<Migration> = listOf(
            Migration(1, 2) {
                it.execSQL("ALTER TABLE Alarm ADD COLUMN ringtoneUri TEXT")
                it.execSQL("ALTER TABLE Alarm ADD COLUMN vibrate INTEGER NOT NULL DEFAULT 1")
                it.execSQL("ALTER TABLE Alarm ADD COLUMN snoozeMinutes INTEGER NOT NULL DEFAULT 5")
                it.execSQL("ALTER TABLE Alarm ADD COLUMN gradualVolumeSeconds INTEGER NOT NULL DEFAULT 0")
            },
            Migration(2, 3) {
                it.execSQL("ALTER TABLE Alarm ADD COLUMN snoozedUntil INTEGER")
            },
            Migration(3, 4) {
                // Dual time base: monotonic anchor for running timers.
                // Nullable: pre-existing rows have no monotonic anchor and
                // keep measuring on the wall base until next started().
                it.execSQL("ALTER TABLE Timer ADD COLUMN remainingStartElapsedRealtime INTEGER")
            },
        )
    }
}
