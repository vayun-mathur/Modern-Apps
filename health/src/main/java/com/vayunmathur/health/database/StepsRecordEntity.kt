package com.vayunmathur.health.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.Period
import java.time.ZoneOffset

enum class RecordType {
    Steps, Wheelchair, Distance, CaloriesTotal, CaloriesActive, CaloriesBasal, Floors, Elevation,
    HeartRate, RestingHeartRate, HeartRateVariabilityRmssd, RespiratoryRate, OxygenSaturation,
    BloodPressure, BloodGlucose, Vo2Max, SkinTemperature,
    Weight, Height, BodyFat, LeanBodyMass, BoneMass, BodyWaterMass,
    Sleep, Mindfulness, Hydration, Nutrition
}

@Entity
data class Record(
    val id: String,
    val index : Int, // for multisample records
    val type: RecordType,
    val startTime: Instant,
    val endTime: Instant,
    val value: Double,
    val secondaryValue: Double = 0.0,
    @PrimaryKey val primaryKey: String = "$id-$index",
)

@Dao
interface HealthDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(records: List<Record>)

    @Query("DELETE FROM Record WHERE primaryKey IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT * FROM Record WHERE type = :type ORDER BY startTime DESC")
    suspend fun getRecords(type: RecordType): List<Record>

    @Query("SELECT * FROM Record WHERE primaryKey = :id")
    suspend fun getRecord(id: String): Record?

    @Query("SELECT * FROM Record WHERE type = :type ORDER BY startTime DESC LIMIT 1")
    suspend fun getLastRecord(type: RecordType): Record?

    @Query("SELECT SUM(value) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>
    @Query("SELECT SUM(value) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    suspend fun sumInRangeGet(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Double
    @Query("SELECT MIN(value) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun minInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>
    @Query("SELECT MAX(value) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun maxInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>
}

@Database(
    entities = [Record::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class HealthDatabase : RoomDatabase() {
    abstract fun healthDao(): HealthDao
}

class Converters {
    @TypeConverter
    fun fromTimestamp(value: Long?): Instant? = value?.let { Instant.ofEpochMilli(it) }

    @TypeConverter
    fun dateToTimestamp(date: Instant?): Long? = date?.toEpochMilli()

    @TypeConverter
    fun toTS(date: kotlin.time.Instant): Long = date.toEpochMilliseconds()

    @TypeConverter
    fun fromTS(timestamp: Long): kotlin.time.Instant = kotlin.time.Instant.fromEpochMilliseconds(timestamp)
}