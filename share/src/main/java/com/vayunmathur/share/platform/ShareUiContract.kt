package com.vayunmathur.share.platform

import android.net.Uri
import com.vayunmathur.share.platform.discovery.NearbyDevice
import com.vayunmathur.share.network.transport.Connection

/**
 * UI contract for the Share app.
 *
 * In-app is the **send** flow only: receiving is entirely notification-driven, controlled by a
 * Quick Settings tile, and owned by
 * [com.vayunmathur.share.platform.receive.ShareReceiveController] so it works with no Activity
 * alive.
 *
 * Mirrors the per-repo pattern (see FindFamilyUiContract / MapsUiContract): data-class UiState
 * + actions interface with a Noop object for @Preview.
 */

data class SendUiState(
    val discoveredDevices: List<NearbyDevice> = emptyList(),
    val isScanning: Boolean = false,
    val outgoingUris: List<Uri> = emptyList(),
    val outgoingDisplayNames: List<String> = emptyList(),
    val activeConnection: Connection? = null,
)

/**
 * A file that finished arriving and is staged in app-private storage.
 *
 * [uri] is a `FileProvider` content URI, so it can be handed to another app without a
 * storage permission. The file stays where it is until the user shares or saves it.
 */
data class ReceivedFile(
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val uri: Uri,
)

/** Contract for share actions. */
interface ShareActions {
    fun startScan()
    fun stopScan()
    fun connectToDevice(device: NearbyDevice)
    fun setOutgoingUris(uris: List<Uri>)
    fun clearOutgoing()
    fun disconnect(connection: Connection)

    /** Shared constants and helpers for ShareActions. */
    companion object {
        val Noop = object : ShareActions {
            override fun startScan() {}
            override fun stopScan() {}
            override fun connectToDevice(device: NearbyDevice) {}
            override fun setOutgoingUris(uris: List<Uri>) {}
            override fun clearOutgoing() {}
            override fun disconnect(connection: Connection) {}
        }
    }
}
