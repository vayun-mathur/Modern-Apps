package com.vayunmathur.networklocation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.vayunmathur.networklocation.domain.BeaconKeys
import com.vayunmathur.networklocation.domain.BeaconId
import com.vayunmathur.networklocation.domain.RadioType

/**
 * Pins the key packing against the store builder.
 *
 * These vectors are transcribed into `keys.rs` in `scripts/networklocation/wps_harvest` and
 * asserted there too. Both sides must agree exactly: a packing mismatch does not throw or log,
 * it just makes every offline lookup miss, which is indistinguishable from "the database does
 * not have that beacon".
 */
class BeaconKeysTest {

    @Test
    fun `mac parses big-endian with the oui in the high bits`() {
        assertEquals(0x001122334455L, BeaconKeys.parseMac("00:11:22:33:44:55"))
        assertEquals(0xAABBCCDDEEFFL, BeaconKeys.parseMac("aa:bb:cc:dd:ee:ff"))
        assertEquals(0xAABBCCDDEEFFL, BeaconKeys.parseMac("AA:BB:CC:DD:EE:FF"))
        assertEquals(0L, BeaconKeys.parseMac("00:00:00:00:00:00"))
        assertEquals(0xFFFFFFFFFFFFL, BeaconKeys.parseMac("ff:ff:ff:ff:ff:ff"))
    }

    @Test
    fun `malformed macs are rejected rather than silently truncated`() {
        assertNull(BeaconKeys.parseMac(""))
        assertNull(BeaconKeys.parseMac("00:11:22:33:44"))
        assertNull(BeaconKeys.parseMac("00:11:22:33:44:55:66"))
        assertNull(BeaconKeys.parseMac("00-11-22-33-44-55"))
        assertNull(BeaconKeys.parseMac("zz:11:22:33:44:55"))
        // A one-digit group would otherwise parse and shift the whole address.
        assertNull(BeaconKeys.parseMac("0:11:22:33:44:55"))
        // toIntOrNull(16) accepts a leading sign, which sign-extends into every higher octet.
        assertNull(BeaconKeys.parseMac("-1:11:22:33:44:55"))
    }

    @Test
    fun `locally administered and multicast macs are excluded`() {
        // Bit 0x02 of the first octet: randomized or virtual, not a fixed AP.
        assertTrue(BeaconKeys.isRandomizedMac(0xAABBCCDDEEFFL))
        assertTrue(BeaconKeys.isRandomizedMac(0x020000000000L))
        // Bit 0x01: multicast, never a real BSSID.
        assertTrue(BeaconKeys.isRandomizedMac(0x010000000000L))
        // A real, globally administered OUI.
        assertFalse(BeaconKeys.isRandomizedMac(0x001122334455L))
        assertFalse(BeaconKeys.isRandomizedMac(0xFCFBFB000000L))
        // Only the first octet decides it.
        assertFalse(BeaconKeys.isRandomizedMac(0x00FFFFFFFFFFL))
    }

    @Test
    fun `cell key layout is mcc10 mnc10 radio4 area24 cid36`() {
        val cell = BeaconId.Cell(
            mcc = 310,
            mnc = 260,
            radio = RadioType.LTE,
            cellId = 0xABCDEF123L,
            tacOrLac = 12345,
        )
        assertEquals(0x4D904L, BeaconKeys.cellKeyHi(cell))
        assertEquals(0x3003039ABCDEF123L, BeaconKeys.cellKeyLo(cell))
    }

    @Test
    fun `radio type discriminates otherwise identical cells`() {
        fun cell(radio: RadioType) =
            BeaconId.Cell(mcc = 234, mnc = 15, radio = radio, cellId = 4242, tacOrLac = 999)

        val keys = RadioType.entries.map { BeaconKeys.cellKeyLo(cell(it)) }
        assertEquals(keys.size, keys.distinct().size, "each radio type must produce its own key")

        // The specific pairing that used to collide: an LTE ECI and a GSM CID are the same
        // number in the same PLMN and area code.
        assertNotEquals(
            BeaconKeys.cellKeyLo(cell(RadioType.LTE)),
            BeaconKeys.cellKeyLo(cell(RadioType.GSM)),
        )
    }

    @Test
    fun `full 36-bit 5G nci survives packing`() {
        val nci = 0xFFFFFFFFFL // 36 bits, all set
        val cell = BeaconId.Cell(
            mcc = 1,
            mnc = 1,
            radio = RadioType.NR,
            cellId = nci,
            tacOrLac = 0,
        )
        val lo = BeaconKeys.cellKeyLo(cell)
        assertEquals(nci, lo and 0xFFFFFFFFFL, "the NCI must round-trip, not truncate to 28 bits")

        // The old 28-bit field aliased these two onto the same key.
        val truncated = cell.copy(cellId = nci and 0x0FFFFFFFL)
        assertNotEquals(lo, BeaconKeys.cellKeyLo(truncated))
    }

    @Test
    fun `24-bit 5G tac survives packing`() {
        val tac = 0xFFFFFF // 24 bits, wider than the 16-bit LTE TAC
        val cell = BeaconId.Cell(
            mcc = 505,
            mnc = 1,
            radio = RadioType.NR,
            cellId = 1,
            tacOrLac = tac,
        )
        val lo = BeaconKeys.cellKeyLo(cell)
        assertEquals(tac.toLong(), (lo ushr 36) and 0xFFFFFFL)
    }

    @Test
    fun `cell keys stay inside the declared 84-bit universe`() {
        val widest = BeaconId.Cell(
            mcc = 999,
            mnc = 999,
            radio = RadioType.NR,
            cellId = 0xFFFFFFFFFL,
            tacOrLac = 0xFFFFFF,
        )
        val hi = BeaconKeys.cellKeyHi(widest)
        val lo = BeaconKeys.cellKeyLo(widest)
        assertTrue(hi >= 0 && hi < (1L shl (BeaconKeys.CELL_UNIVERSE_BITS - 64)))
        // Bit 63 must stay clear so the (hi, lo) pair is unambiguous as two signed longs.
        assertTrue(lo >= 0)
    }

    @Test
    fun `wifi keys stay inside the declared 48-bit universe`() {
        val mac = BeaconKeys.parseMac("ff:ff:ff:ff:ff:ff")!!
        assertTrue(mac < (1L shl BeaconKeys.WIFI_UNIVERSE_BITS))
    }
}
