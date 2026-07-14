package com.vayunmathur.clock.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Upsert
import com.vayunmathur.library.util.DatabaseItem
import com.vayunmathur.library.util.DefaultConverters
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalTime
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

@Entity
data class Timer(
    var isRunning: Boolean,
    var name: String,
    var remainingStartTime: Instant,
    var remainingLength: Duration,
    var totalLength: Duration,
    @PrimaryKey(autoGenerate = true)
    override var id: Long = 0,
): DatabaseItem {
    fun stopped(): Timer {
        val now = Clock.System.now()
        val remainingTime = remainingLength - (now - remainingStartTime)
        return copy(isRunning = false, remainingLength = remainingTime)
    }
    fun started(): Timer {
        val now = Clock.System.now()
        return copy(isRunning = true, remainingStartTime = now)
    }
}

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
    @PrimaryKey(autoGenerate = true)
    override var id: Long = 0,
): DatabaseItem

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

@TypeConverters(DefaultConverters::class)
@Database(entities = [Timer::class, Alarm::class], version = 2, exportSchema = false)
abstract class ClockDatabase: RoomDatabase() {
    abstract fun timerDao(): TimerDao
    abstract fun alarmDao(): AlarmDao

    companion object : com.vayunmathur.library.util.DatabaseMigrations {
        override val migrations: List<Migration> = listOf(
            Migration(1, 2) {
                it.execSQL("ALTER TABLE Alarm ADD COLUMN ringtoneUri TEXT")
                it.execSQL("ALTER TABLE Alarm ADD COLUMN vibrate INTEGER NOT NULL DEFAULT 1")
                it.execSQL("ALTER TABLE Alarm ADD COLUMN snoozeMinutes INTEGER NOT NULL DEFAULT 5")
                it.execSQL("ALTER TABLE Alarm ADD COLUMN gradualVolumeSeconds INTEGER NOT NULL DEFAULT 0")
            },
        )
    }
}
