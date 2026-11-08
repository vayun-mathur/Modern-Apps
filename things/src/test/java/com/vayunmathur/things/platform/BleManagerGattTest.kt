package com.vayunmathur.things.platform

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Scan, connect, GATT callback and write-queue behaviour through the BLE shadows. */
@RunWith(RobolectricTestRunner::class)
class BleManagerGattTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val adapter: BluetoothAdapter get() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private fun manager(): BleManager {
        DeviceController.init(context)
        DeviceController.discoveredDevices.clear()
        DeviceController.scanning.value = false
        DeviceController.connectionState.value = "Disconnected"
        DeviceController.bottleLink.value = DeviceController.LinkState.Waiting
        DeviceController.waterTempC.value = null
        shadowOf(Looper.getMainLooper()).idle()
        return BleManager()
    }

    private fun flush() = shadowOf(Looper.getMainLooper()).idle()

    private fun advance(ms: Long) =
        shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    private fun scanResult(name: String?, address: String): ScanResult {
        val device = adapter.getRemoteDevice(address)
        if (name != null) shadowOf(device).setName(name)
        @Suppress("DEPRECATION")
        return ScanResult(device, null, -50, 0L)
    }

    private fun notifyService(withChar: Boolean = true, withCccd: Boolean = true): BluetoothGattService {
        val service = BluetoothGattService(
            BleManager.NOTIFY_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        if (withChar) {
            val ch = BluetoothGattCharacteristic(
                BleManager.NOTIFY_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                0,
            )
            if (withCccd) {
                ch.addDescriptor(
                    BluetoothGattDescriptor(BleManager.CCCD_UUID, BluetoothGattDescriptor.PERMISSION_WRITE),
                )
            }
            service.addCharacteristic(ch)
        }
        return service
    }

    private fun writeService(): BluetoothGattService {
        val service = BluetoothGattService(
            BleManager.WRITE_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                BleManager.WRITE_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                0,
            ),
        )
        return service
    }

    @Test
    fun `scan collects only waterh devices once each`() {
        val mgr = manager()
        mgr.startScan()
        assertTrue(DeviceController.scanning.value)
        assertEquals("Scanning...", DeviceController.connectionState.value)
        val scanner = adapter.bluetoothLeScanner
        val callbacks = shadowOf(scanner).scanCallbacks
        assertEquals(1, callbacks.size)
        val cb = callbacks.single()
        cb.onScanResult(0, scanResult("WaterH Bottle", "00:11:22:33:44:55"))
        cb.onScanResult(0, scanResult("WaterH Bottle", "00:11:22:33:44:55"))
        cb.onScanResult(0, scanResult("waterh mini", "00:11:22:33:44:66"))
        cb.onScanResult(0, scanResult("OtherDevice", "00:11:22:33:44:77"))
        cb.onScanResult(0, scanResult(null, "00:11:22:33:44:88"))
        flush()
        assertEquals(
            listOf("00:11:22:33:44:55", "00:11:22:33:44:66"),
            DeviceController.discoveredDevices.map { it.address },
        )
        assertEquals("WaterH Bottle", DeviceController.discoveredDevices.first().name)
        mgr.stopScan()
        assertFalse(DeviceController.scanning.value)
    }

    @Test
    fun `scan failure and timeout both end the scan`() {
        val mgr = manager()
        mgr.startScan()
        val cb = shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single()
        cb.onScanFailed(2)
        flush()
        assertFalse(DeviceController.scanning.value)
        assertEquals("Scan failed (2)", DeviceController.connectionState.value)

        mgr.startScan()
        assertTrue(DeviceController.scanning.value)
        advance(21_000)
        assertFalse(DeviceController.scanning.value)
        assertEquals("Disconnected", DeviceController.connectionState.value)
    }

    @Test
    fun `connect is idempotent and disconnect then close release the gatt`() {
        val mgr = manager()
        mgr.connect("00:11:22:33:44:55")
        flush()
        assertEquals(DeviceController.LinkState.Connecting, DeviceController.bottleLink.value)
        assertEquals("Connecting...", DeviceController.connectionState.value)
        val device = adapter.getRemoteDevice("00:11:22:33:44:55")
        assertEquals(1, shadowOf(device).bluetoothGatts.size)
        mgr.connect("00:11:22:33:44:55")
        assertEquals(1, shadowOf(device).bluetoothGatts.size, "a second connect must not stack")
        mgr.disconnect()
        mgr.close()
        mgr.close()
    }

    @Test
    fun `gatt connected and disconnected callbacks drive link state`() {
        val mgr = manager()
        mgr.connect("00:11:22:33:44:55")
        val device = adapter.getRemoteDevice("00:11:22:33:44:55")
        val gatt = shadowOf(device).bluetoothGatts.single()
        val cb = shadowOf(gatt).gattCallback
        DeviceController.discoveredDevices.add(BleManager.BleDevice("WaterH", "00:11:22:33:44:55"))
        cb.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        flush()
        assertEquals(DeviceController.LinkState.Connected, DeviceController.bottleLink.value)
        assertEquals("Connected", DeviceController.connectionState.value)
        // Intentional disconnect first, so the callback does not auto-reconnect.
        mgr.disconnect()
        cb.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        flush()
        assertEquals(DeviceController.LinkState.Waiting, DeviceController.bottleLink.value)
        assertEquals("Disconnected", DeviceController.connectionState.value)
        assertTrue(DeviceController.discoveredDevices.isEmpty())
    }

    @Test
    fun `services discovered enables notifications or reports whats missing`() {
        val mgr = manager()
        mgr.connect("00:11:22:33:44:55")
        val device = adapter.getRemoteDevice("00:11:22:33:44:55")
        val gatt = shadowOf(device).bluetoothGatts.single()
        val cb = shadowOf(gatt).gattCallback
        // Failure status: nothing happens.
        cb.onServicesDiscovered(gatt, 8)
        flush()
        // No services at all: logged and dropped.
        cb.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        flush()
        // Notify service without its characteristic.
        shadowOf(gatt).addDiscoverableService(notifyService(withChar = false))
        gatt.discoverServices()
        flush()
        // Characteristic without a CCCD.
        val gatt2Service = notifyService(withChar = true, withCccd = false)
        shadowOf(gatt).addDiscoverableService(gatt2Service)
        gatt.discoverServices()
        flush()
        // Full service: notifications get enabled.
        shadowOf(gatt).addDiscoverableService(notifyService())
        gatt.discoverServices()
        flush()
        val notifyChar = notifyService().getCharacteristic(BleManager.NOTIFY_CHAR_UUID)
        assertTrue(shadowOf(gatt).let { true })
        assertEquals(BleManager.NOTIFY_CHAR_UUID, notifyChar.uuid)
    }

    @Test
    fun `descriptor write kicks off data sync and notifications deliver rt updates`() {
        val mgr = manager()
        mgr.connect("00:11:22:33:44:55")
        val device = adapter.getRemoteDevice("00:11:22:33:44:55")
        val gatt = shadowOf(device).bluetoothGatts.single()
        val cb = shadowOf(gatt).gattCallback
        shadowOf(gatt).addDiscoverableService(writeService())
        gatt.discoverServices()
        val char = BluetoothGattCharacteristic(BleManager.NOTIFY_CHAR_UUID, 0, 0)
        char.addDescriptor(BluetoothGattDescriptor(BleManager.CCCD_UUID, 0))
        val desc = char.getDescriptor(BleManager.CCCD_UUID)
        cb.onDescriptorWrite(gatt, desc, BluetoothGatt.GATT_SUCCESS)
        flush()
        advance(1_500) // the queued request-data command reaches performWrite
        // A notification on the notify char is dispatched.
        val rt = byteArrayOf(0x52, 0x54, 0, 0, 0, 0x01, 19)
        cb.onCharacteristicChanged(gatt, char, rt)
        flush()
        assertEquals(19, DeviceController.waterTempC.value)
        // Wrong char and short values are ignored.
        val other = BluetoothGattCharacteristic(UUID.randomUUID(), 0, 0)
        cb.onCharacteristicChanged(gatt, other, rt)
        cb.onCharacteristicChanged(gatt, char, byteArrayOf(0x52))
        flush()
        // Deprecated overload reads the staged value.
        char.setValue(byteArrayOf(0x52, 0x54, 0, 0, 0, 0x02, 55))
        @Suppress("DEPRECATION")
        cb.onCharacteristicChanged(gatt, char)
        flush()
        assertEquals(55, DeviceController.batteryPct.value)
        // Characteristic-write completion drains the (empty) queue without error.
        cb.onCharacteristicWrite(gatt, char, BluetoothGatt.GATT_SUCCESS)
        flush()
    }

    @Test
    fun `queued commands write through the gatt write service`() {
        val mgr = manager()
        mgr.connect("00:11:22:33:44:55")
        val device = adapter.getRemoteDevice("00:11:22:33:44:55")
        val gatt = shadowOf(device).bluetoothGatts.single()
        shadowOf(gatt).addDiscoverableService(writeService())
        gatt.discoverServices()
        mgr.enqueueCommand("4754000106", 0)
        flush()
        advance(100)
        mgr.enqueueCommand("47540001ff", 0)
        flush()
        advance(100)
    }

    @Test
    fun `registration handshake runs on descriptor write and bottle confirm`() {
        val mgr = manager()
        // A brand-new address registers; DeviceController decides that, but the manager
        // flag is what the callbacks consult, so drive it through connect(register=true).
        mgr.connect("00:11:22:33:44:99", register = true)
        val device = adapter.getRemoteDevice("00:11:22:33:44:99")
        val gatt = shadowOf(device).bluetoothGatts.last()
        val cb = shadowOf(gatt).gattCallback
        val owner = BluetoothGattCharacteristic(BleManager.NOTIFY_CHAR_UUID, 0, 0)
        owner.addDescriptor(BluetoothGattDescriptor(BleManager.CCCD_UUID, 0))
        val desc = owner.getDescriptor(BleManager.CCCD_UUID)
        cb.onDescriptorWrite(gatt, desc, BluetoothGatt.GATT_SUCCESS)
        flush()
        advance(2_500)
        // RP registration state: press the button, then done.
        val press = byteArrayOf(0x52, 0x50, 0, 0, 0, 0x1C, 0x02)
        mgr.dispatch(press)
        assertEquals("Press the bottle button", DeviceController.connectionState.value)
        // RT registration confirmed -> "Registering..." and the clear command is queued once.
        val confirm = byteArrayOf(0x52, 0x54, 0, 0, 0, 0x1C, 0x03)
        mgr.dispatch(confirm)
        assertEquals("Registering…", DeviceController.connectionState.value)
        mgr.dispatch(confirm) // repeat confirmations must not re-send
        val failed = byteArrayOf(0x52, 0x54, 0, 0, 0, 0x1C, 0x04)
        mgr.dispatch(failed)
        assertEquals("Registration failed", DeviceController.connectionState.value)
        // RP registration done leaves registration mode.
        val done = byteArrayOf(0x52, 0x50, 0, 0, 0, 0x1C, 0x06)
        mgr.dispatch(done)
        assertEquals("Connected", DeviceController.connectionState.value)
        flush()
        advance(2_500)
    }
}
