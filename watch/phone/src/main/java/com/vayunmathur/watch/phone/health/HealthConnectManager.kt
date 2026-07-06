package com.vayunmathur.watch.phone.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import com.vayunmathur.watch.phone.data.WatchRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Wraps Health Connect onboarding, permissions, and inserting the watch's
 * records. Heart rate and steps are written per-sample; the directly-measured
 * daily totals (distance, floors, elevation, calories) are written day-keyed so
 * growing totals upsert instead of duplicating.
 */
class HealthConnectManager(private val context: Context) {

    val permissions: Set<String> = setOf(
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(FloorsClimbedRecord::class),
        HealthPermission.getWritePermission(ElevationGainedRecord::class),
        HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getWritePermission(RestingHeartRateRecord::class),
        HealthPermission.getWritePermission(SleepSessionRecord::class),
    )

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    fun isAvailable(): Boolean = sdkStatus() == HealthConnectClient.SDK_AVAILABLE

    fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    suspend fun hasAllPermissions(): Boolean =
        client().permissionController.getGrantedPermissions().containsAll(permissions)

    suspend fun insert(records: List<WatchRecord>) {
        if (records.isEmpty()) return
        val zone = ZoneId.systemDefault()
        val hcRecords = records.mapNotNull { record ->
            val time = Instant.ofEpochMilli(record.timestamp)
            val offset = zone.rules.getOffset(time)
            when (record.type) {
                "HeartRate" -> HeartRateRecord(
                    startTime = time,
                    startZoneOffset = offset,
                    endTime = time,
                    endZoneOffset = offset,
                    samples = listOf(
                        HeartRateRecord.Sample(
                            time = time,
                            beatsPerMinute = record.value.toLong(),
                        ),
                    ),
                    metadata = Metadata.manualEntry(clientRecordId = "hr-${record.id}"),
                )
                "Steps" -> {
                    val count = record.delta.toLong()
                    if (count <= 0L) return@mapNotNull null
                    val start = time.minusSeconds(STEP_WINDOW_SECONDS)
                    StepsRecord(
                        startTime = start,
                        startZoneOffset = zone.rules.getOffset(start),
                        endTime = time,
                        endZoneOffset = offset,
                        count = count,
                        metadata = Metadata.manualEntry(clientRecordId = "steps-${record.id}"),
                    )
                }
                "Distance" -> dailyRecord(record, time, zone) { start, day ->
                    DistanceRecord(
                        startTime = start,
                        startZoneOffset = zone.rules.getOffset(start),
                        endTime = time,
                        endZoneOffset = offset,
                        distance = Length.meters(record.value),
                        metadata = Metadata.manualEntry(clientRecordId = "distance-$day"),
                    )
                }
                "Floors" -> dailyRecord(record, time, zone) { start, day ->
                    FloorsClimbedRecord(
                        startTime = start,
                        startZoneOffset = zone.rules.getOffset(start),
                        endTime = time,
                        endZoneOffset = offset,
                        floors = record.value,
                        metadata = Metadata.manualEntry(clientRecordId = "floors-$day"),
                    )
                }
                "Elevation" -> dailyRecord(record, time, zone) { start, day ->
                    ElevationGainedRecord(
                        startTime = start,
                        startZoneOffset = zone.rules.getOffset(start),
                        endTime = time,
                        endZoneOffset = offset,
                        elevation = Length.meters(record.value),
                        metadata = Metadata.manualEntry(clientRecordId = "elevation-$day"),
                    )
                }
                "Calories" -> dailyRecord(record, time, zone) { start, day ->
                    TotalCaloriesBurnedRecord(
                        startTime = start,
                        startZoneOffset = zone.rules.getOffset(start),
                        endTime = time,
                        endZoneOffset = offset,
                        energy = Energy.kilocalories(record.value),
                        metadata = Metadata.manualEntry(clientRecordId = "calories-$day"),
                    )
                }
                else -> null
            }
        }
        if (hcRecords.isEmpty()) return
        try {
            client().insertRecords(hcRecords)
        } catch (e: Exception) {
            Log.e(TAG, "insertRecords failed", e)
        }
    }

    // Daily totals span start-of-day -> record time; the day-keyed clientRecordId
    // makes a growing total upsert rather than duplicate. Skip non-positive totals.
    private inline fun dailyRecord(
        record: WatchRecord,
        time: Instant,
        zone: ZoneId,
        build: (start: Instant, day: LocalDate) -> Record,
    ): Record? {
        if (record.value <= 0.0) return null
        val day = time.atZone(zone).toLocalDate()
        var start = day.atStartOfDay(zone).toInstant()
        if (!time.isAfter(start)) start = time.minusSeconds(1)
        return build(start, day)
    }

    /** Batch-inserts derivation output; clientRecordIds make this idempotent. */
    suspend fun insertDerived(records: List<Record>) {
        if (records.isEmpty()) return
        try {
            client().insertRecords(records)
        } catch (e: Exception) {
            Log.e(TAG, "insertDerived failed", e)
        }
    }

    companion object {
        private const val TAG = "HealthConnectManager"
        private const val STEP_WINDOW_SECONDS = 60L
    }
}
