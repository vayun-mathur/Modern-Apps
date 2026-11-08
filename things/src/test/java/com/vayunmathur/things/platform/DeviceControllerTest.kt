package com.vayunmathur.things.platform

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Actions on an already-initialised [DeviceController]. Prefs assertions go through
 * `DeviceController.devicePrefs` — the exact store the controller reads — because the
 * singleton binds its context once per process.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceControllerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs: SharedPreferences get() = DeviceController.devicePrefs

    private fun init() {
        DeviceController.init(context)
        DeviceController.waterTempC.value = null
        DeviceController.tds.value = null
        DeviceController.batteryPct.value = null
        DeviceController.bottleVolumePct.value = null
        DeviceController.charging.value = false
        DeviceController.bottleLastUpdated.value = null
    }

    @Test
    fun `connect and disconnect bottle remember and forget the address`() {
        init()
        DeviceController.connectBottle("00:11:22:33:44:55")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("00:11:22:33:44:55", prefs.getString(DeviceController.BOTTLE_ADDRESS_KEY, null))
        assertTrue(DeviceController.bottlePaired.value)
        assertTrue(DeviceController.hasRememberedDevice())
        DeviceController.waterTempC.value = 22
        DeviceController.persistBottleTelemetry()
        DeviceController.disconnectBottle()
        assertNull(prefs.getString(DeviceController.BOTTLE_ADDRESS_KEY, null))
        assertFalse(DeviceController.bottlePaired.value)
        assertNull(DeviceController.waterTempC.value)
        assertFalse(prefs.contains(DeviceController.BOTTLE_TEMP_KEY))
        assertFalse(DeviceController.hasRememberedDevice())
    }

    @Test
    fun `connect and disconnect scale remember and forget the address and slot`() {
        init()
        DeviceController.saveScaleUserIndex(4)
        DeviceController.connectScale("00:11:22:33:44:66")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("00:11:22:33:44:66", prefs.getString(DeviceController.SCALE_ADDRESS_KEY, null))
        assertTrue(DeviceController.scalePaired.value)
        DeviceController.disconnectScale()
        assertNull(prefs.getString(DeviceController.SCALE_ADDRESS_KEY, null))
        assertFalse(DeviceController.scalePaired.value)
        assertNull(DeviceController.scaleUserSlot.value)
        assertFalse(DeviceController.hasRememberedDevice())
    }

    @Test
    fun `auto connect does nothing without permission or bluetooth`() {
        init()
        prefs.edit().putString(DeviceController.BOTTLE_ADDRESS_KEY, "00:11:22:33:44:55").commit()
        DeviceController.autoConnectSavedDevices()
        assertTrue(DeviceController.hasRememberedDevice())
        shadowOf(context as Application).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        // Permission granted but the Robolectric adapter is disabled: still a no-op.
        DeviceController.autoConnectSavedDevices()
        assertTrue(DeviceController.hasRememberedDevice())
        prefs.edit().remove(DeviceController.BOTTLE_ADDRESS_KEY).commit()
    }

    @Test
    fun `bluetooth permission check reflects the grant`() {
        init()
        shadowOf(context as Application).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertFalse(DeviceController.hasBluetoothConnectPermission())
        shadowOf(context as Application).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertTrue(DeviceController.hasBluetoothConnectPermission())
    }

    @Test
    fun `health connect availability surfaces unavailable here`() {
        init()
        assertFalse(DeviceController.isHealthConnectAvailable())
    }

    @Test
    fun `run on main executes the block`() {
        init()
        var ran = false
        DeviceController.runOnMain { ran = true }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ran)
    }

    @Test
    fun `persist bottle telemetry writes only present values`() {
        init()
        prefs.edit().clear().commit()
        DeviceController.waterTempC.value = 19
        DeviceController.charging.value = true
        DeviceController.bottleLastUpdated.value = 999L
        DeviceController.persistBottleTelemetry()
        assertEquals(19, prefs.getInt(DeviceController.BOTTLE_TEMP_KEY, -1))
        assertFalse(prefs.contains(DeviceController.BOTTLE_TDS_KEY))
        assertTrue(prefs.getBoolean(DeviceController.BOTTLE_CHARGING_KEY, false))
        assertEquals(999L, prefs.getLong(DeviceController.BOTTLE_UPDATED_KEY, -1))
    }

    @Test
    fun `load scale profile reads the stored profile back`() {
        init()
        prefs.edit()
            .putString("scale_sex", "Female")
            .putInt("scale_age", 41)
            .putString("scale_height", "182")
            .putString("scale_athlete", "true")
            .commit()
        DeviceController.loadScaleProfile()
        assertEquals(Sex.Female, DeviceController.scaleSex.value)
        assertEquals("41", DeviceController.scaleAge.value)
        assertEquals("182", DeviceController.scaleHeight.value)
        assertTrue(DeviceController.scaleAthlete.value)
        assertEquals(41, DeviceController.scaleProfile.value.age)
        assertEquals(182.0, DeviceController.scaleProfile.value.heightCm)
        // Restore the defaults the other tests in this class expect.
        prefs.edit()
            .putString("scale_sex", "Male")
            .putInt("scale_age", 30)
            .putString("scale_height", "175")
            .putString("scale_athlete", "false")
            .commit()
        DeviceController.loadScaleProfile()
    }

    @Test
    fun `scan and close entry points run without a radio`() {
        init()
        DeviceController.startBottleScan()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(DeviceController.scanning.value)
        DeviceController.startScaleScan()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(DeviceController.scaleScanning.value)
        DeviceController.closeManagers()
    }
}

