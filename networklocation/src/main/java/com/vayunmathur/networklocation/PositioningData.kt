package com.vayunmathur.networklocation

/**
 * Plain domain types shared across the network-location pipeline (scan → offline
 * store lookup → cache → position estimate). Kept free of Android types so the
 * pieces compose cleanly.
 */

/**
 * Cellular access technology. Part of a cell's identity, not decoration: an LTE ECI and a GSM
 * CID are different numbers in the same range, so without this two unrelated towers in the same
 * network and area code alias onto one key.
 *
 * The wire values are baked into the `cells-v2.wpsdb` key and MUST match `wps_harvest`'s
 * `RadioType`. Append only.
 */
@Suppress("MagicNumber") // Wire values are the protocol spec; see doc above.
/**
 * Wire values are baked into the `cells-v2.wpsdb` key and MUST match `wps_harvest`'s
 * `RadioType`. File level (not in the companion): enum entries are initialised
 * before the companion object, so entries cannot read companion constants —
 * but file-level `const` is inlined at compile time and has no init order.
 */
private const val WIRE_GSM = 1
private const val WIRE_UMTS = 2
private const val WIRE_LTE = 3
private const val WIRE_NR = 4

enum class RadioType(val wire: Int) {
    GSM(WIRE_GSM),
    UMTS(WIRE_UMTS),
    LTE(WIRE_LTE),
    NR(WIRE_NR),
    ;
}

/** A radio beacon we can resolve to a location: a WiFi access point or a cell tower. */
sealed interface BeaconId {
    data class Wifi(val bssid: String) : BeaconId

    /**
     * [cellId] is a [Long] because 5G's NCI is 36 bits; truncating it to an [Int] aliases
     * distinct gNB cells onto one another. [tacOrLac] holds a 5G 24-bit TAC as well as the
     * 16-bit LTE TAC and GSM/UMTS LAC.
     */
    data class Cell(
        val mcc: Int,
        val mnc: Int,
        val radio: RadioType,
        val cellId: Long,
        val tacOrLac: Int,
    ) : BeaconId
}

/**
 * A beacon whose coordinates are known (from the offline stores or the local cache).
 * [accuracyMeters] is the beacon's own horizontal accuracy radius.
 *
 * [altitudeMeters] is the beacon's altitude when the source reported one (kind-2 WiFi
 * records; Apple's `location.5`), or null when it did not — always the case for cells and
 * for kind-1 stores. The solver is 2D and does not consume it yet.
 */
data class BeaconFix(
    val id: BeaconId,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val altitudeMeters: Double? = null,
)

/** An estimated device position: the output of the Rust weighted-centroid solver. */
data class DevicePosition(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
)
