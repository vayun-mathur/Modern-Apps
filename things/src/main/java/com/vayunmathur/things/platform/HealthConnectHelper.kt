package com.vayunmathur.things.platform

import android.content.Context
import com.vayunmathur.library.log.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.units.Volume

/** Health Connect bridge: permission set and hydration/scale record writes. */
object HealthConnectHelper {

    private val ALL_PERMISSIONS: Set<String> = buildSet {
        add(HealthPermission.getWritePermission(HydrationRecord::class))
        add(HealthPermission.getWritePermission(WeightRecord::class))
        add(HealthPermission.getWritePermission(BodyFatRecord::class))
        add(HealthPermission.getWritePermission(LeanBodyMassRecord::class))
        add(HealthPermission.getWritePermission(BoneMassRecord::class))
        add(HealthPermission.getWritePermission(BodyWaterMassRecord::class))
        add(HealthPermission.getWritePermission(BasalMetabolicRateRecord::class))
        // Read-back for permission wall gating is optional, but health does it; request read too
        // so the permission screen is consistent if the user already uses Health Connect.
        add(HealthPermission.getReadPermission(HydrationRecord::class))
        add(HealthPermission.getReadPermission(WeightRecord::class))
        add(HealthPermission.getReadPermission(BodyFatRecord::class))
    }

    val requiredPermissions: Set<String> get() = ALL_PERMISSIONS

    fun permissionsContract() = PermissionController.createRequestPermissionResultContract()

    suspend fun hasAllPermissions(client: HealthConnectClient): Boolean {
        return try {
            client.permissionController.getGrantedPermissions().containsAll(ALL_PERMISSIONS)
        } catch (_: Exception) {
            false
        }
    }

    fun availabilityStatus(context: Context): Int =
        HealthConnectClient.getSdkStatus(context)

    private fun zoneOffsetAt(instant: java.time.Instant) =
        java.time.ZoneId.systemDefault().rules.getOffset(instant)

// Broad catch is deliberate: Health Connect IPC can fail with provider-side
// RuntimeExceptions, and every failure mode is logged below.
@Suppress("TooGenericExceptionCaught")
    suspend fun writeHydration(
        client: HealthConnectClient,
        instant: java.time.Instant,
        volumeLiters: Double,
    ) {
        try {
            val record = HydrationRecord(
                startTime = instant,
                startZoneOffset = zoneOffsetAt(instant),
                endTime = instant,
                endZoneOffset = zoneOffsetAt(instant),
                volume = Volume.liters(volumeLiters),
                metadata = Metadata.manualEntry(),
            )
            client.insertRecords(listOf(record))
            Log.status("HealthConnectHelper", "Wrote HydrationRecord ${volumeLiters}L")
        } catch (e: Exception) {
            Log.error("HealthConnectHelper", "Failed to write HydrationRecord", e)
        }
    }

    private data class BodyCompositionValues(
        val weightKg: Double,
        val bodyFatPct: Double?,
        val leanMassKg: Double?,
        val boneMassKg: Double?,
        val bodyWaterMassKg: Double?,
        val bmrKcal: Int?,
        val clientRecordId: String?,
    )

    private fun buildBodyRecords(
        instant: java.time.Instant,
        off: java.time.ZoneOffset,
        values: BodyCompositionValues,
    ): List<androidx.health.connect.client.records.Record> {
        // Client record IDs are scoped per record type, so one ID per measurement is enough to
        // make a re-import of the same reading replace the previous rows rather than add to them.
        val metadata = {
            if (values.clientRecordId == null) Metadata.manualEntry()
            else Metadata.manualEntryWithId(values.clientRecordId)
        }
        val records = mutableListOf<androidx.health.connect.client.records.Record>()
        records.add(
            WeightRecord(
                time = instant,
                zoneOffset = off,
                weight = Mass.kilograms(values.weightKg),
                metadata = metadata(),
            )
        )
        addFatAndLeanRecords(records, instant, off, values, metadata)
        addWaterAndMetabolicRecords(records, instant, off, values, metadata)
        return records
    }

    private fun addFatAndLeanRecords(
        records: MutableList<androidx.health.connect.client.records.Record>,
        instant: java.time.Instant,
        off: java.time.ZoneOffset,
        values: BodyCompositionValues,
        metadata: () -> Metadata,
    ) {
        if (values.bodyFatPct != null && values.bodyFatPct > 0) {
            records.add(
                BodyFatRecord(
                    time = instant,
                    zoneOffset = off,
                    percentage = Percentage(values.bodyFatPct),
                    metadata = metadata(),
                )
            )
        }
        if (values.leanMassKg != null && values.leanMassKg > 0) {
            records.add(
                LeanBodyMassRecord(
                    time = instant,
                    zoneOffset = off,
                    mass = Mass.kilograms(values.leanMassKg),
                    metadata = metadata(),
                )
            )
        }
        if (values.boneMassKg != null && values.boneMassKg > 0) {
            records.add(
                BoneMassRecord(
                    time = instant,
                    zoneOffset = off,
                    mass = Mass.kilograms(values.boneMassKg),
                    metadata = metadata(),
                )
            )
        }
    }

    private fun addWaterAndMetabolicRecords(
        records: MutableList<androidx.health.connect.client.records.Record>,
        instant: java.time.Instant,
        off: java.time.ZoneOffset,
        values: BodyCompositionValues,
        metadata: () -> Metadata,
    ) {
        if (values.bodyWaterMassKg != null && values.bodyWaterMassKg > 0) {
            records.add(
                BodyWaterMassRecord(
                    time = instant,
                    zoneOffset = off,
                    mass = Mass.kilograms(values.bodyWaterMassKg),
                    metadata = metadata(),
                )
            )
        }
        if (values.bmrKcal != null && values.bmrKcal > 0) {
            records.add(
                BasalMetabolicRateRecord(
                    time = instant,
                    zoneOffset = off,
                    basalMetabolicRate = Power.kilocaloriesPerDay(values.bmrKcal.toDouble()),
                    metadata = metadata(),
                )
            )
        }
    }

    // Broad catch is deliberate: Health Connect IPC can fail with provider-side
    // RuntimeExceptions, and every failure mode is logged below.
    @Suppress("TooGenericExceptionCaught")
    suspend fun writeBodyComposition(
        client: HealthConnectClient,
        instant: java.time.Instant,
        weightKg: Double,
        bodyFatPct: Double?,
        leanMassKg: Double?,
        boneMassKg: Double?,
        bodyWaterMassKg: Double?,
        bmrKcal: Int?,
        clientRecordId: String? = null,
    ) {
        try {
            val off = zoneOffsetAt(instant)
            val values = BodyCompositionValues(
                weightKg = weightKg,
                bodyFatPct = bodyFatPct,
                leanMassKg = leanMassKg,
                boneMassKg = boneMassKg,
                bodyWaterMassKg = bodyWaterMassKg,
                bmrKcal = bmrKcal,
                clientRecordId = clientRecordId,
            )
            val records = buildBodyRecords(instant, off, values)
            client.insertRecords(records)
            Log.status("HealthConnectHelper", "Wrote ${records.size} body records")
        } catch (e: Exception) {
            Log.error("HealthConnectHelper", "Failed to write body composition", e)
        }
    }
}
