@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.health.util

import kotlin.uuid.Uuid
import android.content.Context
import android.content.SharedPreferences
import com.vayunmathur.library.log.Log
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
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.Record
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.library.util.Tuple3
import kotlin.reflect.KClass
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus

object HealthAPI {
    private var healthConnectClient: HealthConnectClient? = null
    lateinit var preferences: SharedPreferences

    private var repository: HealthRepository? = null

    /** Backward-compatible accessor — callers that read `HealthAPI.db` still compile. */
    val db get() = repository!!.database

    fun init(healthConnectClient: HealthConnectClient, context: Context, repository: HealthRepository) {
        this.healthConnectClient = healthConnectClient
        this.repository = repository
        preferences = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    }

    /**
     * Local-only mode for when Health Connect is missing or too old (API 33 and below without
     * the provider APK installed): Room and preferences work, the Health Connect mirror calls
     * below skip their remote half. Replaced by [init] once the provider is usable.
     */
    fun initLocal(context: Context, repository: HealthRepository) {
        this.healthConnectClient = null
        this.repository = repository
        preferences = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    }

    private fun repo(): HealthRepository = repository!!

    suspend fun lastRecord(recordType: RecordType): Record? {
        return repo().getLastRecord(recordType)
    }

    // Broad catch is deliberate: the delete is best-effort — the local row is already gone
    // and a failed Health Connect mirror must not crash the caller. The SDK throws
    // undocumented RuntimeExceptions (not just declared ones) when the service is missing.
    @Suppress("TooGenericExceptionCaught")
    suspend fun deleteRecord(record: Record) {
        // Delete from local Room DB
        repo().deleteByIds(listOf(record.id))

        // Local-only mode (Health Connect missing or too old): nothing remote to delete.
        val client = healthConnectClient ?: return
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
                client.deleteRecords(
                        recordType = recordClass,
                        recordIdsList = listOf(record.id),
                        clientRecordIdsList = listOf(record.id)
                )
            }
        } catch (e: Exception) {
            Log.error("HealthAPI", "Failed to delete record from Health Connect", e)
        }
    }

    /**
     * Health Connect's record constructors take a `java.time.ZoneOffset`, so that type is
     * fixed by the SDK; this just derives it from the system zone at [instant].
     */
    private fun systemOffsetAt(instant: java.time.Instant) =
        java.time.ZoneId.systemDefault().rules.getOffset(instant)

    // Broad catch is deliberate: a failed Health Connect mirror must not lose the local
    // record — the row is already saved by the caller and stays as the source of truth.
    // The SDK throws undocumented RuntimeExceptions, not just declared ones.
    @Suppress("TooGenericExceptionCaught")
    suspend fun writeHealthRecord(record: Record) {
        Log.dev("HealthAPI", "writeHealthRecord: type=${record.type}, metadata=${record.metadata}")
        val hcRecord: androidx.health.connect.client.records.Record = when (record.type) {
            RecordType.Nutrition -> nutritionRecord(record) ?: return
            RecordType.Hydration -> hydrationRecord(record)
            RecordType.Weight -> weightRecord(record)
            RecordType.Height -> heightRecord(record)
            RecordType.BodyFat -> bodyFatRecord(record)
            RecordType.LeanBodyMass -> leanBodyMassRecord(record)
            RecordType.BoneMass -> boneMassRecord(record)
            RecordType.BodyWaterMass -> bodyWaterMassRecord(record)
            else -> return
        }

        try {
            // Local-only mode (Health Connect missing or too old): the row just written to Room
            // above stays the source of truth and there is nothing to mirror into.
            val response = healthConnectClient?.insertRecords(listOf(hcRecord)) ?: return
            val newId = response.recordIdsList.firstOrNull()
            if (newId != null) {
                Log.status("HealthAPI", "Successfully wrote record to Health Connect with ID: $newId")
                // Remove old local record and replace with one containing HC ID.
                // deleteByIds matches the `id` column, not primaryKey ("$id-$index"),
                // so passing primaryKey deleted nothing and left a duplicate.
                repo().deleteByIds(listOf(record.id))
                val updatedRecord = record.copy(id = newId, primaryKey = "$newId-${record.index}")
                repo().upsert(listOf(updatedRecord))
            }
        } catch (e: Exception) {
            Log.error("HealthAPI", "Failed to write record to Health Connect", e)
        }
    }

    private fun nutritionRecord(record: Record): NutritionRecord? {
        val nd = record.nutritionData ?: return null
        val startInstant = record.startTime
        val endInstant = record.endTime
        return NutritionRecord(
            startTime = startInstant,
            startZoneOffset = systemOffsetAt(startInstant),
            endTime = endInstant,
            endZoneOffset = systemOffsetAt(endInstant),
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
            metadata = Metadata.manualEntry(clientRecordId = record.id),
        )
    }

    private fun hydrationRecord(record: Record): HydrationRecord {
        val startInstant = record.startTime
        val endInstant = record.endTime
        return HydrationRecord(
            startTime = startInstant,
            startZoneOffset = systemOffsetAt(startInstant),
            endTime = endInstant,
            endZoneOffset = systemOffsetAt(endInstant),
            volume = Volume.liters(record.value),
            metadata = Metadata.manualEntry(clientRecordId = record.id),
        )
    }

    private fun weightRecord(record: Record): WeightRecord = WeightRecord(
        time = record.startTime,
        zoneOffset = systemOffsetAt(record.startTime),
        weight = Mass.kilograms(record.value),
        metadata = Metadata.manualEntry(clientRecordId = record.id),
    )

    private fun heightRecord(record: Record): HeightRecord = HeightRecord(
        time = record.startTime,
        zoneOffset = systemOffsetAt(record.startTime),
        height = Length.meters(record.value),
        metadata = Metadata.manualEntry(clientRecordId = record.id),
    )

    private fun bodyFatRecord(record: Record): BodyFatRecord = BodyFatRecord(
        time = record.startTime,
        zoneOffset = systemOffsetAt(record.startTime),
        percentage = Percentage(record.value),
        metadata = Metadata.manualEntry(clientRecordId = record.id),
    )

    private fun leanBodyMassRecord(record: Record): LeanBodyMassRecord = LeanBodyMassRecord(
        time = record.startTime,
        zoneOffset = systemOffsetAt(record.startTime),
        mass = Mass.kilograms(record.value),
        metadata = Metadata.manualEntry(clientRecordId = record.id),
    )

    private fun boneMassRecord(record: Record): BoneMassRecord = BoneMassRecord(
        time = record.startTime,
        zoneOffset = systemOffsetAt(record.startTime),
        mass = Mass.kilograms(record.value),
        metadata = Metadata.manualEntry(clientRecordId = record.id),
    )

    private fun bodyWaterMassRecord(record: Record): BodyWaterMassRecord =
        BodyWaterMassRecord(
            time = record.startTime,
            zoneOffset = systemOffsetAt(record.startTime),
            mass = Mass.kilograms(record.value),
            metadata = Metadata.manualEntry(clientRecordId = record.id),
        )

    enum class PeriodType {
        Hourly,
        Daily,
        Weekly,
        Monthly
    }

    private const val DAYS_PER_WEEK = 7
    private const val HOURS_PER_DAY = 24

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
                date.minus((date.dayOfWeek.ordinal + 1) % DAYS_PER_WEEK, DateTimeUnit.DAY).toEpochDays()
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
                    Tuple3(
                        date.date.toEpochDays() * HOURS_PER_DAY + date.hour,
                        it.totalValue,
                        it.totalValue2,
                    )
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
        dailyQuery = { t, s, e -> repo().getDailyAvgs(t, s, e) },
        hourlyQuery = { t, s, e -> repo().getHourlyAvgs(t, s, e) },
    )

    suspend fun getListOfSums(
            recordType: RecordType,
            startTime: Instant,
            endTime: Instant,
            period: PeriodType
    ): List<Tuple3<Long, Double, Double>> = aggregateByPeriod(
        recordType, startTime, endTime, period,
        dailyQuery = { t, s, e -> repo().getDailySums(t, s, e) },
        hourlyQuery = { t, s, e -> repo().getHourlySums(t, s, e) },
    )
}
