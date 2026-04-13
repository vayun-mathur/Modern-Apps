package com.vayunmathur.health.data
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow
import java.time.Instant

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

    @Query("SELECT COALESCE(SUM(value), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun sumInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>
    @Query("SELECT COALESCE(SUM(value), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    suspend fun sumInRangeGet1(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Double
    @Query("SELECT COALESCE(SUM(secondaryValue), 0.0) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    suspend fun sumInRangeGet2(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Double
    @Query("SELECT AVG(value) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    suspend fun avgInRangeGet1(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Double?
    @Query("SELECT AVG(secondaryValue) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    suspend fun avgInRangeGet2(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Double?
    @Query("SELECT MIN(value) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun minInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double?>
    @Query("SELECT MAX(value) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun maxInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double?>
    @Query("SELECT * FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun getAllInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<List<Record>>

    @Query("""
    SELECT 
        date(startTime / 1000, 'unixepoch', 'localtime') as day, 
        SUM(value) as totalValue,
        SUM(secondaryValue) as totalValue2 
    FROM Record 
    WHERE type = :type 
      AND startTime >= :startTime 
      AND endTime <= :endTime
    GROUP BY day
    ORDER BY day ASC
""")
    suspend fun getDailySums(
        type: RecordType,
        startTime: kotlin.time.Instant,
        endTime: kotlin.time.Instant
    ): List<DailySum>

    @Query("""
    SELECT 
        strftime('%Y-%m-%d %H:00', startTime / 1000, 'unixepoch', 'localtime') AS hourBlock, 
        SUM(value) AS totalValue,
        SUM(secondaryValue) AS totalValue2
    FROM Record 
    WHERE type = :type 
      AND startTime >= :startTime 
      AND endTime <= :endTime
    GROUP BY hourBlock
    ORDER BY hourBlock ASC
""")
    suspend fun getHourlySums(
        type: RecordType,
        startTime: Long,
        endTime: Long
    ): List<HourlySum>

    @Query("""
    SELECT 
        date(startTime / 1000, 'unixepoch', 'localtime') as day, 
        AVG(value) as totalValue,
        AVG(secondaryValue) as totalValue2 
    FROM Record 
    WHERE type = :type 
      AND startTime >= :startTime 
      AND endTime <= :endTime
    GROUP BY day
    ORDER BY day ASC
""")
    suspend fun getDailyAvgs(
        type: RecordType,
        startTime: kotlin.time.Instant,
        endTime: kotlin.time.Instant
    ): List<DailySum>

    @Query("""
    SELECT 
        strftime('%Y-%m-%d %H:00', startTime / 1000, 'unixepoch', 'localtime') AS hourBlock, 
        AVG(value) AS totalValue,
        AVG(secondaryValue) AS totalValue2
    FROM Record 
    WHERE type = :type 
      AND startTime >= :startTime 
      AND endTime <= :endTime
    GROUP BY hourBlock
    ORDER BY hourBlock ASC
""")
    suspend fun getHourlyAvgs(
        type: RecordType,
        startTime: Long,
        endTime: Long
    ): List<HourlySum>

    // Helper data class to catch the results
    data class DailySum(
        val day: String, // Format: YYYY-MM-DD
        val totalValue: Double,
        val totalValue2: Double
    )
    data class HourlySum(
        val hourBlock: String, // Format: 2026-03-03 15:00
        val totalValue: Double,
        val totalValue2: Double
    )
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