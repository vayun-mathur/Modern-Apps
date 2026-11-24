package com.vayunmathur.library.wearlink

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket as BtServerSocket
import android.bluetooth.BluetoothSocket as BtSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * RFCOMM transport over the platform Bluetooth stack, verified against the
 * local SDK (android-37): `BluetoothManager.adapter`,
 * `adapter.bondedDevices`, `device.createRfcommSocketToServiceRecord(uuid)`,
 * `adapter.listenUsingRfcommWithServiceRecord(name, uuid)`. Secure (bonded,
 * encrypted) sockets only — no insecure RFCOMM.
 *
 * The Wear OS watch is already bonded to the phone by the system companion;
 * third-party apps cannot ride that system link, so this is our own RFCOMM
 * service on top of the same bond. No scanning: candidates come from the
 * bonded set, which is also why no BLUETOOTH_SCAN/location is ever needed.
 */
/** Runtime-permission entry for hosting activities (API 31+ runtime model). */
object WearLinkPermissions {
    /** True when WearLink may connect/accept; request [WearLinkNode.REQUIRED_PERMISSIONS] otherwise. */
    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED

    /** Call from an Activity's onCreate; no-ops once granted. */
    fun requestIfNeeded(activity: android.app.Activity) {
        if (!isGranted(activity)) {
            activity.requestPermissions(WearLinkNode.REQUIRED_PERMISSIONS, 0x574C)
        }
    }
}

class AndroidBluetoothTransport(private val context: Context) : WearLinkTransport {

    private val adapter get() = runCatching {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }.getOrNull()

    fun hasConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED

    override fun isAvailable(): Boolean = adapter?.isEnabled == true

    @SuppressLint("MissingPermission") // guarded by hasConnectPermission() below
    override fun bondedPeers(): List<BondedPeer> {
        if (!hasConnectPermission()) return emptyList()
        val bonded = runCatching { adapter?.bondedDevices }.getOrNull() ?: return emptyList()
        return bonded.map { device ->
            @Suppress("MissingPermission")
            BondedPeer(device.address, device.name ?: device.address)
        }
    }

    @Suppress("MissingPermission") // checked by caller via hasConnectPermission / node state
    override fun connect(peer: BondedPeer, serviceUuid: UUID): WearLinkSocket {
        val device = adapter?.getRemoteDevice(peer.address)
            ?: throw IllegalStateException("no bluetooth adapter")
        val socket = device.createRfcommSocketToServiceRecord(serviceUuid)
        // Discovery would fight the connect; we never start it, but cancel a
        // system-started one defensively (cheap, documented in the SDK).
        runCatching { adapter?.cancelDiscovery() }
        socket.connect()
        return BtSocketWrapper(socket, peer.label)
    }

    @Suppress("MissingPermission")
    override fun listen(serviceUuid: UUID, serviceName: String): WearLinkServerSocket {
        val server = adapter?.listenUsingRfcommWithServiceRecord(serviceName, serviceUuid)
            ?: throw IllegalStateException("no bluetooth adapter")
        return BtServerWrapper(server)
    }

    private class BtSocketWrapper(
        private val socket: BtSocket,
        override val peerLabel: String,
    ) : WearLinkSocket {
        override val input: InputStream get() = socket.inputStream
        override val output: OutputStream get() = socket.outputStream
        override fun close() = runCatching { socket.close() }.let { Unit }
    }

    private class BtServerWrapper(private val server: BtServerSocket) : WearLinkServerSocket {
        @Suppress("MissingPermission")
        override fun accept(): WearLinkSocket {
            val socket = server.accept()
            @Suppress("MissingPermission")
            val label = socket.remoteDevice?.name ?: socket.remoteDevice?.address ?: "peer"
            return BtSocketWrapper(socket, label)
        }

        override fun close() = runCatching { server.close() }.let { Unit }
    }
}
