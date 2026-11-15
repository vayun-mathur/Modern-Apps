package com.vayunmathur.share.platform.discovery

import kotlinx.serialization.Serializable

/**
 * A nearby device discovered via mDNS (`_FC9F5ED42C8A._tcp`) or BLE (GATT `0xFEF3`).
 *
 * Produced by [NsdDiscoveryManager] + [BleDiscoveryManager] and consumed by
 * [com.vayunmathur.share.platform.ShareViewModel] for the Send flow's nearby-device list.
 *
 * Everyone-mode only:
 *  - The TCP endpoint is the resolved host/port from the mDNS browse (`ServerSocket(0)` on
 *    WIFI_LAN). Only the mDNS leg can supply it, so a BLE-only entry is not connectable.
 *  - [endpointId] is the peer's 4-character Nearby endpoint id from the mDNS
 *    `WifiLanServiceInfo`, or the MAC address for a BLE-only entry.
 *  - [endpointName] is the name inside the peer's endpoint-info blob. A device advertising
 *    in contact-only mode publishes none, so those fall back to the endpoint id or the
 *    Bluetooth name.
 *  - [extra] carries the BLE advertisement's `data` field as hex, when scanned.
 */
@Serializable
data class NearbyDevice(
    val endpointId: String,
    val endpointName: String,
    val serviceId: String? = null,
    val serviceName: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val source: DiscoverySource = DiscoverySource.Nsd,
    val extra: String? = null,
)

/** Kinds of discovery source. */
enum class DiscoverySource { Nsd, Ble, Both, AirDrop }
