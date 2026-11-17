package com.vayunmathur.share.platform

import android.Manifest
import android.os.Build

/**
 * Runtime permissions for the Share module (mirrors AndroidManifest.xml).
 *
 * Deliberately short. `:share` asks for nothing it can avoid:
 *  - **no storage or media permissions** — files to send are picked with `ACTION_OPEN_DOCUMENT`
 *    and saved with `ACTION_CREATE_DOCUMENT`, each of which grants access to exactly the URI the
 *    user chose; received files live in app-private storage and leave through a `FileProvider`.
 *  - **no location** — `minSdk` is 31, so `neverForLocation` on `BLUETOOTH_SCAN` covers scanning
 *    outright; the pre-Android-12 classic-scan fallback that needed `ACCESS_FINE_LOCATION`
 *    cannot be reached.
 *  - **no `BLUETOOTH_CONNECT`** — the peer's name comes from its advertisement, never from
 *    `BluetoothDevice.getName()`.
 *
 * `POST_NOTIFICATIONS` is not optional for receiving: notifications *are* the receive UI, so
 * [com.vayunmathur.share.platform.receive.ShareReceiveTileService] refuses to turn receiving on
 * without it.
 */
object SharePermissions {
    /** Needed to advertise for discovery (and so for Quick Share visibility). */
    val BLE_ADVERTISE = Manifest.permission.BLUETOOTH_ADVERTISE
    val BLE_SCAN = Manifest.permission.BLUETOOTH_SCAN

    /** Wi-Fi Direct / Hotspot transport (Android 13+ shows a dedicated dialog). */
    val NEARBY_WIFI =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else null

    /**
     * Local Network Protections (Android 16+). Gates mDNS *and* every LAN socket, so
     * without it `NsdManager.registerService` throws
     * `SecurityException("Missing local network permission")` and `:share` is invisible
     * over WIFI_LAN — the only medium it can accept a transfer on.
     */
    val LOCAL_NETWORK =
        if (Build.VERSION.SDK_INT >= 37) Manifest.permission.ACCESS_LOCAL_NETWORK else null

    /** Foreground-service and transfer notifications (Android 13+). */
    val POST_NOTIFICATIONS = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null

    /** Scanning for peers to send to. Advertising is not needed to send. */
    fun sendFlowPermissions(): Array<String> = buildList {
        add(BLE_SCAN)
        NEARBY_WIFI?.let { add(it) }
        LOCAL_NETWORK?.let { add(it) }
        POST_NOTIFICATIONS?.let { add(it) }
    }.toTypedArray()

    /** Advertising so peers can find us, plus the notifications that are the receive UI. */
    fun receiveFlowPermissions(): Array<String> = buildList {
        add(BLE_ADVERTISE)
        NEARBY_WIFI?.let { add(it) }
        LOCAL_NETWORK?.let { add(it) }
        POST_NOTIFICATIONS?.let { add(it) }
    }.toTypedArray()

    fun allSharePermissions(): Array<String> = buildList {
        add(BLE_ADVERTISE)
        add(BLE_SCAN)
        NEARBY_WIFI?.let { add(it) }
        LOCAL_NETWORK?.let { add(it) }
        POST_NOTIFICATIONS?.let { add(it) }
    }.toTypedArray()
}
