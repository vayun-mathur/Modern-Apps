package com.vayunmathur.findfamily.platform
import com.vayunmathur.findfamily.domain.PoweredOffProtocol

import android.bluetooth.le.ScanFilter
import android.os.ParcelUuid
import java.util.UUID

/**
 * The on-air shape of a powered-off beacon, and the one part of this feature that is **assumed
 * rather than verified**. Read this before trusting the scanner.
 *
 * ## Why it cannot be read out of the source
 * Powered-off beacons are emitted after Android is gone, by the Bluetooth controller itself. All
 * the HAL does is hand the controller a list of 20-byte keys and some advertising parameters
 * over vendor HCI opcode `0xfd62`
 * (`hardware/interfaces/bluetooth/bluetooth_hal/extensions/finder/bluetooth_finder_handler.cc`).
 * It sets the advertising interval (2000ms), the TX power (0x0A), the key rotation period
 * (1024s) and a 20s start delay — and nothing else. Closed controller firmware composes the
 * actual advertisement. There is no service UUID, no frame type and no payload assembly anywhere
 * in the platform tree, so the byte layout below is **not confirmed by anything we can read**.
 * Confirming it needs an on-air capture from a powered-off device.
 *
 * ## What is assumed
 * Service data under one of [CANDIDATE_SERVICE_UUIDS], laid out as:
 * ```
 * [0]      frame type 0x40
 * [1..21]  20-byte EID
 * [21]     hashed flags (optional, ignored)
 * ```
 * Two service UUIDs are matched because the evidence points at one and does not rule out the
 * other, and guessing wrong costs everything: the symptom of a wrong UUID is a scanner that
 * matches nothing, silently, forever. [FAST_PAIR_UUID] (0xFE2C) is the stronger candidate —
 * Find My Device Network is built on top of Google Fast Pair, and 0xFE2C is the only relevant
 * service constant anywhere in the tree
 * (`packages/modules/Bluetooth/.../le_scan/MsftAdvMonitorTest.kt`, credit to the lead for
 * finding it). [EDDYSTONE_UUID] (0xFEAA) is kept as a second filter because Eddystone-EID is the
 * other published 20-byte EID format. Both filters are evaluated in controller hardware, so
 * carrying the second one costs no battery.
 *
 * If a capture ever settles it, delete the loser and nothing else changes: [FRAME_TYPE] and
 * [EID_OFFSET] are the only other guesses, and nothing downstream of [PoweredOffScanner] depends
 * on any of them. The derivation, the sealing, the upload and the retrieval are all unaffected.
 */
object PoweredOffBle {

    /** Google Fast Pair Service. Find My Device Network frames are a GFPS frame type. */
    val FAST_PAIR_UUID: UUID = UUID.fromString("0000fe2c-0000-1000-8000-00805f9b34fb")

    /** Eddystone, the other published home for a 20-byte EID. Kept as a fallback match. */
    val EDDYSTONE_UUID: UUID = UUID.fromString("0000feaa-0000-1000-8000-00805f9b34fb")

    /** Every service UUID the beacon might be advertising under, best guess first. */
    val CANDIDATE_SERVICE_UUIDS: List<ParcelUuid> =
        listOf(ParcelUuid(FAST_PAIR_UUID), ParcelUuid(EDDYSTONE_UUID))

    /** First service-data byte, identifying the frame as an FMDN one. */
    const val FRAME_TYPE: Byte = 0x40

    /** Where the EID starts within the service data, i.e. straight after [FRAME_TYPE]. */
    const val EID_OFFSET: Int = 1

    /** Shortest service data that can still carry a whole EID. */
    const val MIN_SERVICE_DATA_LEN: Int = EID_OFFSET + PoweredOffProtocol.EID_LEN

    /**
     * Filters matching only FMDN frames, tight enough for the controller to evaluate in hardware
     * so the application processor is never woken by unrelated advertisements — which on a busy
     * street is essentially all of them. Matching on the service-data prefix rather than just the
     * service UUID matters: ordinary Fast Pair and Eddystone beacons are common and share these
     * UUIDs, and each one would otherwise cost a wakeup.
     */
    fun scanFilters(): List<ScanFilter> = CANDIDATE_SERVICE_UUIDS.map { uuid ->
        ScanFilter.Builder()
            .setServiceData(uuid, byteArrayOf(FRAME_TYPE), byteArrayOf(0xFF.toByte()))
            .build()
    }

    /** The EID carried by this service data, or null if it is malformed or not an FMDN frame. */
    fun eidFrom(serviceData: ByteArray?): ByteArray? {
        if (serviceData == null || serviceData.size < MIN_SERVICE_DATA_LEN) return null
        if (serviceData[0] != FRAME_TYPE) return null
        return serviceData.copyOfRange(EID_OFFSET, EID_OFFSET + PoweredOffProtocol.EID_LEN)
    }
}

/** One powered-off beacon heard by a finder phone. */
data class PoweredOffSighting(
    /** The 20-byte rotating EID from the advertisement. */
    val eid: ByteArray,
    /** Received signal strength (dBm) — a very coarse proximity hint, not reported to anyone. */
    val rssi: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is PoweredOffSighting && eid.contentEquals(other.eid) && rssi == other.rssi

    override fun hashCode(): Int = eid.contentHashCode() * 31 + rssi
}
