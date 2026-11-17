package com.vayunmathur.findfamily.domain

import java.util.UUID

/**
 * BLE contract shared between the app and the tracker firmware (nRF52 + Qorvo DW3110;
 * the reference build is a DWM3001CDK, see `firmware/findfamily-tracker/`).
 * Documented here so the embedded side has an exact spec.
 *
 * ## Advertising (crowd-finding)
 * A provisioned tracker advertises [SERVICE_UUID] with **service data** laid out as:
 * ```
 * [16B epochId][1B battery%]
 * ```
 * where `epochId = TrackerProtocol.epochId(secret, currentEpoch)` and `battery` is
 * 0..100. The id rotates every [TrackerProtocol.EPOCH_SECONDS]; no static id is ever
 * broadcast.
 *
 * The advertisement carries [SERVICE_UUID] **twice**: once as a Service UUID AD field
 * and once as the UUID of the service data. Both are required — `ScanFilter.setServiceUuid`
 * only matches the former, while `ScanRecord.getServiceData` only reads the latter, so a
 * beacon that omits either is undiscoverable by the scanner in [TrackerBeaconScanner].
 *
 * This AD structure does **not** fit a legacy advertisement: `Service Data — 128-bit
 * UUID` costs `1B length + 1B type + 16B UUID = 18B` of overhead, so with the 17-byte
 * payload above it is 35 bytes against the 31-byte legacy limit. The full 16-byte UUID
 * has to be on air because [TrackerBeaconScanner] reads it back via
 * `ScanRecord.getServiceData(ParcelUuid)`, so no legacy encoding works and the beacon
 * is sent as a **BLE 5 extended advertisement** (secondary PHY 1M, connectable so the
 * phone can still write UWB session params post-bind). Scanners must therefore pass
 * `ScanSettings.Builder().setLegacy(false)`.
 *
 * ## Binding (GATT)
 * An unprovisioned tracker advertises [UNPROVISIONED_SERVICE_UUID] — as a *legacy*
 * advertisement, since `Flags` + a 128-bit service UUID is only 21 bytes and that keeps
 * `TrackerProvisioner.unprovisioned()` working on default (legacy-only) scan settings.
 * It exposes a GATT server with:
 *  - [PROVISION_CHARACTERISTIC_UUID] (write): the phone writes the provisioning blob
 *    ```
 *    [8B trackerUserId BE][32B beaconSecret][8B unixSeconds BE]
 *    ```
 *    ([PROVISION_BLOB_LEN] bytes). Firmware should also accept a
 *    [PROVISION_BLOB_LEN_NO_TIME]-byte write without the trailing timestamp, leaving its
 *    time base unset. The timestamp exists because the tracker has no battery-backed RTC
 *    and cannot otherwise compute `unix_seconds / 900`.
 *    After a successful write the tracker persists these, stops advertising the
 *    unprovisioned service, and begins the rotating beacon above.
 *  - [UWB_SESSION_CHARACTERISTIC_UUID] (write/notify): per-find FiRa session params
 *    for phone-native UWB precision finding (see [TrackerUwbGatt]). The STS key and the
 *    tracker's UWB address are **not** in this write — both ends derive them from the
 *    beacon secret (see [TrackerUwbKeys]).
 *
 * Both characteristics live under [UNPROVISIONED_SERVICE_UUID] for the device's whole
 * life; provisioning only stops that service being *advertised*, so an owner can still
 * connect to a bound tracker and write session params.
 */
object TrackerBle {
    /** Service a provisioned tracker advertises its rotating beacon under. */
    val SERVICE_UUID: UUID = UUID.fromString("6b1d2f00-4b3a-4c7e-9a10-1f2e3d4c5b6a")

    /** Service an unprovisioned (pairing-mode) tracker advertises. */
    val UNPROVISIONED_SERVICE_UUID: UUID = UUID.fromString("6b1d2f01-4b3a-4c7e-9a10-1f2e3d4c5b6a")

    /** GATT characteristic the phone writes the provisioning blob to. */
    val PROVISION_CHARACTERISTIC_UUID: UUID = UUID.fromString("6b1d2f02-4b3a-4c7e-9a10-1f2e3d4c5b6a")

    /** GATT characteristic carrying per-find FiRa session params for UWB ranging. */
    val UWB_SESSION_CHARACTERISTIC_UUID: UUID = UUID.fromString("6b1d2f03-4b3a-4c7e-9a10-1f2e3d4c5b6a")

    /** Length of the provisioning blob: `[8B userId][32B secret][8B unixSeconds]`. */
    const val PROVISION_BLOB_LEN: Int = 8 + TrackerProtocol.SECRET_LEN + 8

    /** Length of the pre-time-sync provisioning blob firmware still accepts. */
    const val PROVISION_BLOB_LEN_NO_TIME: Int = 8 + TrackerProtocol.SECRET_LEN
}

/** One crowd-finding beacon sighting heard by a finder phone. */
data class TrackerSighting(
    /** The 16-byte rotating id from the advertisement; used verbatim as the report key. */
    val epochId: ByteArray,
    /** Battery percent from the advertisement, or -1 when absent. */
    val battery: Int,
    /** Received signal strength (dBm) — a coarse proximity hint. */
    val rssi: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is TrackerSighting && epochId.contentEquals(other.epochId) &&
            battery == other.battery && rssi == other.rssi

    override fun hashCode(): Int = epochId.contentHashCode() * 31 + battery * 31 + rssi
}
