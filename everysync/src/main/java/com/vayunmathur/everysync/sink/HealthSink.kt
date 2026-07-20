package com.vayunmathur.everysync.sink

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
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
import java.time.Instant
import java.time.ZoneId

/**
 * Writes [RemoteMeasurement]s into Health Connect. Uses a stable `clientRecordId`
 * per measurement so re-running a sync upserts rather than duplicating. Mirrors the
 * record-construction patterns in `health`'s HealthAPI / HealthSyncWorker.
 */
object HealthSink {
    private const val TAG = "HealthSink"
    private val zone = ZoneId.systemDefault()

    suspend fun upsert(context: Context, measurements: List<RemoteMeasurement>) {
        if (measurements.isEmpty()) return
        val client = HealthConnectClient.getOrCreate(context)
        val records = measurements.mapNotNull { toRecord(it) }
        if (records.isEmpty()) return
        try {
            client.insertRecords(records)
        } catch (e: Exception) {
            Log.e(TAG, "insertRecords failed", e)
        }
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

            // --- Lifestyle (session) ---
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
        }
    }
}
