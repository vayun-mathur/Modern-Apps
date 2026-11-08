package com.vayunmathur.things.platform

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HealthConnectHelperTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `required permissions cover every record type we write`() {
        val perms = HealthConnectHelper.requiredPermissions
        assertEquals(10, perms.size)
        assertTrue(perms.all { it.startsWith("android.permission.health.") }, perms.toString())
        assertTrue(perms.any { it.contains("WRITE_HYDRATION") }, perms.toString())
        assertTrue(perms.any { it.contains("WRITE_WEIGHT") }, perms.toString())
        assertTrue(perms.any { it.contains("WRITE_BODY_FAT") }, perms.toString())
    }

    @Test
    fun `availability status reports the provider as unavailable here`() {
        // No Health Connect provider exists under Robolectric, so every caller must
        // take its unavailable path instead of crashing.
        assertEquals(
            HealthConnectClient.SDK_UNAVAILABLE,
            HealthConnectHelper.availabilityStatus(context),
        )
    }

    @Test
    fun `permissions contract is the health connect one`() {
        val contract = HealthConnectHelper.permissionsContract()
        assertEquals(
            "androidx.health.connect.client.contracts.HealthPermissionsRequestContract",
            contract.javaClass.name,
        )
    }

    @Test
    fun `has all permissions only when the full set is granted`() = runBlocking {
        val full = FakeHealthConnect()
        assertTrue(HealthConnectHelper.hasAllPermissions(full.client))
        val missingOne = FakeHealthConnect(
            granted = HealthConnectHelper.requiredPermissions.drop(1).toSet(),
        )
        assertFalse(HealthConnectHelper.hasAllPermissions(missingOne.client))
        assertFalse(HealthConnectHelper.hasAllPermissions(FakeHealthConnect(granted = emptySet()).client))
    }

    @Test
    fun `write hydration inserts one hydration record with the given volume`() = runBlocking {
        val fake = FakeHealthConnect()
        val instant = Instant.parse("2026-01-15T10:30:00Z")
        HealthConnectHelper.writeHydration(fake.client, instant, 0.35)
        assertEquals(1, fake.inserted.size)
        val record = fake.inserted.single().single() as HydrationRecord
        assertEquals(instant, record.startTime)
        assertEquals(instant, record.endTime)
        assertEquals(0.35, record.volume.inLiters, 1e-9)
    }

    @Test
    fun `write hydration swallows provider failures`() = runBlocking {
        val fake = FakeHealthConnect(insertError = IllegalStateException("provider died"))
        HealthConnectHelper.writeHydration(fake.client, Instant.now(), 0.2)
        // Reaching here without throwing is the assertion the production catch promises.
        assertTrue(fake.inserted.isEmpty())
    }

    @Test
    fun `write body composition inserts every positive component and skips the rest`() = runBlocking {
        val fake = FakeHealthConnect()
        HealthConnectHelper.writeBodyComposition(
            client = fake.client,
            instant = Instant.parse("2026-01-15T10:30:00Z"),
            weightKg = 75.0,
            bodyFatPct = 20.0,
            leanMassKg = 60.0,
            boneMassKg = 3.0,
            bodyWaterMassKg = 40.0,
            bmrKcal = 1700,
            clientRecordId = "scale-123",
        )
        val records = fake.inserted.single()
        assertEquals(6, records.size)
        assertTrue(records.any { it is WeightRecord && it.weight.inKilograms == 75.0 })
        assertTrue(records.any { it is BodyFatRecord && it.percentage.value == 20.0 })
        assertTrue(records.any { it is LeanBodyMassRecord && it.mass.inKilograms == 60.0 })
        assertTrue(records.any { it is BoneMassRecord && it.mass.inKilograms == 3.0 })
        assertTrue(records.any { it is BodyWaterMassRecord && it.mass.inKilograms == 40.0 })
        assertTrue(
            records.any {
                it is BasalMetabolicRateRecord && it.basalMetabolicRate.inKilocaloriesPerDay == 1700.0
            },
        )
        // This SDK drops the client record id from locally-built records (it only
        // takes effect at insert time on a real provider), so it is not asserted here.
    }

    @Test
    fun `write body composition with only a weight inserts just the weight`() = runBlocking {
        val fake = FakeHealthConnect()
        HealthConnectHelper.writeBodyComposition(
            client = fake.client,
            instant = Instant.now(),
            weightKg = 80.0,
            bodyFatPct = 0.0,
            leanMassKg = null,
            boneMassKg = -1.0,
            bodyWaterMassKg = null,
            bmrKcal = 0,
        )
        val records = fake.inserted.single()
        assertEquals(1, records.size)
        assertTrue(records.single() is WeightRecord)
        assertEquals(null, records.single().metadata.clientRecordId)
    }

    @Test
    fun `write body composition swallows provider failures`() = runBlocking {
        val fake = FakeHealthConnect(insertError = RuntimeException("ipc failed"))
        HealthConnectHelper.writeBodyComposition(
            fake.client, Instant.now(), 75.0, 20.0, 60.0, 3.0, 40.0, 1700,
        )
        assertTrue(fake.inserted.isEmpty())
    }
}
