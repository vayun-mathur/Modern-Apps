package com.vayunmathur.everysync.sink

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Temperature
import androidx.health.connect.client.units.Volume
import com.vayunmathur.everysync.model.MeasurementType
import com.vayunmathur.everysync.model.RemoteMeasurement
import com.vayunmathur.everysync.model.RemoteNutrition
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Writes [RemoteMeasurement]s into Health Connect. Uses a stable `clientRecordId`
 * per measurement so re-running a sync upserts rather than duplicating. Mirrors the
 * record-construction patterns in `health`'s HealthAPI / HealthSyncWorker.
 */
object HealthSink {
    private const val TAG = "HealthSink"
    private const val INSERT_BATCH = 1000
    private const val INSERT_CONCURRENCY = 4
    private const val HR_SAMPLES_PER_RECORD = 1000
    private const val HR_RECORDS_PER_INSERT = 50 // bounds samples-per-insert (~50k)
    private val zone = ZoneId.systemDefault()

    suspend fun upsert(context: Context, measurements: List<RemoteMeasurement>) {
        if (measurements.isEmpty()) return
        val client = HealthConnectClient.getOrCreate(context)
        // Heart rate is a *series* record: collapse the many per-second/minute
        // samples into a handful of multi-sample records instead of one record
        // each. Same resolution, but ~1000x fewer records to insert.
        val (heartRate, other) = measurements.partition { it.type == MeasurementType.HEART_RATE }
        val hrRecords = heartRateSeries(heartRate)
        Log.i(
            TAG,
            "upsert: ${measurements.size} measurements " +
                "(${heartRate.size} HR samples -> ${hrRecords.size} HR series); " +
                measurements.groupingBy { it.type }.eachCount(),
        )
        // Batch sizes: HR series records each carry up to HR_SAMPLES_PER_RECORD
        // samples, so a batch of them is far larger than a batch of scalar records.
        // Keep HR batches small so a single insert doesn't blow past Health
        // Connect's per-transaction limits (which silently fails the whole call).
        val scalar = other.mapNotNull { toRecord(it) }
        val batches = scalar.chunked(INSERT_BATCH) + hrRecords.chunked(HR_RECORDS_PER_INSERT)
        val semaphore = Semaphore(INSERT_CONCURRENCY)
        var inserted = 0
        coroutineScope {
            batches.map { batch ->
                async {
                    semaphore.withPermit {
                        try {
                            client.insertRecords(batch)
                            synchronized(this@HealthSink) { inserted += batch.size }
                        } catch (e: Exception) {
                            Log.e(TAG, "insertRecords failed (${batch.size} records)", e)
                        }
                    }
                }
            }.awaitAll()
        }
        Log.i(TAG, "upsert: inserted $inserted / ${scalar.size + hrRecords.size} records")
    }

    /**
     * Group heart-rate samples into series [HeartRateRecord]s (bucketed by civil
     * day, capped at [HR_SAMPLES_PER_RECORD] samples each). Deterministic
     * clientRecordIds keep re-syncing an overlapping window an idempotent upsert.
     */
    private fun heartRateSeries(measurements: List<RemoteMeasurement>): List<Record> {
        if (measurements.isEmpty()) return emptyList()
        val out = mutableListOf<Record>()
        measurements.groupBy { Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() }
            .forEach { (day, dayMeasurements) ->
                dayMeasurements.sortedBy { it.startMillis }
                    .chunked(HR_SAMPLES_PER_RECORD)
                    .forEachIndexed { bucket, chunk ->
                        val samples = chunk.map {
                            HeartRateRecord.Sample(Instant.ofEpochMilli(it.startMillis), it.value.toLong())
                        }
                        val startInst = Instant.ofEpochMilli(chunk.first().startMillis)
                        var endInst = Instant.ofEpochMilli(chunk.last().startMillis)
                        if (!endInst.isAfter(startInst)) endInst = startInst.plusMillis(1)
                        out += HeartRateRecord(
                            startTime = startInst,
                            startZoneOffset = zone.rules.getOffset(startInst),
                            endTime = endInst,
                            endZoneOffset = zone.rules.getOffset(endInst),
                            samples = samples,
                            metadata = Metadata.manualEntry(
                                clientRecordId = "googlehealth:HEART_RATE:$day:$bucket",
                            ),
                        )
                    }
            }
        return out
    }

    private fun toRecord(m: RemoteMeasurement): Record? {
        val start = Instant.ofEpochMilli(m.startMillis)
        val end = Instant.ofEpochMilli(m.endMillis)
        val startOffset = zone.rules.getOffset(start)
        val endOffset = zone.rules.getOffset(end)
        val meta = Metadata.manualEntry(clientRecordId = m.clientRecordId)
        return when (m.type) {
            // --- Body composition (instantaneous) ---
            MeasurementType.WEIGHT ->
                WeightRecord(time = start, zoneOffset = startOffset, weight = Mass.kilograms(m.value), metadata = meta)
            MeasurementType.HEIGHT ->
                HeightRecord(time = start, zoneOffset = startOffset, height = Length.meters(m.value), metadata = meta)
            MeasurementType.BODY_FAT ->
                BodyFatRecord(time = start, zoneOffset = startOffset, percentage = Percentage(m.value), metadata = meta)

            // --- Vitals (instantaneous) ---
            MeasurementType.OXYGEN_SATURATION ->
                OxygenSaturationRecord(time = start, zoneOffset = startOffset, percentage = Percentage(m.value), metadata = meta)
            MeasurementType.RESTING_HEART_RATE ->
                RestingHeartRateRecord(time = start, zoneOffset = startOffset, beatsPerMinute = m.value.toLong(), metadata = meta)
            MeasurementType.HEART_RATE_VARIABILITY ->
                HeartRateVariabilityRmssdRecord(time = start, zoneOffset = startOffset, heartRateVariabilityMillis = m.value, metadata = meta)
            MeasurementType.RESPIRATORY_RATE ->
                RespiratoryRateRecord(time = start, zoneOffset = startOffset, rate = m.value, metadata = meta)
            MeasurementType.BLOOD_GLUCOSE ->
                BloodGlucoseRecord(time = start, zoneOffset = startOffset, level = BloodGlucose.milligramsPerDeciliter(m.value), metadata = meta)
            MeasurementType.BODY_TEMPERATURE ->
                BodyTemperatureRecord(time = start, zoneOffset = startOffset, temperature = Temperature.celsius(m.value), metadata = meta)
            MeasurementType.VO2_MAX ->
                Vo2MaxRecord(time = start, zoneOffset = startOffset, vo2MillilitersPerMinuteKilogram = m.value, metadata = meta)
            MeasurementType.HEART_RATE -> HeartRateRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                samples = listOf(HeartRateRecord.Sample(start, m.value.toLong())),
                metadata = meta,
            )

            // --- Activity (intervals) ---
            MeasurementType.STEPS -> StepsRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                count = m.value.toLong().coerceAtLeast(1),
                metadata = meta,
            )
            MeasurementType.DISTANCE -> DistanceRecord(
                startTime = start, startZoneOffset = startOffset,
                endTime = end, endZoneOffset = endOffset,
                distance = Length.meters(m.value), metadata = meta,
            )
            MeasurementType.FLOORS -> FloorsClimbedRecord(
                startTime = start, startZoneOffset = startOffset,
                endTime = end, endZoneOffset = endOffset,
                floors = m.value, metadata = meta,
            )
            MeasurementType.ELEVATION -> ElevationGainedRecord(
                startTime = start, startZoneOffset = startOffset,
                endTime = end, endZoneOffset = endOffset,
                elevation = Length.meters(m.value), metadata = meta,
            )
            MeasurementType.ACTIVE_CALORIES -> ActiveCaloriesBurnedRecord(
                startTime = start, startZoneOffset = startOffset,
                endTime = end, endZoneOffset = endOffset,
                energy = Energy.kilocalories(m.value), metadata = meta,
            )
            MeasurementType.TOTAL_CALORIES -> TotalCaloriesBurnedRecord(
                startTime = start, startZoneOffset = startOffset,
                endTime = end, endZoneOffset = endOffset,
                energy = Energy.kilocalories(m.value), metadata = meta,
            )
            MeasurementType.HYDRATION -> HydrationRecord(
                startTime = start, startZoneOffset = startOffset,
                endTime = end, endZoneOffset = endOffset,
                volume = Volume.liters(m.value), metadata = meta,
            )

            // --- Sessions ---
            MeasurementType.SLEEP -> SleepSessionRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                stages = m.sleepStages.map {
                    SleepSessionRecord.Stage(
                        startTime = Instant.ofEpochMilli(it.startMillis),
                        endTime = Instant.ofEpochMilli(it.endMillis),
                        stage = it.stage,
                    )
                },
                metadata = meta,
            )
            MeasurementType.EXERCISE -> ExerciseSessionRecord(
                startTime = start,
                startZoneOffset = startOffset,
                endTime = end,
                endZoneOffset = endOffset,
                exerciseType = m.exerciseType,
                title = m.title,
                notes = m.notes,
                metadata = meta,
            )
            MeasurementType.NUTRITION -> nutritionRecord(m, start, startOffset, end, endOffset, meta)
        }
    }

    private fun nutritionRecord(
        m: RemoteMeasurement,
        start: Instant,
        startOffset: java.time.ZoneOffset,
        end: Instant,
        endOffset: java.time.ZoneOffset,
        meta: Metadata,
    ): NutritionRecord {
        val n: RemoteNutrition = m.nutrition ?: RemoteNutrition()
        fun g(key: String) = n.nutrientGrams[key]?.let { Mass.grams(it) }
        return NutritionRecord(
            startTime = start, startZoneOffset = startOffset,
            endTime = end, endZoneOffset = endOffset,
            name = m.title,
            energy = n.energyKcal?.let { Energy.kilocalories(it) },
            totalFat = g("TOTAL_FAT"),
            totalCarbohydrate = g("TOTAL_CARBOHYDRATE"),
            protein = g("PROTEIN"),
            dietaryFiber = g("DIETARY_FIBER"),
            sugar = g("SUGAR"),
            sodium = g("SODIUM"),
            biotin = g("BIOTIN"),
            caffeine = g("CAFFEINE"),
            calcium = g("CALCIUM"),
            chloride = g("CHLORIDE"),
            cholesterol = g("CHOLESTEROL"),
            chromium = g("CHROMIUM"),
            copper = g("COPPER"),
            folate = g("FOLATE"),
            folicAcid = g("FOLIC_ACID"),
            iodine = g("IODINE"),
            iron = g("IRON"),
            magnesium = g("MAGNESIUM"),
            manganese = g("MANGANESE"),
            molybdenum = g("MOLYBDENUM"),
            monounsaturatedFat = g("MONOUNSATURATED_FAT"),
            niacin = g("NIACIN"),
            pantothenicAcid = g("PANTOTHENIC_ACID"),
            phosphorus = g("PHOSPHORUS"),
            polyunsaturatedFat = g("POLYUNSATURATED_FAT"),
            potassium = g("POTASSIUM"),
            riboflavin = g("RIBOFLAVIN"),
            saturatedFat = g("SATURATED_FAT"),
            selenium = g("SELENIUM"),
            thiamin = g("THIAMIN"),
            transFat = g("TRANS_FAT"),
            unsaturatedFat = g("UNSATURATED_FAT"),
            vitaminA = g("VITAMIN_A"),
            vitaminB12 = g("VITAMIN_B12"),
            vitaminB6 = g("VITAMIN_B6"),
            vitaminC = g("VITAMIN_C"),
            vitaminD = g("VITAMIN_D"),
            vitaminE = g("VITAMIN_E"),
            vitaminK = g("VITAMIN_K"),
            zinc = g("ZINC"),
            metadata = meta,
        )
    }
}
