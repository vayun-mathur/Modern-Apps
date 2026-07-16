package com.vayunmathur.health.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Volume
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.Record
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.library.util.Tuple3
import java.time.ZoneOffset
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus

object HealthAPI {
    lateinit var healthConnectClient: HealthConnectClient
    lateinit var db: HealthDatabase
    lateinit var preferences: SharedPreferences

    fun init(healthConnectClient: HealthConnectClient, context: Context, db: HealthDatabase) {
        this.healthConnectClient = healthConnectClient
        this.db = db
        preferences = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    }

    suspend inline fun lastRecord(recordType: RecordType): Record? {
        return db.healthDao().getLastRecord(recordType)
    }

    suspend fun deleteRecord(record: Record) {
        // Delete from local Room DB
        db.healthDao().deleteByIds(listOf(record.id))

        // Delete from Health Connect if it has a valid ID
        try {
            val recordClass: KClass<out androidx.health.connect.client.records.Record>? = when (record.type) {
                RecordType.Nutrition -> NutritionRecord::class
                RecordType.Hydration -> HydrationRecord::class
                RecordType.Weight -> WeightRecord::class
                RecordType.Height -> HeightRecord::class
                RecordType.BodyFat -> BodyFatRecord::class
                RecordType.LeanBodyMass -> LeanBodyMassRecord::class
                RecordType.BoneMass -> BoneMassRecord::class
                RecordType.BodyWaterMass -> BodyWaterMassRecord::class
                else -> null
            }
            if (recordClass != null) {
                healthConnectClient.deleteRecords(
                        recordType = recordClass,
                        recordIdsList = listOf(record.id),
                        clientRecordIdsList = listOf(record.id)
                )
            }
        } catch (e: Exception) {
            Log.e("HealthAPI", "Failed to delete record from Health Connect", e)
        }
    }

    suspend fun writeHealthRecord(record: Record) {
        Log.d("HealthAPI", "writeHealthRecord: type=${record.type}, metadata=${record.metadata}")
        val startInstant = record.startTime
        val endInstant = record.endTime

        val hcRecord: androidx.health.connect.client.records.Record =
                when (record.type) {
                    RecordType.Nutrition -> {
                        val nd = record.nutritionData ?: return
                        NutritionRecord(
                                startTime = startInstant,
                                startZoneOffset =
                                        ZoneOffset.systemDefault().rules.getOffset(startInstant),
                                endTime = endInstant,
                                endZoneOffset =
                                        ZoneOffset.systemDefault().rules.getOffset(endInstant),
                                name = record.metadata,
                                energy = Energy.kilocalories(nd.calories),
                                protein = Mass.grams(nd.protein),
                                totalCarbohydrate = Mass.grams(nd.carbohydrates),
                                totalFat = Mass.grams(nd.fat),
                                dietaryFiber = Mass.grams(nd.fiber),
                                sugar = Mass.grams(nd.sugar),
                                sodium = Mass.milligrams(nd.sodium),
                                biotin = Mass.micrograms(nd.biotin),
                                caffeine = Mass.milligrams(nd.caffeine),
                                calcium = Mass.milligrams(nd.calcium),
                                chloride = Mass.milligrams(nd.chloride),
                                cholesterol = Mass.milligrams(nd.cholesterol),
                                chromium = Mass.micrograms(nd.chromium),
                                copper = Mass.milligrams(nd.copper),
                                folate = Mass.micrograms(nd.folate),
                                folicAcid = Mass.micrograms(nd.folicAcid),
                                iodine = Mass.micrograms(nd.iodine),
                                iron = Mass.milligrams(nd.iron),
                                magnesium = Mass.milligrams(nd.magnesium),
                                manganese = Mass.milligrams(nd.manganese),
                                molybdenum = Mass.micrograms(nd.molybdenum),
                                monounsaturatedFat = Mass.grams(nd.monounsaturatedFat),
                                niacin = Mass.milligrams(nd.niacin),
                                pantothenicAcid = Mass.milligrams(nd.pantothenicAcid),
                                phosphorus = Mass.milligrams(nd.phosphorus),
                                polyunsaturatedFat = Mass.grams(nd.polyunsaturatedFat),
                                potassium = Mass.milligrams(nd.potassium),
                                riboflavin = Mass.milligrams(nd.riboflavin),
                                saturatedFat = Mass.grams(nd.saturatedFat),
                                selenium = Mass.micrograms(nd.selenium),
                                thiamin = Mass.milligrams(nd.thiamin),
                                transFat = Mass.grams(nd.transFat),
                                unsaturatedFat = Mass.grams(nd.unsaturatedFat),
                                vitaminA = Mass.micrograms(nd.vitaminA),
                                vitaminB12 = Mass.micrograms(nd.vitaminB12),
                                vitaminB6 = Mass.milligrams(nd.vitaminB6),
                                vitaminC = Mass.milligrams(nd.vitaminC),
                                vitaminD = Mass.micrograms(nd.vitaminD),
                                vitaminE = Mass.milligrams(nd.vitaminE),
                                vitaminK = Mass.micrograms(nd.vitaminK),
                                zinc = Mass.milligrams(nd.zinc),
                                metadata = Metadata.manualEntry(clientRecordId = record.id)
                        )
                    }
                    RecordType.Hydration -> {
                        HydrationRecord(
                                startTime = startInstant,
                                startZoneOffset =
                                        ZoneOffset.systemDefault().rules.getOffset(startInstant),
                                endTime = endInstant,
                                endZoneOffset =
                                        ZoneOffset.systemDefault().rules.getOffset(endInstant),
                                volume = Volume.liters(record.value),
                                metadata = Metadata.manualEntry(clientRecordId = record.id)
                        )
                    }
                    RecordType.Weight -> WeightRecord(
                            time = startInstant,
                            zoneOffset = ZoneOffset.systemDefault().rules.getOffset(startInstant),
                            weight = Mass.kilograms(record.value),
                            metadata = Metadata.manualEntry(clientRecordId = record.id)
                    )
                    RecordType.Height -> HeightRecord(
                            time = startInstant,
                            zoneOffset = ZoneOffset.systemDefault().rules.getOffset(startInstant),
                            height = Length.meters(record.value),
                            metadata = Metadata.manualEntry(clientRecordId = record.id)
                    )
                    RecordType.BodyFat -> BodyFatRecord(
                            time = startInstant,
                            zoneOffset = ZoneOffset.systemDefault().rules.getOffset(startInstant),
                            percentage = Percentage(record.value),
                            metadata = Metadata.manualEntry(clientRecordId = record.id)
                    )
                    RecordType.LeanBodyMass -> LeanBodyMassRecord(
                            time = startInstant,
                            zoneOffset = ZoneOffset.systemDefault().rules.getOffset(startInstant),
                            mass = Mass.kilograms(record.value),
                            metadata = Metadata.manualEntry(clientRecordId = record.id)
                    )
                    RecordType.BoneMass -> BoneMassRecord(
                            time = startInstant,
                            zoneOffset = ZoneOffset.systemDefault().rules.getOffset(startInstant),
                            mass = Mass.kilograms(record.value),
                            metadata = Metadata.manualEntry(clientRecordId = record.id)
                    )
                    RecordType.BodyWaterMass -> BodyWaterMassRecord(
                            time = startInstant,
                            zoneOffset = ZoneOffset.systemDefault().rules.getOffset(startInstant),
                            mass = Mass.kilograms(record.value),
                            metadata = Metadata.manualEntry(clientRecordId = record.id)
                    )
                    else -> return
                }

        try {
            val response = healthConnectClient.insertRecords(listOf(hcRecord))
            val newId = response.recordIdsList.firstOrNull()
            if (newId != null) {
                Log.i("HealthAPI", "Successfully wrote record to Health Connect with ID: $newId")
                // Remove old local record and replace with one containing HC ID
                db.healthDao().deleteByIds(listOf(record.primaryKey))
                val updatedRecord = record.copy(id = newId, primaryKey = "$newId-${record.index}")
                db.healthDao().upsert(listOf(updatedRecord))
            }
        } catch (e: Exception) {
            Log.e("HealthAPI", "Failed to write record to Health Connect", e)
        }
    }

    enum class PeriodType {
        Hourly,
        Daily,
        Weekly,
        Monthly
    }

    private val hourlyFormat =
            LocalDateTime.Format {
                year()
                chars("-")
                monthNumber()
                chars("-")
                day()
                chars(" ")
                hour()
                chars(":")
                minute()
            }

    private suspend fun aggregateByPeriod(
        recordType: RecordType,
        startTime: Instant,
        endTime: Instant,
        period: PeriodType,
        dailyQuery: suspend (RecordType, Instant, Instant) -> List<com.vayunmathur.health.data.HealthDao.DailySum>,
        hourlyQuery: suspend (RecordType, Long, Long) -> List<com.vayunmathur.health.data.HealthDao.HourlySum>,
    ): List<Tuple3<Long, Double, Double>> = when (period) {
        PeriodType.Daily -> {
            dailyQuery(recordType, startTime, endTime).sortedBy { it.day }.map {
                Tuple3(LocalDate.parse(it.day).toEpochDays(), it.totalValue, it.totalValue2)
            }
        }
        PeriodType.Weekly -> {
            dailyQuery(recordType, startTime, endTime).sortedBy { it.day }.groupBy {
                val date = LocalDate.parse(it.day)
                date.minus((date.dayOfWeek.ordinal + 1) % 7, DateTimeUnit.DAY).toEpochDays()
            }.map { (key, values) ->
                Tuple3(key, values.map { it.totalValue }.average(), values.map { it.totalValue2 }.average())
            }
        }
        PeriodType.Monthly -> {
            dailyQuery(recordType, startTime, endTime).sortedBy { it.day }.groupBy {
                val date = LocalDate.parse(it.day)
                date.minus(date.day - 1, DateTimeUnit.DAY).toEpochDays()
            }.map { (key, values) ->
                Tuple3(key, values.map { it.totalValue }.average(), values.map { it.totalValue2 }.average())
            }
        }
        PeriodType.Hourly -> {
            hourlyQuery(recordType, startTime.toEpochMilliseconds(), endTime.toEpochMilliseconds())
                .sortedBy { it.hourBlock }.map {
                    val date = hourlyFormat.parse(it.hourBlock)
                    Tuple3(date.date.toEpochDays() * 24 + date.hour, it.totalValue, it.totalValue2)
                }
        }
    }

    suspend fun getListOfAverages(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant,
            period: PeriodType
    ): List<Tuple3<Long, Double, Double>> = aggregateByPeriod(
        recordType, startTime, endTime, period,
        dailyQuery = { t, s, e -> db.healthDao().getDailyAvgs(t, s, e) },
        hourlyQuery = { t, s, e -> db.healthDao().getHourlyAvgs(t, s, e) },
    )

    suspend fun getListOfSums(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant,
            period: PeriodType
    ): List<Tuple3<Long, Double, Double>> = aggregateByPeriod(
        recordType, startTime, endTime, period,
        dailyQuery = { t, s, e -> db.healthDao().getDailySums(t, s, e) },
        hourlyQuery = { t, s, e -> db.healthDao().getHourlySums(t, s, e) },
    )
}
