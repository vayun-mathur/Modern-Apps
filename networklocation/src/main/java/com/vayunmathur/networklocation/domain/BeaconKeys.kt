package com.vayunmathur.networklocation.domain

/**
 * Packing of beacon identities into the 128-bit keys the offline WPSDB stores are indexed by.
 *
 * These functions MUST stay byte-for-byte identical to `keys.rs` in
 * `scripts/networklocation/wps_harvest`, which builds the stores. A mismatch does not fail
 * loudly — it just makes every lookup miss — so both sides are pinned by unit tests over
 * shared vectors (`BeaconKeysTest`).
 */
object BeaconKeys {
    /** A BSSID is 48 bits, so `hi` is always zero for WiFi keys. */
    const val WIFI_UNIVERSE_BITS = 48

    /**
     * Cell keys are 84 bits, laid out MSB-first as:
     *
     * ```
     * mcc(10) | mnc(10) | radio(4) | area(24) | cid(36)
     * ```
     *
     * 36 bits of cell id covers 5G's NCI; 24 bits of area code covers 5G's TAC. The radio type
     * is part of the identity because an LTE ECI and a GSM CID are otherwise indistinguishable
     * numbers in the same network and area code. The layout splits cleanly at bit 64: `hi`
     * carries the PLMN, `lo` carries everything else.
     */
    const val CELL_UNIVERSE_BITS = 84

    private const val CID_BITS = 36
    private const val AREA_BITS = 24
    private const val CID_MASK = (1L shl CID_BITS) - 1
    private const val AREA_MASK = (1L shl AREA_BITS) - 1

    /** Octets in a BSSID string. */
    private const val MAC_OCTETS = 6

    /** Hex chars per MAC octet. */
    private const val MAC_OCTET_HEX = 2

    /** Radix for parsing MAC octets. */
    private const val HEX_RADIX = 16

    /** Max value of one MAC octet. */
    private const val OCTET_MAX = 0xFF

    /** Bits per MAC octet, for big-endian packing. */
    private const val OCTET_BITS = 8

    /** Bit shift of the first octet in a 48-bit MAC key. */
    private const val FIRST_OCTET_SHIFT = 40

    /** Locally-administered/multicast bits of the first octet. */
    private const val RANDOMIZED_MAC_BITS = 0x03L

    /** Bits per PLMN field (MCC/MNC) in the cell key. */
    private const val PLMN_FIELD_BITS = 10

    /** Mask for a 10-bit PLMN field. */
    private const val PLMN_MASK = 0x3FFL

    /** Bits for the radio-type field in the cell key. */
    private const val RADIO_FIELD_BITS = 4

    /** Mask for the 4-bit radio field. */
    private const val RADIO_MASK = 0xFL

    /** Parse `"aa:bb:cc:dd:ee:ff"` to a 48-bit key, big-endian (OUI in the high bits). */
    fun parseMac(bssid: String): Long? {
        val parts = bssid.split(":")
        if (parts.size != MAC_OCTETS) return null
        var v = 0L
        for (p in parts) {
            if (p.length != MAC_OCTET_HEX) return null
            // toIntOrNull(16) accepts a leading '-', which would sign-extend into every
            // higher octet, so the range check is load-bearing and not just defensive.
            val b = p.toIntOrNull(HEX_RADIX) ?: return null
            if (b < 0 || b > OCTET_MAX) return null
            v = (v shl OCTET_BITS) or b.toLong()
        }
        return v
    }

    /**
     * Whether [mac] is locally administered or multicast — a randomized client address, a
     * virtual interface, or something that is not a fixed access point at all.
     *
     * The builder excludes these because they are not stable identifiers: the same address is
     * reused in different places and at different times, so an entry for one is worse than no
     * entry. Excluding them here too means the lookup path does not spend a store probe on a
     * key that is guaranteed to be absent.
     */
    fun isRandomizedMac(mac: Long): Boolean {
        val firstOctet = (mac ushr FIRST_OCTET_SHIFT) and OCTET_MAX.toLong()
        return (firstOctet and RANDOMIZED_MAC_BITS) != 0L
    }

    /** High 64 bits of the cell key: the PLMN, occupying key bits 64..83. */
    fun cellKeyHi(id: BeaconId.Cell): Long =
        ((id.mcc.toLong() and PLMN_MASK) shl PLMN_FIELD_BITS) or (id.mnc.toLong() and PLMN_MASK)

    /** Low 64 bits of the cell key: radio type, area code and cell id. */
    fun cellKeyLo(id: BeaconId.Cell): Long =
        ((id.radio.wire.toLong() and RADIO_MASK) shl (CID_BITS + AREA_BITS)) or
            ((id.tacOrLac.toLong() and AREA_MASK) shl CID_BITS) or
            (id.cellId and CID_MASK)
}
