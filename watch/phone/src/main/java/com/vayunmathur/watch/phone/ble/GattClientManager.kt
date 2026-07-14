package com.vayunmathur.watch.phone.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
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
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.vayunmathur.watch.shared.ble.BleConstants
import com.vayunmathur.watch.phone.data.WatchBatch
import com.vayunmathur.watch.phone.data.WatchRecord
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

enum class ConnectionState {
    Disconnected,
    Scanning,
    Connecting,
    Connected,
}

/**
 * Scans for the watch by Service UUID, connects, discovers services, requests a
 * larger MTU, subscribes to the Data characteristic, reassembles the streamed
 * JSON batch until the EOT marker, deserializes it, then writes an ACK opcode to
 * the Control characteristic so the watch can clear the acked rows.
 */
@SuppressLint("MissingPermission")
class GattClientManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var dndCharacteristic: BluetoothGattCharacteristic? = null

    private val reassembly = ByteArrayOutputStream()

    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state

    private val _batches = MutableSharedFlow<List<WatchRecord>>(extraBufferCapacity = 8)
    val batches: SharedFlow<List<WatchRecord>> = _batches

    // Interruption-filter bytes pushed by the watch over the DND characteristic.
    private val _remoteDnd = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val remoteDnd: SharedFlow<Int> = _remoteDnd

    fun startScan() {
        val scanner = bluetoothManager.adapter?.bluetoothLeScanner ?: return
        if (scanning) return
        scanning = true
        _state.value = ConnectionState.Scanning
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    fun disconnect() {
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _state.value = ConnectionState.Disconnected
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            stopScan()
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            scanning = false
            _state.value = ConnectionState.Disconnected
        }
    }

    @Suppress("DEPRECATION")
    private fun connect(device: BluetoothDevice) {
        _state.value = ConnectionState.Connecting
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> gatt.requestMtu(REQUESTED_MTU)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _state.value = ConnectionState.Disconnected
                    gatt.close()
                    this@GattClientManager.gatt = null
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = gatt.getService(BleConstants.SERVICE_UUID) ?: return
            val dataChar = service.getCharacteristic(BleConstants.DATA_CHARACTERISTIC_UUID) ?: return
            dndCharacteristic = service.getCharacteristic(BleConstants.DND_CHARACTERISTIC_UUID)
            _state.value = ConnectionState.Connected
            // Enable the Data CCCD first; the DND CCCD is enabled from
            // onDescriptorWrite so the two descriptor writes are sequenced.
            subscribe(gatt, dataChar)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            when (descriptor.characteristic?.uuid) {
                BleConstants.DATA_CHARACTERISTIC_UUID -> {
                    // Subscription active; the watch will now push the batch.
                    reassembly.reset()
                    // Then subscribe to DND (one GATT op at a time).
                    dndCharacteristic?.let { subscribe(gatt, it) }
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            when (characteristic.uuid) {
                BleConstants.DATA_CHARACTERISTIC_UUID -> {
                    if (value.contentEquals(BleConstants.EOT_MARKER)) {
                        finishBatch(gatt)
                    } else {
                        reassembly.write(value)
                    }
                }
                BleConstants.DND_CHARACTERISTIC_UUID -> {
                    if (value.isNotEmpty()) _remoteDnd.tryEmit(value[0].toInt())
                }
            }
        }
    }

    private fun subscribe(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(BleConstants.CCCD_UUID) ?: return
        gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
    }

    /** Writes a new interruption filter to the watch's DND characteristic. */
    fun writeDnd(filter: Int) {
        val g = gatt ?: return
        val service = g.getService(BleConstants.SERVICE_UUID) ?: return
        val dnd = service.getCharacteristic(BleConstants.DND_CHARACTERISTIC_UUID) ?: return
        g.writeCharacteristic(
            dnd,
            byteArrayOf(filter.toByte()),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        )
    }

    /**
     * Polls the watch for new data. The watch only streams on subscribe or on a
     * read of the Data characteristic, so reading it here makes the watch re-stream
     * any rows accumulated (and not yet acked) since the last sync.
     */
    fun requestSync() {
        val g = gatt ?: return
        val service = g.getService(BleConstants.SERVICE_UUID) ?: return
        val data = service.getCharacteristic(BleConstants.DATA_CHARACTERISTIC_UUID) ?: return
        reassembly.reset()
        g.readCharacteristic(data)
    }

    private fun finishBatch(gatt: BluetoothGatt) {
        val bytes = reassembly.toByteArray()
        reassembly.reset()
        val records = try {
            if (bytes.isEmpty()) emptyList()
            else json.decodeFromString<WatchBatch>(bytes.toString(Charsets.UTF_8)).records
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse batch", e)
            return
        }
        if (records.isNotEmpty()) {
            _batches.tryEmit(records)
        }
        sendAck(gatt)
    }

    private fun sendAck(gatt: BluetoothGatt) {
        val service = gatt.getService(BleConstants.SERVICE_UUID) ?: return
        val control = service.getCharacteristic(BleConstants.CONTROL_CHARACTERISTIC_UUID) ?: return
        gatt.writeCharacteristic(
            control,
            byteArrayOf(BleConstants.OPCODE_ACK),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        )
    }

    companion object {
        private const val TAG = "GattClientManager"
        private const val REQUESTED_MTU = 512
    }
}
