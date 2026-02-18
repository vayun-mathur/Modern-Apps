package com.vayunmathur.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.WheelchairPushesRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vayunmathur.health.database.HealthDatabase
import com.vayunmathur.health.database.Record
import com.vayunmathur.health.database.RecordType
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.buildDatabase
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Worker that ensures local Room DB is in sync with Health Connect.
 */
class HealthSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val healthConnectClient = HealthConnectClient.getOrCreate(applicationContext)
        val db = applicationContext.buildDatabase<HealthDatabase>()
        val ds = DataStoreUtils.getInstance(applicationContext)
        var token = ds.getString("hc_token")
        if (token == null) {
            // 2. If no token exists, initialize one for the data types you care about
            CLASSES.forEach {
                var pageToken: String? = null
                do {
                    val records = healthConnectClient.readRecords(ReadRecordsRequest(it, TimeRangeFilter.after(Instant.EPOCH), pageSize = 5000, pageToken = pageToken))
                    db.healthDao().upsert(records.records.map { it.toRecord() }.flatten())
                    pageToken = records.pageToken
                } while (pageToken != null)
                println("Completed inserting ${it.simpleName}")
            }
            token = healthConnectClient.getChangesToken(
                ChangesTokenRequest(
                    recordTypes = CLASSES
                )
            )
            ds.setString("hc_token", token)
        }
        do {
            val response = healthConnectClient.getChanges(token!!)

            // Handle new/updated records
            val upsertedRecords = response.changes.filterIsInstance<UpsertionChange>().map { it.record }

            val newRecords = upsertedRecords.map {
                it.toRecord()
            }.flatten()
            db.healthDao().upsert(newRecords)
            println("Upserted ${newRecords.size} records")

            // Handle deleted records
            val deletedIds = response.changes.filterIsInstance<DeletionChange>().map { it.recordId }
            db.healthDao().deleteByIds(deletedIds)

            // Update token for the next iteration/sync
            token = response.nextChangesToken
            ds.setString("hc_token", token)

            println("Deleted ${deletedIds.size} records")

        } while (response.hasMore)
        return Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<HealthSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            val syncRequestNow = OneTimeWorkRequestBuilder<HealthSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "HealthSyncWork",
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
            WorkManager.getInstance(context).enqueueUniqueWork("HealthSyncWork_now", ExistingWorkPolicy.KEEP, syncRequestNow)
        }
    }
}

fun androidx.health.connect.client.records.Record.toRecord(): List<Record> =
    when (this) {
        // --- Activity ---
        is StepsRecord -> listOf(Record(this.metadata.id, 0, RecordType.Steps, this.startTime, this.endTime, this.count.toDouble()))
        is WheelchairPushesRecord -> listOf(Record(this.metadata.id, 0, RecordType.Wheelchair, this.startTime, this.endTime, this.count.toDouble()))
        is DistanceRecord -> listOf(Record(this.metadata.id, 0, RecordType.Distance, this.startTime, this.endTime, this.distance.inKilometers))
        is TotalCaloriesBurnedRecord -> listOf(Record(this.metadata.id, 0, RecordType.CaloriesTotal, this.startTime, this.endTime, this.energy.inKilocalories))
        is ActiveCaloriesBurnedRecord -> listOf(Record(this.metadata.id, 0, RecordType.CaloriesActive, this.startTime, this.endTime, this.energy.inKilocalories))
        is BasalMetabolicRateRecord -> listOf(Record(this.metadata.id, 0, RecordType.CaloriesBasal, this.time, this.time, this.basalMetabolicRate.inKilocaloriesPerDay))
        is FloorsClimbedRecord -> listOf(Record(this.metadata.id, 0, RecordType.Floors, this.startTime, this.endTime, this.floors))
        is ElevationGainedRecord -> listOf(Record(this.metadata.id, 0, RecordType.Elevation, this.startTime, this.endTime, this.elevation.inMeters))

        // --- Vitals ---
        is HeartRateRecord -> this.samples.mapIndexed { idx, sample -> Record(this.metadata.id, idx, RecordType.HeartRate, sample.time, sample.time, sample.beatsPerMinute.toDouble()) }
        is RestingHeartRateRecord -> listOf(Record(this.metadata.id, 0, RecordType.RestingHeartRate, this.time, this.time, this.beatsPerMinute.toDouble()))
        is HeartRateVariabilityRmssdRecord -> listOf(Record(this.metadata.id, 0, RecordType.HeartRateVariabilityRmssd, this.time, this.time, this.heartRateVariabilityMillis))
        is RespiratoryRateRecord -> listOf(Record(this.metadata.id, 0, RecordType.RespiratoryRate, this.time, this.time, this.rate))
        is OxygenSaturationRecord -> listOf(Record(this.metadata.id, 0, RecordType.OxygenSaturation, this.time, this.time, this.percentage.value))
        is BloodPressureRecord -> listOf(Record(this.metadata.id, 0, RecordType.BloodPressure, this.time, this.time, this.systolic.inMillimetersOfMercury, this.diastolic.inMillimetersOfMercury))
        is BloodGlucoseRecord -> listOf(Record(this.metadata.id, 0, RecordType.BloodGlucose, this.time, this.time, this.level.inMilligramsPerDeciliter))
        is Vo2MaxRecord -> listOf(Record(this.metadata.id, 0, RecordType.Vo2Max, this.time, this.time, this.vo2MillilitersPerMinuteKilogram))
        is SkinTemperatureRecord -> this.deltas.mapIndexed { idx, delta -> Record(this.metadata.id, idx, RecordType.SkinTemperature, delta.time, delta.time, delta.delta.inCelsius) }

        // --- Body Composthision ---
        is WeightRecord -> listOf(Record(this.metadata.id, 0, RecordType.Weight, this.time, this.time, this.weight.inKilograms))
        is HeightRecord -> listOf(Record(this.metadata.id, 0, RecordType.Height, this.time, this.time, this.height.inMeters))
        is BodyFatRecord -> listOf(Record(this.metadata.id, 0, RecordType.BodyFat, this.time, this.time, this.percentage.value))
        is LeanBodyMassRecord -> listOf(Record(this.metadata.id, 0, RecordType.LeanBodyMass, this.time, this.time, this.mass.inKilograms))
        is BoneMassRecord -> listOf(Record(this.metadata.id, 0, RecordType.BoneMass, this.time, this.time, this.mass.inKilograms))
        is BodyWaterMassRecord -> listOf(Record(this.metadata.id, 0, RecordType.BodyWaterMass, this.time, this.time, this.mass.inKilograms))

        // --- Lifestyle ---
//                    is SleepSessionRecord -> listOf(Record(this.metadata.id, 0, "Sleep", this.startTime, this.endTime, this))
        is MindfulnessSessionRecord -> listOf(Record(this.metadata.id, 0, RecordType.Mindfulness, this.startTime, this.endTime, Duration.between(this.startTime, this.endTime).toMillis().toDouble() / 1000.0 / 60.0))
        is HydrationRecord -> listOf(Record(this.metadata.id, 0, RecordType.Hydration, this.startTime, this.endTime, this.volume.inMilliliters))
//                    is NutritionRecord -> listOf(Record(it.metadata.id, 0, "Nutrition", it.startTime, it.endTime, it))

        else -> throw IllegalArgumentException("Unsupported record type")
    }