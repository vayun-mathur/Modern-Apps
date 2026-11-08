package com.vayunmathur.things.platform

import android.content.Context
import android.os.Looper
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class DeviceSessionsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun init() {
        DeviceController.init(context)
        // Reset the shared state this class mutates.
        DeviceController.scaleSex.value = Sex.Male
        DeviceController.scaleAge.value = "30"
        DeviceController.scaleHeight.value = "175"
        DeviceController.scaleAthlete.value = false
        DeviceController.scaleWeight.value = null
        DeviceController.scaleMetrics.value = null
        DeviceController.waterTempC.value = null
        DeviceController.tds.value = null
        DeviceController.batteryPct.value = null
        DeviceController.bottleVolumePct.value = null
        DeviceController.charging.value = false
        DeviceController.bottleLastUpdated.value = null
    }

    private fun withFakeHealth(fake: FakeHealthConnect, block: () -> Unit) {
        val oldStatus = healthStatusProvider
        val oldClient = healthClientProvider
        healthStatusProvider = { HealthConnectClient.SDK_AVAILABLE }
        healthClientProvider = { fake.client }
        try {
            block()
        } finally {
            healthStatusProvider = oldStatus
            healthClientProvider = oldClient
        }
    }

    private fun flushMain() {
        repeat(5) { shadowOf(Looper.getMainLooper()).idle() }
    }

    @Test
    fun `bottle status merges partial updates and persists telemetry`() {
        init()
        DeviceController.onBottleStatus(BottleStatus(22, null, null, false, null))
        assertEquals(22, DeviceController.waterTempC.value)
        assertNull(DeviceController.tds.value, "a field the packet did not carry must survive")
        DeviceController.onBottleStatus(BottleStatus(null, 45, 80, true, 65))
        assertEquals(22, DeviceController.waterTempC.value)
        assertEquals(45, DeviceController.tds.value)
        assertEquals(80, DeviceController.batteryPct.value)
        assertTrue(DeviceController.charging.value)
        assertEquals(65, DeviceController.bottleVolumePct.value)
        assertNotNull(DeviceController.bottleLastUpdated.value)
        // The persisted copy is what a relaunch reloads.
        assertEquals(22, DeviceController.devicePrefs.getInt(DeviceController.BOTTLE_TEMP_KEY, -1))
        assertEquals(45, DeviceController.devicePrefs.getInt(DeviceController.BOTTLE_TDS_KEY, -1))
        assertEquals(80, DeviceController.devicePrefs.getInt(DeviceController.BOTTLE_BATTERY_KEY, -1))
        assertEquals(65, DeviceController.devicePrefs.getInt(DeviceController.BOTTLE_VOLUME_KEY, -1))
        assertTrue(DeviceController.devicePrefs.getBoolean(DeviceController.BOTTLE_CHARGING_KEY, false))
    }

    @Test
    fun `scale realtime weight updates the weighing state`() {
        init()
        DeviceController.onScaleRealtimeWeight(75.25)
        assertEquals(75.25, DeviceController.scaleRealtimeWeight.value)
        assertTrue(DeviceController.scaleConnectionState.value.contains("75.2") ||
            DeviceController.scaleConnectionState.value.contains("75.3"),
            DeviceController.scaleConnectionState.value)
    }

    @Test
    fun `scale measurement computes metrics and writes body composition`() {
        init()
        val fake = FakeHealthConnect()
        withFakeHealth(fake) {
            DeviceController.onScaleMeasurement(75.0, 500, 300, measuredAtMillis = 1_700_000_000_000L)
            flushMain()
        }
        assertEquals(75.0, DeviceController.scaleWeight.value)
        assertEquals(500, DeviceController.scaleR50.value)
        assertEquals(300, DeviceController.scaleR500.value)
        assertNull(DeviceController.scaleRealtimeWeight.value, "the live reading clears on lock")
        assertNotNull(DeviceController.scaleMetrics.value)
        // Body composition was offered to Health Connect with no client record id (live).
        assertEquals(1, fake.inserted.size)
        val weights = fake.inserted.single().filterIsInstance<WeightRecord>()
        assertEquals(75.0, weights.single().weight.inKilograms, 1e-9)
        assertNull(weights.single().metadata.clientRecordId)
        // The profile was persisted.
        assertEquals(30, DeviceController.devicePrefs.getInt("scale_age", -1))
    }

    @Test
    fun `zero impedances become null but the measurement still lands`() {
        init()
        val fake = FakeHealthConnect()
        withFakeHealth(fake) {
            DeviceController.onScaleMeasurement(60.0, 0, 0, measuredAtMillis = 1_700_000_000_000L)
            flushMain()
        }
        assertEquals(60.0, DeviceController.scaleWeight.value)
        assertNull(DeviceController.scaleR50.value)
        assertNull(DeviceController.scaleR500.value)
        assertEquals(1, fake.inserted.size)
    }

    @Test
    fun `scale history writes with a dedupe record id and does not touch live state`() {
        init()
        DeviceController.scaleWeight.value = 99.0
        val fake = FakeHealthConnect()
        withFakeHealth(fake) {
            DeviceController.onScaleHistory(70.0, 500, 300, measuredAtMillis = 1_700_000_000_000L)
            flushMain()
        }
        assertEquals(99.0, DeviceController.scaleWeight.value, "history must not displace the live reading")
        val weights = fake.inserted.single().filterIsInstance<WeightRecord>()
        assertEquals(70.0, weights.single().weight.inKilograms, 1e-9)
    }

    @Test
    fun `drink log writes hydration with the skew clamp`() {
        init()
        val fake = FakeHealthConnect()
        withFakeHealth(fake) {
            // A future-dated reading (bottle clock ahead) is clamped to now; the record
            // still lands and carries the measured volume.
            val future = System.currentTimeMillis() + 3_600_000L
            DeviceController.onDrinkLog(HydrationReading(350, future, 45, 22))
            flushMain()
        }
        val hydration = fake.inserted.single().filterIsInstance<HydrationRecord>()
        assertEquals(1, hydration.size)
        assertEquals(0.35, hydration.single().volume.inLiters, 1e-9)
        assertTrue(hydration.single().startTime.toEpochMilli() <= System.currentTimeMillis())
    }

    @Test
    fun `writes are skipped when health connect is unavailable`() {
        init()
        // Default seam: the real provider status here is SDK_UNAVAILABLE, so the write
        // functions must return before touching any client.
        DeviceController.onDrinkLog(HydrationReading(200, System.currentTimeMillis(), null, null))
        DeviceController.onScaleHistory(70.0, 500, 300, System.currentTimeMillis())
        flushMain()
    }

    @Test
    fun `measurement without health permissions writes nothing`() {
        init()
        val fake = FakeHealthConnect(granted = emptySet())
        withFakeHealth(fake) {
            DeviceController.onScaleMeasurement(75.0, 500, 300, measuredAtMillis = 1_700_000_000_000L)
            flushMain()
        }
        assertTrue(fake.inserted.isEmpty())
        assertTrue(fake.permissionChecks > 0, "the permission gate must be consulted")
    }

    @Test
    fun `advertised traits round trip through prefs`() {
        init()
        DeviceController.saveScaleAdvertisedTraits(128, encryptsResistance = true)
        assertEquals(128, DeviceController.savedScaleCategory())
        assertTrue(DeviceController.savedScaleEncryptsResistance())
        DeviceController.saveScaleAdvertisedTraits(100, encryptsResistance = false)
        assertEquals(100, DeviceController.savedScaleCategory())
        assertFalse(DeviceController.savedScaleEncryptsResistance())
    }

    @Test
    fun `scale user key is generated once and stays stable`() {
        init()
        DeviceController.devicePrefs.edit().remove(DeviceController.SCALE_USER_KEY_KEY).commit()
        val first = DeviceController.scaleUserKey()
        assertTrue(first in 1..9999, "key=$first")
        assertEquals(first, DeviceController.scaleUserKey())
        assertEquals(first, DeviceController.devicePrefs.getInt(DeviceController.SCALE_USER_KEY_KEY, -1))
        // An out-of-range stored key is regenerated.
        DeviceController.devicePrefs.edit().putInt(DeviceController.SCALE_USER_KEY_KEY, 0).commit()
        val regenerated = DeviceController.scaleUserKey()
        assertTrue(regenerated in 1..9999)
    }

    @Test
    fun `user slot save clear and reset lifecycle`() {
        init()
        DeviceController.saveScaleUserIndex(3)
        assertEquals(3, DeviceController.scaleUserIndex())
        assertEquals(3, DeviceController.scaleUserSlot.value)
        DeviceController.requestScaleReset()
        assertTrue(DeviceController.scaleResetPending())
        assertNull(DeviceController.scaleUserSlot.value, "a pending reset drops our slot claim")
        DeviceController.onScaleResetDone()
        assertFalse(DeviceController.scaleResetPending())
        assertNull(DeviceController.scaleUserIndex())
    }

    @Test
    fun `profile reading clamps age and height and falls back on garbage`() {
        init()
        DeviceController.scaleAge.value = "200"
        DeviceController.scaleHeight.value = "999"
        var profile = DeviceController.readScaleProfile(fallbackAge = 44, fallbackHeightCm = 160.0)
        assertEquals(80, profile.age)
        assertEquals(240.0, profile.heightCm)
        DeviceController.scaleAge.value = "abc"
        DeviceController.scaleHeight.value = "xyz"
        profile = DeviceController.readScaleProfile(fallbackAge = 44, fallbackHeightCm = 160.0)
        assertEquals(44, profile.age)
        assertEquals(160.0, profile.heightCm)
        DeviceController.scaleAge.value = "1"
        DeviceController.scaleHeight.value = "10"
        profile = DeviceController.readScaleProfile(fallbackAge = 44, fallbackHeightCm = 160.0)
        assertEquals(3, profile.age)
        assertEquals(40.0, profile.heightCm)
    }

    @Test
    fun `recalc metrics only runs with a weight and persists the profile`() {
        init()
        DeviceController.recalcScaleMetrics()
        assertNull(DeviceController.scaleMetrics.value)
        DeviceController.scaleWeight.value = 75.0
        DeviceController.scaleR50.value = 500
        DeviceController.scaleR500.value = 300
        DeviceController.recalcScaleMetrics()
        assertNotNull(DeviceController.scaleMetrics.value)
        assertEquals("175.0", DeviceController.devicePrefs.getString("scale_height", null))
    }
}
