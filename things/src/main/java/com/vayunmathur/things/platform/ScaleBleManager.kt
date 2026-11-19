package com.vayunmathur.things.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.vayunmathur.library.log.Log
import java.util.UUID

/**
 * Offline BLE manager for the Renpho Elis 1 / Qingniu (Yolanda) scale.
 *
 * Protocol basis: renpho_analysis/OFFLINE_FEASIBILITY.md + YOLANDA_CALC_FORMULAS.md +
 * jadx-out/sources/com/qingniu and com/qn.
 *
 * - GATT services 0000FFE0 / 0000FFF0; notify chars 0000FFE1/0000FFF1, indicate 0000FFE2;
 *   write chars 0000FFE3/0000FFF2 (config, acks) and 0000FFE4 (time, start).
 * - The scale will not stream anything until it is driven: on receiving the 0x12 scale-info it
 *   wants a 0x13 config frame, then a 0x20 time frame (re-sent until acknowledged by 0x21), then
 *   a 0x22 start. Only then does it emit 0x10 weight packets. See ScaleBleServiceManager /
 *   ScaleBleManager / QNDecoderImpl in the decompiled SDK.
 * - Scan-by-name: devices advertise as QN-Scale / QN-Scale1 / RENPHO / Elis (confirmed
 *   from BleConst.DEFAULT_BLE_SCALE_NAME="QN-Scale", DEFAULT_BLE_SCALE_NAME_1="QN-Scale1"
 *   and OFFLINE_FEASIBILITY packet captures). We filter on name prefix.
 * - Scale category and the resistance-encryption flag come from the advertisement's
 *   manufacturer-specific data, exactly as ScaleBleUtils.checkScaleType and
 *   ScaleBleUtils.isUseResistanceEncrypt read them.
 * - Packet decode: QNDecoderImpl.decodeData — c=16 weight at bytes 3..4 (weightRatio 10/100),
 *   sub-command at byte 5 (0/17/18 streaming, 1 and 2 stable, 1 on an eight-electrode scale
 *   is the ten-channel burst); impedance via fourResTwoByte2Int(bytes 6..9) and
 *   eightResTwoByte2Double(kRatio=0.1); c=18 scale-info (weightRatio, units), c=35 stored
 *   history (timestamp bytes 5..8, weight 9..10, impedance 11..14).
 *
 * No wifi/internet/cloud/login — pure on-device BLE + BodyComposition math.
 */
@SuppressLint("MissingPermission")
class ScaleBleManager {

    /** Qingniu/Renpho scale GATT UUIDs, name prefixes and handshake timings. */
    companion object {
        internal const val TAG = "ScaleBle"

        // Primary Qingniu GATT (covers Elis 1)
        val SERVICE_FFE0: UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        val CHAR_FFE1: UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
        val CHAR_FFE2: UUID = UUID.fromString("0000FFE2-0000-1000-8000-00805F9B34FB")
        val CHAR_FFE3: UUID = UUID.fromString("0000FFE3-0000-1000-8000-00805F9B34FB")
        val CHAR_FFE4: UUID = UUID.fromString("0000FFE4-0000-1000-8000-00805F9B34FB")
        // Secondary (FFF0 family) — Holtek firmware collapses every write onto FFF2
        val SERVICE_FFF0: UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
        val CHAR_FFF1: UUID = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")
        val CHAR_FFF2: UUID = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB")
        // Battery + Device Info
        val SERVICE_BATTERY: UUID = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
        val CHAR_BATTERY: UUID = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        /** Name prefixes seen for Qingniu/Renpho scales. */
        val SCALE_NAME_PREFIXES = listOf("QN-Scale", "QN-S3", "RENPHO", "Elis", "Yolanda", "QIANGNIU")

        // Command bytes, VA categories and decode constants live in ScaleBleProtocol.kt.

        // Handshake timings lifted from QNDecoderImpl.
        internal const val CONFIG_TO_TIME_MS = 300L
        internal const val TIME_RETRY_MS = 250L
        internal const val TIME_RETRY_LIMIT = 3
        internal const val ACK_TO_START_MS = 250L

        /** ConnectionViewModel scans in a bounded window rather than indefinitely. */
        internal const val SCAN_TIMEOUT_MS = 20_000L
    }

    /** Scanned BLE scale identified by display name and Bluetooth address. */
    data class ScaleBleDevice(val name: String, val address: String)

    // Nullable for the same reason as BleManager: no Bluetooth stack, no crash.
    private val adapter: BluetoothAdapter? =
        DeviceController.appContext.getSystemService(BluetoothManager::class.java)?.adapter
    internal val scanner get() = adapter?.bluetoothLeScanner
    internal var gatt: BluetoothGatt? = null

    // See BleManager: keep connect() idempotent so repeated auto-connect calls don't stack a
    // second GATT client, and drive passive background re-establishment on an unexpected drop.
    private var currentAddress: String? = null
    internal var intentionalDisconnect = false
    /** Address we are passively watching for, if any. */
    internal var watchAddress: String? = null

    internal var weightRatio = DEFAULT_WEIGHT_RATIO
    /** VA scales scale the weight up by this instead of dividing by [weightRatio]. */
    internal var kgWeightRatio = KG_RATIO_KG
    internal var isVaScale = false
    /**
     * Set once the scale has accepted us into one of its eight slots. Null means we are running
     * as the transient visitor, either before the first registration or because all slots were
     * taken.
     */
    internal var vaUserIndex: Int? = null
    /** 0x12 byte[16] bit 5: whether the scale accepts an app-supplied reference weight. */
    internal var supportsIdentifyWeight = false
    internal var scaleType = 0
    private var notifyChar: UUID = CHAR_FFE1
    private var indicateChar: UUID? = null
    /** Config frames and per-measurement acks (FFE3, or FFF2 on Holtek). */
    internal var configChar: UUID = CHAR_FFE3
    /** Time and start frames (FFE4, falling back to [configChar] when absent). */
    internal var bleWriteChar: UUID = CHAR_FFE3
    private var serviceUuid: UUID = SERVICE_FFE0
    /**
     * Holtek firmware exposes the FFF0 family, collapses every write onto FFF2, and waits for its
     * 0x14 hardware-version packet before it will accept the time frame.
     */
    internal var isHoltek = false

    // One outstanding GATT write at a time, drained on onCharacteristicWrite.
    private class Command(val char: UUID, val bytes: ByteArray)
    private val commandQueue = ArrayDeque<Command>()
    private var writing = false
    private val descriptorQueue = ArrayDeque<Pair<BluetoothGattDescriptor, ByteArray>>()
    internal var timeRetries = 0

    // Advertised manufacturer data per address, kept from the scan so that the scale category and
    // the resistance-encryption flag can be resolved once we know which device we are connecting to.
    internal val manufacturerData = HashMap<String, ByteArray>()
    internal var scaleCategory = 0
    internal var useResistanceEncrypt = false

    // For 8-electrode burst reassembly (count/cur at b[6]). burstStarted guards against emitting
    // a reading built from stale channels when the burst's first packet is dropped — BLE
    // notifications are unacknowledged, and a silently wrong body-fat number is worse than none.
    internal var burstStarted = false
    internal var lf20k = 0.0; internal var lf100k = 0.0
    internal var rf20k = 0.0; internal var rf100k = 0.0
    internal var lh20k = 0.0; internal var lh100k = 0.0
    internal var rh20k = 0.0; internal var rh100k = 0.0
    internal var t20k = 0.0; internal var t100k = 0.0

    internal val handler = Handler(Looper.getMainLooper())

    internal val scanTimeout = Runnable {
        stopScan()
    }

    /**
     * The scale ignores the time frame until it is ready for it, so the reference SDK just keeps
     * re-sending until the 0x21 acknowledgement lands (or it gives up after three tries).
     */
    internal val timeRetry = object : Runnable {
        override fun run() {
            if (timeRetries >= TIME_RETRY_LIMIT) return
            timeRetries++
            enqueue(bleWriteChar, buildCmd(CMD_TIME, *timePayload(System.currentTimeMillis())))
            handler.postDelayed(this, TIME_RETRY_MS)
        }
    }

    internal val sendStart = Runnable { enqueue(bleWriteChar, buildCmd(CMD_START)) }

    // Scan, watch, GATT open and teardown live in ScaleBleConnection.kt; the callbacks below
    // are created once and reused so repeated scans do not stack.
    private val scanCallback: ScanCallback by lazy { makeScanCallback() }
    private val watchCallback: ScanCallback by lazy { makeWatchCallback() }

    fun startScan() = startScanNow(scanCallback)

    fun stopScan() = stopScanNow(scanCallback)

    /**
     * [passive] waits for the scale to wake up instead of trying to reach a powered-off device.
     */
    fun connect(address: String, passive: Boolean = false) {
        if (gatt != null && currentAddress == address) return
        close()
        currentAddress = address
        intentionalDisconnect = false
        if (passive) startWatch(address) else openGatt(address)
    }

    private fun startWatch(address: String) = startWatchNow(address, watchCallback)

    internal fun stopWatch() = stopWatchNow(watchCallback)

    private fun openGatt(address: String) {
        // A persisted address can be corrupt/legacy; getRemoteDevice throws on it.
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            Log.error(TAG, "invalid bluetooth address", e)
            null
        } ?: return
        openGattFor(device)
    }

    internal fun openGattFor(device: BluetoothDevice) = openGattNow(device)

    fun disconnect() {
        intentionalDisconnect = true
        currentAddress = null
        stopWatch()
        gatt?.disconnect()
    }

    fun close() = closeGattNow()

    private fun resetPacketState() {
        handler.removeCallbacks(timeRetry)
        handler.removeCallbacks(sendStart)
        commandQueue.clear()
        descriptorQueue.clear()
        writing = false
        timeRetries = 0
        scaleType = 0
        weightRatio = DEFAULT_WEIGHT_RATIO
        // Re-established from the 0x12 info frame and the user handshake on every connection.
        supportsIdentifyWeight = false
        vaUserIndex = null
        resetBurst()
    }

    internal fun resetBurst() {
        burstStarted = false
        lf20k = 0.0; lf100k = 0.0; rf20k = 0.0; rf100k = 0.0
        lh20k = 0.0; lh100k = 0.0; rh20k = 0.0; rh100k = 0.0; t20k = 0.0; t100k = 0.0
    }

    internal val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.dev(TAG, "onConnectionStateChange status=$status newState=$newState")
            DeviceController.runOnMain {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        resetPacketState()
                        DeviceController.scaleLink.value = DeviceController.LinkState.Connected
                        DeviceController.scaleConnectionState.value = "Discovering services..."
                        g.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        DeviceController.scaleLink.value = DeviceController.LinkState.Waiting
                        DeviceController.scaleConnectionState.value = "Disconnected"
                        DeviceController.scaleDevices.clear()
                        resetPacketState()
                        val g2 = gatt
                        gatt = null
                        g2?.let {
                            it.close()
                            refreshCache(it)
                        }
                        val addr = currentAddress
                        if (!intentionalDisconnect && addr != null) {
                            // The scale powers itself off after each weigh-in, so go back to
                            // watching for it rather than treating this as a failure.
                            startWatch(addr)
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            // Everything below mutates state that dispatch() and the handshake runnables also
            // touch, so keep it all on the main thread.
            DeviceController.runOnMain {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    DeviceController.scaleConnectionState.value = "Service discovery failed ($status)"
                    return@runOnMain
                }
                // Prefer FFE0/FFE1, fall back to FFF0/FFF1 if that's what the firmware exposes.
                val svc = g.getService(SERVICE_FFE0) ?: g.getService(SERVICE_FFF0)
                if (svc == null) {
                    DeviceController.scaleConnectionState.value = "Scale service not found"
                    return@runOnMain
                }
                serviceUuid = svc.uuid
                isHoltek = serviceUuid == SERVICE_FFF0
                val holtek = isHoltek
                logGatt(g)
                val ch = svc.getCharacteristic(if (holtek) CHAR_FFF1 else CHAR_FFE1)
                if (ch == null) {
                    DeviceController.scaleConnectionState.value = "Scale notifying char not found"
                    return@runOnMain
                }
                notifyChar = ch.uuid
                configChar = if (holtek) CHAR_FFF2 else CHAR_FFE3
                bleWriteChar = if (!holtek && svc.getCharacteristic(CHAR_FFE4) != null) CHAR_FFE4 else configChar
                Log.dev(
                    TAG,
                    "service=$serviceUuid holtek=$holtek notify=$notifyChar " +
                        "config=$configChar bleWrite=$bleWriteChar",
                )

                descriptorQueue.clear()
                g.setCharacteristicNotification(ch, true)
                val cccd = ch.getDescriptor(CCCD_UUID)
                if (cccd == null) {
                    Log.status(TAG, "notify char $notifyChar has no CCCD; notifications cannot be enabled")
                    DeviceController.scaleConnectionState.value = "Scale CCCD not found"
                } else {
                    descriptorQueue.addLast(cccd to BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                }
                // FFE2 is an indicate characteristic feeding the same decoder; the reference
                // enables it whenever the firmware exposes it.
                indicateChar = null
                if (!holtek) {
                    svc.getCharacteristic(CHAR_FFE2)?.let { ind ->
                        indicateChar = ind.uuid
                        g.setCharacteristicNotification(ind, true)
                        ind.getDescriptor(CCCD_UUID)?.let {
                            descriptorQueue.addLast(it to BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                        }
                    }
                }
                writeNextDescriptor(g)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.dev(TAG, "onDescriptorWrite ${descriptor.characteristic.uuid} status=$status")
            DeviceController.runOnMain {
                if (descriptorQueue.isNotEmpty()) {
                    writeNextDescriptor(g)
                } else {
                    // The scale pushes its 0x12 scale-info unprompted once subscribed; that packet
                    // is what kicks off the config/time/start handshake.
                    DeviceController.scaleConnectionState.value = "Connected — step on scale"
                }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
            Log.dev(TAG, "onCharacteristicWrite ${char.uuid} status=$status")
            DeviceController.runOnMain {
                writing = false
                writeNext()
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            Log.dev(TAG, "<- ${characteristic.uuid.short()} ${value.toHex()}")
            if (characteristic.uuid != notifyChar && characteristic.uuid != indicateChar) return
            if (value.isEmpty()) return
            DeviceController.runOnMain { dispatch(value) }
        }

        // The (gatt, characteristic, value) overload only exists from API 33; below that the
        // platform delivers notifications through this one, so without it nothing arrives on
        // Android 12/12L (minSdk is 31).
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onCharacteristicChanged(g, characteristic, characteristic.value ?: return)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeNextDescriptor(g: BluetoothGatt) {
        val (desc, value) = descriptorQueue.removeFirstOrNull() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(desc, value)
        } else {
            desc.setValue(value)
            g.writeDescriptor(desc)
        }
    }

    internal fun enqueue(char: UUID, bytes: ByteArray) {
        commandQueue.addLast(Command(char, bytes))
        if (!writing) writeNext()
    }

    @Suppress("DEPRECATION")
    private fun writeNext() {
        val g = gatt
        if (g == null || commandQueue.isEmpty()) {
            writing = false
            return
        }
        val cmd = commandQueue.removeFirst()
        val ch = g.getService(serviceUuid)?.getCharacteristic(cmd.char)
        if (ch == null) {
            Log.status(TAG, "write char ${cmd.char} not found; dropping ${cmd.bytes.toHex()}")
            writing = false
            return
        }
        writing = true
        val writeType = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        Log.dev(TAG, "-> ${cmd.char.short()} ${cmd.bytes.toHex()} type=$writeType")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, cmd.bytes, writeType)
        } else {
            ch.setValue(cmd.bytes)
            ch.writeType = writeType
            g.writeCharacteristic(ch)
        }
    }

    /** Dump the discovered GATT table once, so an unexpected layout is visible in a bug report. */
    private fun logGatt(g: BluetoothGatt) {
        for (svc in g.services) {
            Log.dev(TAG, "svc ${svc.uuid.short()}")
            for (c in svc.characteristics) {
                val descriptors = c.descriptors.joinToString(",") { it.uuid.short() }
                Log.dev(TAG, "  chr ${c.uuid.short()} props=0x${c.properties.toString(HEX_RADIX)} desc=[$descriptors]")
            }
        }
    }

    private fun UUID.short(): String = toString().substring(UUID_SHORT_START, UUID_SHORT_END)

    /** CmdBuilder.buildCmd with this connection's scale type; see ScaleBleProtocol.kt. */
    internal fun buildCmd(cmd: Int, vararg payload: Int): ByteArray =
        buildCmdWithType(cmd, scaleType, *payload)

    // Packet dispatch and measurement decoding live in ScaleBleMeasurement.kt as
    // `internal` members of the manager, so this file stays under the function-count limit.

    internal fun refreshCache(g: BluetoothGatt) {
        runCatching { g.javaClass.getMethod("refresh").invoke(g) }
    }
}

internal const val SCALE_SCANNING_STATE = "Scanning scales..."
internal const val SCALE_WAITING_STATE = "Waiting for scale"
