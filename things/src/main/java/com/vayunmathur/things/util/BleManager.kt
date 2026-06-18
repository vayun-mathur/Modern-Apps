package com.vayunmathur.things.util

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import com.vayunmathur.things.MainActivity
import java.util.UUID

@SuppressLint("MissingPermission")
class BleManager(private val activity: MainActivity) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
        val CHAR_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef1")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    data class BleDevice(val name: String, val address: String)

    private val bluetoothManager = activity.getSystemService(BluetoothManager::class.java)
    private val adapter = bluetoothManager.adapter
    private val scanner get() = adapter.bluetoothLeScanner
    private var gatt: BluetoothGatt? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            val addr = result.device.address
            if (activity.discoveredDevices.none { it.address == addr }) {
                activity.discoveredDevices.add(BleDevice(name, addr))
            }
        }
    }

    fun startScan() {
        activity.discoveredDevices.clear()
        activity.scanning.value = true
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(listOf(filter), settings, scanCallback)
        activity.connectionState.value = "Scanning..."
    }

    fun connect(address: String) {
        scanner?.stopScan(scanCallback)
        activity.scanning.value = false
        activity.connectionState.value = "Connecting..."
        val device = adapter.getRemoteDevice(address)
        gatt = device.connectGatt(activity, false, gattCallback)
    }

    fun disconnect() {
        gatt?.disconnect()
    }

    fun close() {
        gatt?.close()
        gatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            activity.runOnUiThread {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        activity.connectionState.value = "Connected"
                        g.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        activity.connectionState.value = "Disconnected"
                        activity.discoveredDevices.clear()
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = g.getService(SERVICE_UUID) ?: return
            val char = service.getCharacteristic(CHAR_UUID) ?: return
            g.setCharacteristicNotification(char, true)
            val desc = char.getDescriptor(CCCD_UUID)
            if (desc != null) {
                g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            char: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            // Firmware sends a single number: the mL consumed in one drink.
            val ml = value.decodeToString().trim().toIntOrNull() ?: return
            activity.runOnUiThread {
                activity.onDrinkReceived(ml)
            }
        }
    }
}
