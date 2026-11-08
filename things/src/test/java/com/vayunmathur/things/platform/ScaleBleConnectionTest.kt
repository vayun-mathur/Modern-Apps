package com.vayunmathur.things.platform

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Scale scan/watch/connect/GATT-callback behaviour through the BLE shadows. */
@RunWith(RobolectricTestRunner::class)
class ScaleBleConnectionTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val adapter: BluetoothAdapter get() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private fun manager(): ScaleBleManager {
        DeviceController.init(context)
        DeviceController.scaleDevices.clear()
        DeviceController.devicePrefs.edit()
            .remove(DeviceController.SCALE_CATEGORY_KEY)
            .remove(DeviceController.SCALE_ENCRYPT_RES_KEY)
            .commit()
        DeviceController.scaleScanning.value = false
        DeviceController.scaleConnectionState.value = "Disconnected"
        DeviceController.scaleLink.value = DeviceController.LinkState.Waiting
        DeviceController.scaleWeight.value = null
        shadowOf(Looper.getMainLooper()).idle()
        return ScaleBleManager()
    }

    private fun flush() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    /** Builds a ScanRecord holding manufacturer data, via the framework's hidden parser. */
    private fun scanRecord(mfg: ByteArray?): ScanRecord? {
        if (mfg == null) return null
        val adv = ArrayList<Byte>()
        adv.add(2); adv.add(0x01); adv.add(0x06) // flags
        val fieldLen = 1 + 2 + mfg.size
        adv.add(fieldLen.toByte()); adv.add(0xFF.toByte())
        adv.add(0x34); adv.add(0x12) // company id 0x1234, little-endian
        mfg.forEach { adv.add(it) }
        val bytes = ByteArray(adv.size) { adv[it] }
        val parse = ScanRecord::class.java.getMethod("parseFromBytes", ByteArray::class.java)
        return parse.invoke(null, bytes) as ScanRecord
    }

    private fun scanResult(name: String?, address: String, mfg: ByteArray? = null): ScanResult {
        val device = adapter.getRemoteDevice(address)
        if (name != null) shadowOf(device).setName(name)
        @Suppress("DEPRECATION")
        return ScanResult(device, scanRecord(mfg), -50, 0L)
    }

    private fun mfgData(categoryMarker: Int = MARKER_CAT_128_A, encrypt: Boolean = true): ByteArray {
        val mfg = ByteArray(16)
        mfg[MFG_CATEGORY_INDEX] = categoryMarker.toByte()
        if (encrypt) mfg[MFG_ENCRYPT_FLAG_INDEX_VA] = 0b100.toByte()
        return mfg
    }

    @Test
    fun `scan collects named scales with their manufacturer traits`() {
        val mgr = manager()
        mgr.startScan()
        assertTrue(DeviceController.scaleScanning.value)
        assertEquals(SCALE_SCANNING_STATE, DeviceController.scaleConnectionState.value)
        val cb = shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single()
        val mfg = mfgData()
        cb.onScanResult(0, scanResult("QN-Scale", "00:11:22:33:44:55", mfg))
        cb.onScanResult(0, scanResult("QN-Scale", "00:11:22:33:44:55", mfg)) // duplicate
        cb.onScanResult(0, scanResult("renpho scale", "00:11:22:33:44:66")) // prefix, any case
        cb.onScanResult(0, scanResult("WaterH Bottle", "00:11:22:33:44:77", mfg))
        cb.onScanResult(0, scanResult(null, "00:11:22:33:44:88", mfg))
        flush()
        assertEquals(
            listOf("00:11:22:33:44:55", "00:11:22:33:44:66"),
            DeviceController.scaleDevices.map { it.address },
        )
        assertTrue(mgr.manufacturerData.containsKey("00:11:22:33:44:55"))
        mgr.stopScan()
        assertFalse(DeviceController.scaleScanning.value)
        // The scan timeout also stops a scan left running.
        mgr.startScan()
        advance(21_000)
        assertFalse(DeviceController.scaleScanning.value)
    }

    @Test
    fun `scan failure reports and stops`() {
        val mgr = manager()
        mgr.startScan()
        val cb = shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single()
        cb.onScanFailed(3)
        flush()
        assertFalse(DeviceController.scaleScanning.value)
        assertEquals("Scan failed (3)", DeviceController.scaleConnectionState.value)
    }

    @Test
    fun `passive watch connects when the scale wakes and ignores others`() {
        val mgr = manager()
        mgr.connect("00:11:22:33:44:55", passive = true)
        assertEquals("00:11:22:33:44:55", mgr.watchAddress)
        assertEquals(SCALE_WAITING_STATE, DeviceController.scaleConnectionState.value)
        val cb = shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single()
        // A different device waking does nothing.
        cb.onScanResult(0, scanResult("QN-Scale", "00:11:22:33:44:99", mfgData()))
        flush()
        assertEquals("00:11:22:33:44:55", mgr.watchAddress)
        // Our scale wakes: the watch stops and a GATT connection opens.
        cb.onScanResult(0, scanResult("QN-Scale", "00:11:22:33:44:55", mfgData()))
        flush()
        assertEquals(null, mgr.watchAddress)
        val device = adapter.getRemoteDevice("00:11:22:33:44:55")
        assertEquals(1, shadowOf(device).bluetoothGatts.size)
        assertEquals(DeviceController.LinkState.Connecting, DeviceController.scaleLink.value)
        assertEquals(CATEGORY_128, mgr.scaleCategory)
        assertTrue(mgr.useResistanceEncrypt)
        assertTrue(mgr.isVaScale)
        // The advertised traits were remembered for the next launch.
        assertEquals(CATEGORY_128, DeviceController.savedScaleCategory())
        // A watch scan failure clears the watch.
        mgr.connect("00:11:22:33:44:55", passive = true)
        // Already connected to this address through the earlier gatt: connect is a no-op;
        // drive the failure through a fresh watch on another address instead.
        mgr.disconnect()
        mgr.connect("00:11:22:33:44:66", passive = true)
        val cb2 = shadowOf(adapter.bluetoothLeScanner).scanCallbacks.last()
        cb2.onScanFailed(1)
        flush()
        assertEquals(null, mgr.watchAddress)
    }

    @Test
    fun `connect is idempotent and disconnect prevents reconnect watch`() {
        val mgr = manager()
        mgr.connect("00:11:22:33:44:55")
        flush()
        val device = adapter.getRemoteDevice("00:11:22:33:44:55")
        assertEquals(1, shadowOf(device).bluetoothGatts.size)
        mgr.connect("00:11:22:33:44:55")
        assertEquals(1, shadowOf(device).bluetoothGatts.size)
        // Saved-traits fallback: nothing was scanned, so prefs/defaults decide the category.
        assertEquals(CATEGORY_DEFAULT, mgr.scaleCategory)
        assertFalse(mgr.isVaScale)
        mgr.disconnect()
        assertTrue(mgr.intentionalDisconnect)
        mgr.close()
        mgr.close()
    }

    private fun service(
        uuid: UUID,
        chars: List<Pair<UUID, Boolean>>,
    ): BluetoothGattService {
        val svc = BluetoothGattService(uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        for ((charUuid, withCccd) in chars) {
            val ch = BluetoothGattCharacteristic(
                charUuid,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                0,
            )
            if (withCccd) {
                ch.addDescriptor(
                    BluetoothGattDescriptor(
                        ScaleBleManager.CCCD_UUID,
                        BluetoothGattDescriptor.PERMISSION_WRITE,
                    ),
                )
            }
            svc.addCharacteristic(ch)
        }
        return svc
    }

    @Test
    fun `gatt lifecycle drives discovery descriptors and notifications`() {
        val mgr = manager()
        mgr.connect("00:11:22:33:44:55")
        val device = adapter.getRemoteDevice("00:11:22:33:44:55")
        val gatt = shadowOf(device).bluetoothGatts.single()
        val cb = mgr.gattCallback
        cb.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        flush()
        assertEquals(DeviceController.LinkState.Connected, DeviceController.scaleLink.value)
        assertEquals("Discovering services...", DeviceController.scaleConnectionState.value)
        // Discovery failure is reported in the state string.
        cb.onServicesDiscovered(gatt, 8)
        flush()
        assertEquals("Service discovery failed (8)", DeviceController.scaleConnectionState.value)
        // No scale service.
        cb.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        flush()
        assertEquals("Scale service not found", DeviceController.scaleConnectionState.value)
        // Service without the notify char.
        shadowOf(gatt).addDiscoverableService(service(ScaleBleManager.SERVICE_FFE0, emptyList()))
        gatt.discoverServices()
        flush()
        assertEquals("Scale notifying char not found", DeviceController.scaleConnectionState.value)
    }

    @Test
    fun `full ffe0 discovery enables notify and indicate then connects`() {
        val mgr = manager()
        mgr.connect("00:11:22:33:44:55")
        val device = adapter.getRemoteDevice("00:11:22:33:44:55")
        val gatt = shadowOf(device).bluetoothGatts.single()
        val svc = service(
            ScaleBleManager.SERVICE_FFE0,
            listOf(
                ScaleBleManager.CHAR_FFE1 to true,
                ScaleBleManager.CHAR_FFE2 to true,
                ScaleBleManager.CHAR_FFE3 to false,
                ScaleBleManager.CHAR_FFE4 to false,
            ),
        )
        shadowOf(gatt).addDiscoverableService(svc)
        val cb = mgr.gattCallback
        cb.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        flush()
        gatt.discoverServices()
        flush()
        assertEquals(ScaleBleManager.CHAR_FFE4, mgr.bleWriteChar)
        assertEquals(ScaleBleManager.CHAR_FFE3, mgr.configChar)
        assertFalse(mgr.isHoltek)
        // Two descriptors were queued (FFE1 notify + FFE2 indicate); drain both.
        val desc = svc.getCharacteristic(ScaleBleManager.CHAR_FFE1)
            .getDescriptor(ScaleBleManager.CCCD_UUID)
        cb.onDescriptorWrite(gatt, desc, BluetoothGatt.GATT_SUCCESS)
        flush()
        cb.onDescriptorWrite(gatt, desc, BluetoothGatt.GATT_SUCCESS)
        flush()
        assertEquals("Connected — step on scale", DeviceController.scaleConnectionState.value)
        // A measurement notification on FFE1 is dispatched.
        val notifyChar = BluetoothGattCharacteristic(ScaleBleManager.CHAR_FFE1, 0, 0)
        val pkt = byteArrayOf(PACKET_MEASURE.toByte(), 0, 0, 0x02, 0xEE.toByte(), 0, 0, 0, 0, 0)
        cb.onCharacteristicChanged(gatt, notifyChar, pkt)
        flush()
        assertEquals(75.0, DeviceController.scaleRealtimeWeight.value)
        // Other chars and empty values are ignored; the deprecated overload works too.
        val other = BluetoothGattCharacteristic(UUID.randomUUID(), 0, 0)
        cb.onCharacteristicChanged(gatt, other, pkt)
        cb.onCharacteristicChanged(gatt, notifyChar, byteArrayOf())
        notifyChar.setValue(pkt)
        @Suppress("DEPRECATION")
        cb.onCharacteristicChanged(gatt, notifyChar)
        flush()
        // Writes drain through the queue on characteristic-write completion.
        mgr.enqueue(ScaleBleManager.CHAR_FFE3, byteArrayOf(1, 2, 3))
        mgr.enqueue(ScaleBleManager.CHAR_FFE3, byteArrayOf(4, 5, 6))
        cb.onCharacteristicWrite(gatt, notifyChar, BluetoothGatt.GATT_SUCCESS)
        flush()
        advance(100)
    }

    @Test
    fun `holtek fff0 discovery collapses writes onto fff2`() {
        val mgr = manager()
        mgr.connect("00:11:22:33:44:55")
        val device = adapter.getRemoteDevice("00:11:22:33:44:55")
        val gatt = shadowOf(device).bluetoothGatts.single()
        val svc = service(
            ScaleBleManager.SERVICE_FFF0,
            listOf(ScaleBleManager.CHAR_FFF1 to true, ScaleBleManager.CHAR_FFF2 to false),
        )
        shadowOf(gatt).addDiscoverableService(svc)
        val cb = mgr.gattCallback
        gatt.discoverServices()
        flush()
        assertTrue(mgr.isHoltek)
        assertEquals(ScaleBleManager.CHAR_FFF2, mgr.configChar)
        assertEquals(ScaleBleManager.CHAR_FFF2, mgr.bleWriteChar)
        val desc = svc.getCharacteristic(ScaleBleManager.CHAR_FFF1)
            .getDescriptor(ScaleBleManager.CCCD_UUID)
        cb.onDescriptorWrite(gatt, desc, BluetoothGatt.GATT_SUCCESS)
        flush()
        assertEquals("Connected — step on scale", DeviceController.scaleConnectionState.value)
    }

    @Test
    fun `missing cccd is reported`() {
        val mgr = manager()
        mgr.connect("00:11:22:33:44:55")
        val device = adapter.getRemoteDevice("00:11:22:33:44:55")
        val gatt = shadowOf(device).bluetoothGatts.single()
        shadowOf(gatt).addDiscoverableService(
            service(ScaleBleManager.SERVICE_FFE0, listOf(ScaleBleManager.CHAR_FFE1 to false)),
        )
        gatt.discoverServices()
        flush()
        assertEquals("Scale CCCD not found", DeviceController.scaleConnectionState.value)
    }

    @Test
    fun `disconnect callback returns to watching unless intentional`() {
        val mgr = manager()
        mgr.connect("00:11:22:33:44:55")
        val device = adapter.getRemoteDevice("00:11:22:33:44:55")
        val gatt = shadowOf(device).bluetoothGatts.single()
        DeviceController.scaleDevices.add(ScaleBleManager.ScaleBleDevice("QN-Scale", "00:11:22:33:44:55"))
        mgr.gattCallback.onConnectionStateChange(
            gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED,
        )
        flush()
        assertEquals(DeviceController.LinkState.Waiting, DeviceController.scaleLink.value)
        assertTrue(DeviceController.scaleDevices.isEmpty())
        assertEquals("00:11:22:33:44:55", mgr.watchAddress, "an unexpected drop re-watches")
        // Now disconnect intentionally and repeat: no watch is started.
        mgr.disconnect()
        mgr.gattCallback.onConnectionStateChange(
            gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED,
        )
        flush()
        assertEquals(null, mgr.watchAddress)
        assertNotNull(mgr.gattCallback)
    }

    @Test
    fun `time retry gives up after the limit`() {
        val mgr = manager()
        mgr.timeRetries = 0
        mgr.timeRetry.run()
        mgr.timeRetry.run()
        mgr.timeRetry.run()
        assertEquals(3, mgr.timeRetries)
        mgr.timeRetry.run() // over the limit: returns without counting or crashing
        assertEquals(3, mgr.timeRetries)
        mgr.sendStart.run()
        flush()
    }
}
