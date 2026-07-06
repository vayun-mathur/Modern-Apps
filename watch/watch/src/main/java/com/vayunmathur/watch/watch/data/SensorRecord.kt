package com.vayunmathur.watch.watch.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

enum class MetricType {
    HeartRate,
    Steps,
    Pressure,
    Motion,
}

@Entity
data class SensorRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: MetricType,
    val timestamp: Long,
    // For HeartRate: the bpm reading. For Steps: cumulative counter value.
    // For Pressure: hPa. For Motion: 1.0 = stationary, 0.0 = moving.
    val value: Double,
    // For Steps: increment since the previous reading. Otherwise 0.
    val delta: Double = 0.0,
    // For HeartRate: whether the wearer was stationary when sampled.
    val stationary: Boolean = false,
)

@Dao
interface SensorDao {
    @Insert
    suspend fun insert(record: SensorRecord)

    @Query("SELECT * FROM SensorRecord ORDER BY timestamp ASC")
    suspend fun getAll(): List<SensorRecord>

    @Query("SELECT * FROM SensorRecord ORDER BY timestamp ASC")
    fun getAllFlow(): Flow<List<SensorRecord>>

    @Query("SELECT COUNT(*) FROM SensorRecord")
    fun countFlow(): Flow<Int>

    @Query("DELETE FROM SensorRecord WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}

@Database(entities = [SensorRecord::class], version = 1, exportSchema = false)
abstract class SensorDatabase : RoomDatabase() {
    abstract fun sensorDao(): SensorDao

    companion object {
        @Volatile
        private var INSTANCE: SensorDatabase? = null

        fun get(context: Context): SensorDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SensorDatabase::class.java,
                    "sensor.db",
                ).build().also { INSTANCE = it }
            }
    }
}
