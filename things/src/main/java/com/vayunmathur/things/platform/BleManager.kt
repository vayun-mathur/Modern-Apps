package com.vayunmathur.things.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.vayunmathur.library.log.Log
import java.util.UUID

/** One drained drink log read from the bottle's offline history (or live). */
data class HydrationReading(
    val amountMl: Int,
    val epochMillis: Long,
    val tds: Int?,
    val tempC: Int?,
)

/** Live sensor snapshot from the bottle. Fields are null until the bottle reports them. */
data class BottleStatus(
    val tempC: Int?,
    val tds: Int?,
    val batteryPct: Int?,
    val charging: Boolean,
    val volumePct: Int?,
)

/**
 * Speaks the WaterH bottle's BLE protocol. The bottle talks over two GATT services: it
 * notifies on FFE4 and accepts commands on FFE9. Because a GATT stack allows only one
 * outstanding write, commands are queued and drained on [onCharacteristicWrite].
 *
 * Everything stays on-device: we reimplement only the local BLE half of the official app
 * and never touch its cloud backend.
 */
@SuppressLint("MissingPermission")
class BleManager {
    companion object {
        internal const val TAG = "WaterHBle"
        val NOTIFY_SERVICE_UUID: UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        val NOTIFY_CHAR_UUID: UUID = UUID.fromString("0000FFE4-0000-1000-8000-00805F9B34FB")
        val WRITE_SERVICE_UUID: UUID = UUID.fromString("0000FFE5-0000-1000-8000-00805F9B34FB")
        val WRITE_CHAR_UUID: UUID = UUID.fromString("0000FFE9-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Command to request the bottle's water-log history.
        private const val CMD_REQUEST_LOGS = "4754000106"
        // Command to request the full bottle-data snapshot.
        private const val CMD_REQUEST_DATA = "47540001ff"
        // Set language (final step of the sync handshake).
        private const val CMD_SET_LANGUAGE = "50540003021b01"
        // First-time setup: take the bottle out of factory mode, then start registration.
        // Mirrors the official app's registerDevice() path (requestExitFactoryMode +
        // requestRegistration); this is what lights the bottle's blue LED and pairs it.
        private const val CMD_EXIT_FACTORY_MODE = "5054000302f400"
        private const val CMD_REQUEST_REGISTRATION = "50540003021c01"
        // Sent after the user confirms on the bottle (registration "Confirmed"); the bottle then
        // finalizes and replies "registration successful / clear data successful".
        private const val CMD_CLEAR_OFFLINE_DATA = "50540003021c05"

        // The official app never writes back-to-back: BottleConnection.sendBleRequest delays every
        // command, 1s for the request/sync ones and 500ms for the rest. The bottle ignores writes
        // issued too soon after the CCCD write, so these spacings are load-bearing.
        private const val COMMAND_DELAY_MS = 500L
        private const val REQUEST_DELAY_MS = 1000L

        // The official app scans in a bounded 20s window (ConnectionViewModel.scanFor20Seconds)
        // rather than leaving the radio running until something is found.
        private const val SCAN_TIMEOUT_MS = 20_000L
    }

    data class BleDevice(val name: String, val address: String)

    private val bluetoothManager = DeviceController.appContext.getSystemService(BluetoothManager::class.java)
    private val adapter = bluetoothManager.adapter
    private val scanner get() = adapter.bluetoothLeScanner
    private var gatt: BluetoothGatt? = null

    // The device we want to stay connected to, and whether the last disconnect was user-initiated.
    // Used to keep connect() idempotent (so repeated auto-connect calls from the service don't
    // stack a second GATT client) and to drive passive background re-establishment.
    private var currentAddress: String? = null
    private var intentionalDisconnect = false
    // When true, run the one-time registration handshake on connect (blue LED + button press)
    // before requesting data, matching the official app's first-time setup path.
    private var pendingRegistration = false
    // Guards the one-shot clear-offline-data command sent after the button press is confirmed
    // (the bottle re-sends "Confirmed" several times).
    private var registrationClearSent = false

    // Command queue: one outstanding write at a time, drained on onCharacteristicWrite.
    private class Command(val hex: String, val delayMs: Long)
    private val commandQueue = ArrayDeque<Command>()
    private var writing = false

    // Live sensor state, merged across partial RT updates and emitted as a BottleStatus.
    private var curTemp: Int? = null
    private var curTds: Int? = null
    private var curBattery: Int? = null
    private var curCharging = false
    private var curVolumePct: Int? = null

    // Water-log accumulation across PT packets.
    internal var expectedLogs = 0
    internal var parsedRecords = 0
    internal val collected = ArrayList<HydrationReading>()

    private val handler = Handler(Looper.getMainLooper())

    private val scanTimeout = Runnable {
        stopScan()
        if (DeviceController.connectionState.value == SCANNING_STATE) {
            DeviceController.connectionState.value = "Disconnected"
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            if (!name.startsWith("WaterH", ignoreCase = true)) return
            val addr = result.device.address
            if (DeviceController.discoveredDevices.none { it.address == addr }) {
                DeviceController.discoveredDevices.add(BleDevice(name, addr))
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.error(TAG, "onScanFailed error=$errorCode")
            DeviceController.runOnMain {
                stopScan()
                DeviceController.connectionState.value = "Scan failed ($errorCode)"
            }
        }
    }

    fun startScan() {
        DeviceController.discoveredDevices.clear()
        DeviceController.scanning.value = true
        // WaterH advertises no service UUID; discover by name instead of a ScanFilter.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(null, settings, scanCallback)
        DeviceController.connectionState.value = SCANNING_STATE
        handler.removeCallbacks(scanTimeout)
        handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        scanner?.stopScan(scanCallback)
        DeviceController.scanning.value = false
    }

    @Suppress("DEPRECATION")
    fun connect(address: String, register: Boolean = false) {
        // Idempotent: if we already hold a GATT client for this device, don't open a second one.
        // Each connectGatt registers a new client interface with the Bluetooth stack; stacking
        // them leaks interfaces and confuses the bottle, which only services its first session
        // (its command char then silently ignores writes → the app hangs on "Waiting for data").
        if (gatt != null && currentAddress == address) return
        // Release any stale/previous client before opening a fresh one.
        close()
        currentAddress = address
        intentionalDisconnect = false
        pendingRegistration = register
        registrationClearSent = false
        openGatt(address, autoConnect = false)
    }

    private fun openGatt(address: String, autoConnect: Boolean) {
        stopScan()
        DeviceController.bottleLink.value = DeviceController.LinkState.Connecting
        DeviceController.connectionState.value = "Connecting..."
        val device = adapter.getRemoteDevice(address)
        gatt = device.connectGatt(DeviceController.appContext, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        intentionalDisconnect = true
        currentAddress = null
        gatt?.disconnect()
    }

    fun close() {
        gatt?.let {
            it.close()
            // Clear the GATT service cache so a later reconnect re-reads services fresh.
            refreshDeviceCache(it)
        }
        gatt = null
    }

    private fun resetState() {
        commandQueue.clear()
        writing = false
        curTemp = null
        curTds = null
        curBattery = null
        curCharging = false
        curVolumePct = null
        expectedLogs = 0
        parsedRecords = 0
        collected.clear()
    }

    internal fun enqueueCommand(hex: String, delayMs: Long = COMMAND_DELAY_MS) {
        DeviceController.runOnMain {
            commandQueue.addLast(Command(hex, delayMs))
            if (!writing) writeNext()
        }
    }

    private fun writeNext() {
        val cmd = commandQueue.removeFirstOrNull()
        if (cmd == null) {
            writing = false
            return
        }
        writing = true
        handler.postDelayed({ performWrite(cmd.hex) }, cmd.delayMs)
    }

    @Suppress("DEPRECATION")
    private fun performWrite(hex: String) {
        val g = gatt ?: run { writing = false; return }
        val service = g.getService(WRITE_SERVICE_UUID)
        if (service == null) {
            Log.status(TAG, "writeNext: WRITE service $WRITE_SERVICE_UUID not found; dropping $hex")
            writing = false
            return
        }
        val char = service.getCharacteristic(WRITE_CHAR_UUID)
        if (char == null) {
            Log.status(TAG, "writeNext: WRITE char $WRITE_CHAR_UUID not found; dropping $hex")
            writing = false
            return
        }
        val bytes = hexToByteArray(hex)
        // FFE9 on this bottle is write-without-response (properties=0x4). Match the write type to
        // the characteristic's actual properties: on Android 13+ a write-with-response issued to a
        // no-response-only characteristic is silently dropped at the ATT layer (it never leaves the
        // phone), so the command must go out as WRITE_TYPE_NO_RESPONSE to reach the bottle.
        val writeType = if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        Log.dev(TAG, "-> write $hex (props=0x${char.properties.toString(BOTTLE_HEX_RADIX)} type=$writeType)")
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(char, bytes, writeType)
        } else {
            char.setValue(bytes)
            char.writeType = writeType
            if (g.writeCharacteristic(char)) WRITE_OK else WRITE_REJECTED
        }
        if (result != 0) {
            Log.status(TAG, "write of $hex rejected by the stack (code $result)")
            writing = false
            writeNext()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.dev(TAG, "onConnectionStateChange status=$status newState=$newState")
            DeviceController.runOnMain {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        resetState()
                        DeviceController.bottleLink.value = DeviceController.LinkState.Connected
                        DeviceController.connectionState.value = "Connected"
                        g.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        DeviceController.bottleLink.value = DeviceController.LinkState.Waiting
                        DeviceController.connectionState.value = "Disconnected"
                        DeviceController.discoveredDevices.clear()
                        resetState()
                        // Release this GATT client so its interface doesn't leak in the stack.
                        val g2 = gatt
                        gatt = null
                        g2?.let {
                            it.close()
                            refreshDeviceCache(it)
                        }
                        // Passive background re-establishment: if the link dropped on its own
                        // (device slept / went out of range), reconnect with autoConnect=true so
                        // the platform silently waits for it to return instead of churning.
                        val addr = currentAddress
                        if (!intentionalDisconnect && addr != null) {
                            openGatt(addr, autoConnect = true)
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.dev(TAG, "onServicesDiscovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = g.getService(NOTIFY_SERVICE_UUID)
            if (service == null) {
                Log.status(TAG, "NOTIFY service $NOTIFY_SERVICE_UUID not found; services=${g.services.map { it.uuid }}")
                return
            }
            val char = service.getCharacteristic(NOTIFY_CHAR_UUID)
            if (char == null) {
                Log.status(TAG, "NOTIFY char $NOTIFY_CHAR_UUID not found")
                return
            }
            g.setCharacteristicNotification(char, true)
            char.getDescriptor(CCCD_UUID)?.let {
                // The (descriptor, value) overload is API 33+; below that the
                // value has to be staged on the descriptor first.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    it.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(it)
                }
            } ?: Log.status(TAG, "CCCD $CCCD_UUID not found on notify char")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (pendingRegistration) {
                // First-time setup: exit factory mode + start registration (blue LED, button press).
                Log.dev(TAG, "onDescriptorWrite status=$status; starting registration")
                enqueueCommand(CMD_EXIT_FACTORY_MODE, REQUEST_DELAY_MS)
                enqueueCommand(CMD_REQUEST_REGISTRATION, REQUEST_DELAY_MS)
            } else {
                // Notifications are on; kick off the handshake by asking for full bottle data.
                Log.dev(TAG, "onDescriptorWrite status=$status; requesting bottle data")
                enqueueCommand(CMD_REQUEST_DATA, REQUEST_DELAY_MS)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
            Log.dev(TAG, "onCharacteristicWrite ${char.uuid} status=$status queued=${commandQueue.size}")
            DeviceController.runOnMain {
                writing = false
                if (commandQueue.isNotEmpty()) writeNext()
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            char: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (char.uuid != NOTIFY_CHAR_UUID || value.size < 2) return
            Log.dev(TAG, "<- notify ${value.toHex()}")
            DeviceController.runOnMain { dispatch(value) }
        }

        // The (gatt, characteristic, value) overload only exists from API 33; below that the
        // platform delivers notifications through this one, so without it nothing arrives on
        // Android 12/12L (minSdk is 31).
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, char: BluetoothGattCharacteristic) {
            onCharacteristicChanged(g, char, char.value ?: return)
        }
    }

    // Internal so tests can feed wire packets without a radio; production only
    // calls this from the GATT notification callback.
    internal fun dispatch(value: ByteArray) {
        val b0 = value[0].toInt() and BYTE_MASK
        val b1 = value[1].toInt() and BYTE_MASK
        when {
            // RP snapshot / ack (bottle -> phone data reports).
            b0 == RP_MARKER_B0 && b1 == RP_MARKER_B1 -> handleRp(value)
            // RT incremental sensor update.
            b0 == RP_MARKER_B0 && b1 == RT_MARKER_B1 -> handleRt(value)
            // PT water-log stream (first packet).
            b0 == PT_MARKER_B0 && b1 == PT_MARKER_B1 -> handlePtFirst(value)
            // Water-log continuation packet.
            b1 == PT_CONTINUATION_B1 -> handlePtContinuation(value)
            else -> Log.status(TAG, "dispatch: unhandled packet ${value.toHex()}")
        }
    }

    private fun handleRp(value: ByteArray) {
        if (value.size < RP_MIN_SIZE) return
        val b2 = value[2].toInt() and BYTE_MASK
        val b3 = value[3].toInt() and BYTE_MASK
        val b5 = value[RP_SELECTOR_INDEX].toInt() and BYTE_MASK

        // Response to a written text/LED signature (BleGattCallback → onSignatureResponse). It
        // has nothing to do with registration, and this app never writes a signature.
        if (b5 == RP_SIGNATURE_RESPONSE) {
            Log.dev(TAG, "RP: signature response, ignored")
            return
        }

        if (handleRpSnapshot(value, b2, b3)) return
        if (handleRpSyncAck(b2, b3)) return
        if (handleRpLogAvailability(value, b5)) return
        handleRpRegistration(value, b5)
    }

    /** Full Vita / Boost snapshots; true when the packet was one of them. */
    private fun handleRpSnapshot(value: ByteArray, b2: Int, b3: Int): Boolean {
        if (b2 == RP_SNAPSHOT_B2 && b3 == RP_VITA_B3 && value.size > RP_VITA_MIN_SIZE) {
            // Vita full snapshot.
            curTemp = value[VITA_TEMP_INDEX].toInt()
            curBattery = value[VITA_BATTERY_INDEX].toInt() and BYTE_MASK
            curCharging = value[VITA_CHARGING_INDEX].toInt() and BYTE_MASK in CHARGING_STATES
            curTds = beInt(value, VITA_TDS_OFFSET)
            curVolumePct = volumePct(beInt(value, VITA_VOLUME_OFFSET))
            logRpSnapshot("Vita")
            emitStatus()
            enqueueSyncAndLanguage()
            return true
        }
        if (b2 == RP_SNAPSHOT_B2 && b3 == RP_BOOST_B3 && value.size > RP_BOOST_MIN_SIZE) {
            // Boost full snapshot (temp/tds/volume arrive later via RT).
            curBattery = value[BOOST_BATTERY_INDEX].toInt() and BYTE_MASK
            curCharging = value[BOOST_CHARGING_INDEX].toInt() and BYTE_MASK in CHARGING_STATES
            logRpSnapshot("Boost")
            emitStatus()
            enqueueSyncAndLanguage()
            return true
        }
        return false
    }

    private fun logRpSnapshot(kind: String) {
        Log.dev(
            TAG,
            "RP $kind: temp=$curTemp batt=$curBattery charging=$curCharging " +
                "tds=$curTds vol%=$curVolumePct",
        )
    }

    /** Sync setting acknowledged; now request the water logs. True when handled. */
    private fun handleRpSyncAck(b2: Int, b3: Int): Boolean {
        if (b2 == RP_SNAPSHOT_B2 && b3 == RP_SYNC_ACK_B3) {
            Log.dev(TAG, "RP: sync ack → request logs")
            enqueueCommand(CMD_REQUEST_LOGS, REQUEST_DELAY_MS)
            return true
        }
        return false
    }

    // Water-log availability flag: value[6]==1 means PT packets follow, 0 means nothing to
    // drain (the official app's onNoNewWaterLog). True when handled.
    private fun handleRpLogAvailability(value: ByteArray, b5: Int): Boolean {
        if (b5 != RP_LOG_AVAILABILITY) return false
        val available = if (value.size > RP_FLAG_INDEX) value[RP_FLAG_INDEX].toInt() and BYTE_MASK else -1
        Log.dev(TAG, "RP: water-log availability=$available")
        if (available == 0) {
            expectedLogs = 0
            parsedRecords = 0
            collected.clear()
        }
        return true
    }

    // Registration state: value[6]==2 = user must press the bottle button; ==6 = done.
    private fun handleRpRegistration(value: ByteArray, b5: Int) {
        if (b5 != RP_REGISTRATION_STATE || !pendingRegistration) return
        val step = if (value.size > RP_FLAG_INDEX) value[RP_FLAG_INDEX].toInt() and BYTE_MASK else -1
        Log.dev(TAG, "RP: registration step=$step")
        when (step) {
            RP_REG_PRESS_BUTTON -> DeviceController.connectionState.value = "Press the bottle button"
            RP_REG_DONE -> {
                // Registration successful (and offline data cleared). Leave registration mode
                // and proceed to the normal data flow so status/logs start syncing.
                Log.dev(TAG, "RP: registration successful → requesting bottle data")
                pendingRegistration = false
                DeviceController.connectionState.value = "Connected"
                enqueueCommand(CMD_REQUEST_DATA, REQUEST_DELAY_MS)
            }
        }
    }

    private fun handleRt(value: ByteArray) {
        if (value.size < RT_MIN_SIZE) return
        when (val sel = value[RT_SELECTOR_INDEX].toInt() and BYTE_MASK) {
            RT_TEMP_SELECTOR -> curTemp = value[RT_VALUE_INDEX].toInt()
            RT_BATTERY_SELECTOR -> curBattery = value[RT_VALUE_INDEX].toInt() and BYTE_MASK
            RT_CHARGING_SELECTOR -> curCharging = value[RT_VALUE_INDEX].toInt() and BYTE_MASK in CHARGING_STATES
            RT_VOLUME_SELECTOR -> handleRtVolume(value)
            RT_TDS_SELECTOR -> handleRtTds(value)
            RT_REGISTRATION_SELECTOR -> {
                handleRtRegistration(value)
                return
            }
            RT_RECALIBRATE_SELECTOR -> {
                Log.dev(TAG, "RT: recalibrate result=${value[RT_VALUE_INDEX].toInt() and BYTE_MASK}")
                return
            }
            else -> {
                Log.dev(TAG, "RT: unhandled selector=0x${sel.toString(BOTTLE_HEX_RADIX)}")
                return
            }
        }
        logRtState(value)
        emitStatus()
    }

    private fun logRtState(value: ByteArray) {
        Log.dev(
            TAG,
            "RT sel=0x${(value[RT_SELECTOR_INDEX].toInt() and BYTE_MASK).toString(BOTTLE_HEX_RADIX)} " +
                "→ temp=$curTemp batt=$curBattery charging=$curCharging tds=$curTds vol%=$curVolumePct",
        )
    }

    // Volume changed (the user drank). The bottle reports the new fill level here;
    // offline drink logs are drained separately during the sync handshake.
    private fun handleRtVolume(value: ByteArray) {
        if (value.size < RT_WORD_MIN_SIZE) return
        curVolumePct = volumePct(beInt(value, RT_VOLUME_OFFSET))
    }

    private fun handleRtTds(value: ByteArray) {
        if (value.size < RT_WORD_MIN_SIZE) return
        curTds = beInt(value, RT_TDS_OFFSET)
    }

    // Registration: user confirmed on the bottle (6==3) or it failed (6==4).
    // Ignore once registration is done — the bottle keeps re-sending "confirmed".
    private fun handleRtRegistration(value: ByteArray) {
        if (!pendingRegistration) return
        val result = value[RT_VALUE_INDEX].toInt() and BYTE_MASK
        Log.dev(TAG, "RT: registration result=$result")
        when (result) {
            RT_REG_CONFIRMED -> {
                DeviceController.connectionState.value = "Registering…"
                // Finalize registration: the bottle replies with "successful" (RP 1C/06),
                // which then proceeds to the normal data flow. Send once (it repeats 03).
                if (!registrationClearSent) {
                    registrationClearSent = true
                    enqueueCommand(CMD_CLEAR_OFFLINE_DATA, REQUEST_DELAY_MS)
                }
            }
            RT_REG_FAILED -> DeviceController.connectionState.value = "Registration failed"
        }
    }

    private fun enqueueSyncAndLanguage() {
        enqueueCommand(buildSyncCommand(), REQUEST_DELAY_MS)
        enqueueCommand(CMD_SET_LANGUAGE)
    }

    private fun emitStatus() {
        DeviceController.onBottleStatus(BottleStatus(curTemp, curTds, curBattery, curCharging, curVolumePct))
    }

    private fun refreshDeviceCache(g: BluetoothGatt) {
        // The GATT cache refresh is a hidden API; WaterH uses it to avoid stale caches.
        runCatching {
            val refresh = g.javaClass.getMethod("refresh")
            refresh.invoke(g)
        }
    }
}

private const val SCANNING_STATE = "Scanning..."
