package com.vayunmathur.watch.watch.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.vayunmathur.watch.watch.data.SensorDao
import com.vayunmathur.watch.watch.data.SensorRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class WireRecord(
    val id: Long,
    val type: String,
    val timestamp: Long,
    val value: Double,
    val delta: Double,
    val stationary: Boolean = false,
)

@Serializable
private data class WireBatch(val records: List<WireRecord>)

/**
 * Owns the BLE advertiser and GATT server. When a client subscribes to (or reads)
 * the Data characteristic, all currently-stored Room rows are serialized to JSON,
 * chunked to MTU-sized notifications, and streamed followed by an EOT marker.
 * When the client writes an ACK opcode to the Control characteristic, the rows
 * that were streamed are deleted from Room.
 */
@SuppressLint("MissingPermission")
class GattServerManager(
    private val context: Context,
    private val dao: SensorDao,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    private lateinit var dataCharacteristic: BluetoothGattCharacteristic
    private lateinit var controlCharacteristic: BluetoothGattCharacteristic

    // Per-device negotiated MTU (default 23 until MTU exchange).
    private val deviceMtu = mutableMapOf<String, Int>()

    // Ids streamed to each device in the last batch, pending ACK.
    private val pendingAck = mutableMapOf<String, List<Long>>()

    private val _advertising = MutableStateFlow(false)
    val advertising: StateFlow<Boolean> = _advertising

    fun start() {
        val adapter = bluetoothManager.adapter ?: return
        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth adapter disabled")
            return
        }
        openGattServer()
        startAdvertising(adapter.bluetoothLeAdvertiser)
    }

    fun stop() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "stopAdvertising failed", e)
        }
        _advertising.value = false
        gattServer?.close()
        gattServer = null
    }

    private fun openGattServer() {
        val server = bluetoothManager.openGattServer(context, serverCallback) ?: return
        gattServer = server

        val service = BluetoothGattService(
            BleConstants.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )

        dataCharacteristic = BluetoothGattCharacteristic(
            BleConstants.DATA_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        dataCharacteristic.addDescriptor(
            BluetoothGattDescriptor(
                BleConstants.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )

        controlCharacteristic = BluetoothGattCharacteristic(
            BleConstants.CONTROL_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        service.addCharacteristic(dataCharacteristic)
        service.addCharacteristic(controlCharacteristic)
        server.addService(service)
    }

    private fun startAdvertising(bleAdvertiser: BluetoothLeAdvertiser?) {
        advertiser = bleAdvertiser
        if (bleAdvertiser == null) {
            Log.w(TAG, "BLE advertising not supported")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()
        bleAdvertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            _advertising.value = true
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed: $errorCode")
            _advertising.value = false
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            deviceMtu[device.address] = mtu
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, 0 /* GATT_SUCCESS */, offset, value)
            }
            // Subscription enabled -> stream the current batch.
            if (descriptor.uuid == BleConstants.CCCD_UUID && value != null &&
                value.isNotEmpty() && value[0].toInt() != 0
            ) {
                streamBatch(device)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            // A plain read triggers a fresh stream and returns an empty ack payload.
            gattServer?.sendResponse(device, requestId, 0, offset, ByteArray(0))
            if (characteristic.uuid == BleConstants.DATA_CHARACTERISTIC_UUID) {
                streamBatch(device)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (characteristic.uuid == BleConstants.CONTROL_CHARACTERISTIC_UUID &&
                value != null && value.isNotEmpty()
            ) {
                handleControl(device, value)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, 0, offset, null)
            }
        }
    }

    private fun handleControl(device: BluetoothDevice, value: ByteArray) {
        when (value[0]) {
            BleConstants.OPCODE_ACK -> {
                val ids = pendingAck.remove(device.address).orEmpty()
                if (ids.isNotEmpty()) {
                    scope.launch(Dispatchers.IO) { dao.deleteByIds(ids) }
                }
            }
            BleConstants.OPCODE_CLEAR -> {
                pendingAck.remove(device.address)
            }
        }
    }

    private fun streamBatch(device: BluetoothDevice) {
        scope.launch(Dispatchers.IO) {
            val rows: List<SensorRecord> = dao.getAll()
            val batch = WireBatch(rows.map {
                WireRecord(it.id, it.type.name, it.timestamp, it.value, it.delta, it.stationary)
            })
            pendingAck[device.address] = rows.map { it.id }

            val payload = json.encodeToString(batch).toByteArray(Charsets.UTF_8)
            val mtu = deviceMtu[device.address] ?: DEFAULT_MTU
            val chunkSize = (mtu - 3).coerceAtLeast(20)

            var offset = 0
            while (offset < payload.size) {
                val end = minOf(offset + chunkSize, payload.size)
                notify(device, payload.copyOfRange(offset, end))
                offset = end
            }
            // Zero-length batches still send EOT so the client can complete.
            notify(device, BleConstants.EOT_MARKER)
        }
    }

    @Suppress("DEPRECATION")
    private fun notify(device: BluetoothDevice, bytes: ByteArray) {
        val server = gattServer ?: return
        try {
            dataCharacteristic.value = bytes
            server.notifyCharacteristicChanged(device, dataCharacteristic, false)
        } catch (e: Exception) {
            Log.e(TAG, "notify failed", e)
        }
    }

    companion object {
        private const val TAG = "GattServerManager"
        private const val DEFAULT_MTU = 23
    }
}
