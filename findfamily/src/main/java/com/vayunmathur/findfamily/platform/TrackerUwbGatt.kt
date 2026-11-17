package com.vayunmathur.findfamily.platform
import com.vayunmathur.findfamily.domain.TrackerBle
import com.vayunmathur.findfamily.domain.TrackerUwbKeys

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.vayunmathur.library.log.Log
import androidx.annotation.RequiresApi
import com.vayunmathur.findfamily.platform.RangingSample
import com.vayunmathur.findfamily.platform.UwbController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Phone-native **FiRa UWB precision finding** to a bound tracker, over BLE GATT.
 *
 * This is the owner-only "point me to it" last-few-meters step. The design reuses
 * the existing FiRa ranging stack unchanged (`UwbController.openController()` mints
 * the params; `UwbController.stream()` yields distance + AoA); the ONLY thing that
 * differs from the phone-to-phone flow is the transport of the handshake: params go
 * to the tracker over [TrackerBle.UWB_SESSION_CHARACTERISTIC_UUID] instead of the
 * WebSocket `UwbEnvelope`.
 *
 * The phone is the **controller/initiator** and the tracker the controlee/responder.
 * Neither the STS key nor the tracker's UWB address is transmitted — both sides derive
 * them from the bind-time beacon secret ([TrackerUwbKeys]), so the unbonded GATT link
 * carries nothing an attacker could use to range against someone else's tracker.
 */
object TrackerUwbGatt {

    private const val TAG = "TrackerUwbGatt"

    /** Offset of the 4-byte big-endian session id in the session-params frame. */
    private const val SESSION_ID_OFFSET = 2

    /** Length of the big-endian session id. */
    private const val SESSION_ID_LEN = 4

    /** Offset of the channel byte in the session-params frame. */
    private const val CHANNEL_OFFSET = 6

    /** Offset of the preamble byte in the session-params frame. */
    private const val PREAMBLE_OFFSET = 7

    /** Shift of the most significant byte in the big-endian session id. */
    private const val SESSION_ID_SHIFT_MSB = 24

    /** Bits per byte, for big-endian byte-packing shifts. */
    private const val BITS_PER_BYTE = 8

    /** FiRa session params handed to the tracker for one ranging session. */
    data class SessionParams(
        val localAddress: ByteArray,   // phone (controller) 2-byte MAC
        val sessionId: Int,
        val channelNumber: Int,
        val preambleIndex: Int,
        // The STS/session key is derived from the bind-time beacon secret on both
        // ends, so it is NOT sent over the air per-find (only channel/slot are).
    )

    /**
     * Encode the per-find params written to the tracker's UWB session characteristic:
     * `[2B localAddress][4B sessionId BE][1B channel][1B preamble]`.
     */
    fun encodeSessionParams(p: SessionParams): ByteArray {
        require(p.localAddress.size == 2) { "localAddress must be 2 bytes" }
        val out = ByteArray(2 + 4 + 1 + 1)
        p.localAddress.copyInto(out, 0)
        for (i in 0 until SESSION_ID_LEN) {
            out[SESSION_ID_OFFSET + i] =
                (p.sessionId ushr (SESSION_ID_SHIFT_MSB - i * BITS_PER_BYTE)).toByte()
        }
        out[CHANNEL_OFFSET] = p.channelNumber.toByte()
        out[PREAMBLE_OFFSET] = p.preambleIndex.toByte()
        return out
    }

    /**
     * Begin a UWB ranging session to the bound tracker at [bleAddress], whose beacon
     * [secret] the owner holds. Opens GATT, writes [encodeSessionParams] so the tracker
     * starts a matching FiRa responder session, and returns the ranging stream.
     *
     * Returns null if the tracker can't be reached or the write fails. [ctrl] is
     * single-use — `UwbController.stop()` shuts down its executor — so pass a fresh
     * instance per find.
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    suspend fun startRanging(
        context: Context,
        ctrl: UwbController,
        bleAddress: String,
        secret: ByteArray,
    ): Flow<RangingSample>? {
        val info = ctrl.openController().getOrElse {
            Log.error(TAG, "openController failed", it)
            return null
        }
        val params = SessionParams(
            localAddress = info.localAddress,
            sessionId = info.sessionId,
            channelNumber = info.channelNumber,
            preambleIndex = info.preambleIndex,
        )
        if (!writeSessionParams(context, bleAddress, encodeSessionParams(params))) {
            Log.status(TAG, "could not hand session params to tracker at $bleAddress")
            return null
        }
        // Both ends derive these from the beacon secret rather than exchanging them.
        return ctrl.stream(
            role = UwbController.Role.Initiator,
            localAddress = info.localAddress,
            peerAddress = TrackerUwbKeys.uwbAddress(secret),
            sessionId = info.sessionId,
            sessionKey = TrackerUwbKeys.stsKey(secret, info.sessionId),
            channelNumber = info.channelNumber,
            preambleIndex = info.preambleIndex,
        )
    }

    /**
     * Connect to the tracker at [bleAddress] and write [value] to
     * [TrackerBle.UWB_SESSION_CHARACTERISTIC_UUID]. Returns true iff the write reports
     * success. Mirrors [TrackerProvisioner]'s GATT round-trip; failures surface as false
     * rather than throwing.
     */
    @SuppressLint("MissingPermission")
    // Broad catch is deliberate: GATT calls throw varied runtime exceptions; failure must return false, not crash.
    @Suppress("DEPRECATION", "TooGenericExceptionCaught")
    private suspend fun writeSessionParams(
        context: Context,
        bleAddress: String,
        value: ByteArray,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        val device = try {
            /*
             * getRemoteLeDevice with an explicit RANDOM address type, not getRemoteDevice.
             *
             * The tracker advertises a Zephyr static random identity address, and
             * getRemoteDevice(String) assumes ADDRESS_TYPE_PUBLIC — connecting with the
             * wrong type never reaches the peer and fails with GATT status 147 after a
             * 30-second timeout. Binding is unaffected because TrackerProvisioner gets its
             * BluetoothDevice from a ScanResult, which already carries the right type; this
             * is the only path that rebuilds a device from a stored address string.
             */
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                adapter?.getRemoteLeDevice(bleAddress, BluetoothDevice.ADDRESS_TYPE_RANDOM)
            } else {
                // Pre-33 fallback: no address-type-aware factory exists, so this assumes a
                // public address; random-address trackers require API 33+ (see comment above).
                adapter?.getRemoteDevice(bleAddress)
            }
        } catch (e: Exception) {
            Log.status(TAG, "getRemoteLeDevice($bleAddress) failed", e); null
        }
        if (device == null) { cont.resume(false); return@suspendCancellableCoroutine }

        val resumed = AtomicBoolean(false)
        var gatt: BluetoothGatt? = null
        fun finish(result: Boolean) {
            if (resumed.compareAndSet(false, true)) {
                runCatching { gatt?.disconnect() }
                runCatching { gatt?.close() }
                if (cont.isActive) cont.resume(result)
            }
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.status(TAG, "connected to tracker (status=$status); discovering services")
                    runCatching { g.discoverServices() }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // status 133 is the catch-all GATT error, and normally means the
                    // connection was never established rather than that it dropped.
                    Log.status(TAG, "disconnected before the params write completed (status=$status)")
                    finish(false)
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.status(TAG, "service discovery failed: $status")
                    finish(false); return
                }
                val service = g.getService(TrackerBle.UNPROVISIONED_SERVICE_UUID)
                if (service == null) {
                    Log.status(TAG, "service ${TrackerBle.UNPROVISIONED_SERVICE_UUID} not found; " +
                        "discovered=${g.services.map { it.uuid }}")
                    finish(false); return
                }
                val ch = service.getCharacteristic(TrackerBle.UWB_SESSION_CHARACTERISTIC_UUID)
                if (ch == null) {
                    Log.status(TAG, "UWB characteristic not found; service has " +
                        "${service.characteristics.map { it.uuid }}")
                    finish(false); return
                }
                val ok = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeCharacteristic(
                            ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        ) == BluetoothStatusCodes.SUCCESS
                    } else {
                        ch.value = value
                        g.writeCharacteristic(ch)
                    }
                } catch (e: Exception) {
                    Log.status(TAG, "writeCharacteristic failed", e); false
                }
                if (!ok) finish(false)
            }

            @Deprecated("compat shim for API < 33")
            override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                Log.status(TAG, "session params write completed with status=$status")
                finish(status == BluetoothGatt.GATT_SUCCESS)
            }
        }
        Log.status(TAG, "connecting to tracker at $bleAddress to hand over session params")
        gatt = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Log.status(TAG, "connectGatt failed", e); null
        }
        if (gatt == null) { finish(false); return@suspendCancellableCoroutine }

        cont.invokeOnCancellation { finish(false) }
    }
}
