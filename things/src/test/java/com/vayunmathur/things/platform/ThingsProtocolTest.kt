package com.vayunmathur.things.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Scale frame building, category/encryption detection, weight decoding, bottle hex. */
class ThingsProtocolTest {

    @Test
    fun buildFrameLayoutAndChecksum() {
        val frame = buildFrame(0xA0, 0x01, 0x10, 0x20)
        assertEquals(6, frame.size)
        assertEquals(0xA0.toByte(), frame[0])
        assertEquals(6, frame[1].toInt())
        assertEquals(0x01.toByte(), frame[2])
        assertEquals(0x10.toByte(), frame[3])
        assertEquals(0x20.toByte(), frame[4])
        val sum = (0 until frame.size - 1).sumOf { frame[it].toInt() }
        assertEquals(sum.toByte(), frame[frame.size - 1])
        // buildCmdWithType is the same framing with scale type as arg.
        assertTrue(buildCmdWithType(0x01, 0x02, 0x03).contentEquals(buildFrame(0x01, 0x02, 0x03)))
    }

    @Test
    fun timePayloadIsLittleEndianSecondsSince2000() {
        val millis = 946_684_800_000L // 2000-01-01T00:00:00Z
        assertEquals(listOf(0, 0, 0, 0), timePayload(millis).toList())
        val later = timePayload(millis + 1000L)
        assertEquals(1, later[0])
        assertEquals(0, later[1])
    }

    @Test
    fun scaleCategoryFromManufacturerMarker() {
        assertEquals(CATEGORY_DEFAULT, qnScaleCategory(null))
        assertEquals(CATEGORY_DEFAULT, qnScaleCategory(ByteArray(5)))
        val mfg = ByteArray(16)
        mfg[MFG_CATEGORY_INDEX] = MARKER_CAT_101.toByte()
        assertEquals(CATEGORY_101, qnScaleCategory(mfg))
        mfg[MFG_CATEGORY_INDEX] = MARKER_CAT_EIGHT.toByte()
        assertEquals(CATEGORY_EIGHT_ELECTRODE, qnScaleCategory(mfg))
        mfg[MFG_CATEGORY_INDEX] = 0x7F.toByte()
        assertEquals(CATEGORY_DEFAULT, qnScaleCategory(mfg))
    }

    @Test
    fun resistanceEncryptFlagByCategoryFamily() {
        assertFalse(qnUsesResistanceEncrypt(CATEGORY_101, null))
        val va = ByteArray(16)
        va[MFG_ENCRYPT_FLAG_INDEX_VA] = 0b100.toByte()
        assertTrue(qnUsesResistanceEncrypt(CATEGORY_128, va))
        va[MFG_ENCRYPT_FLAG_INDEX_VA] = 0
        assertFalse(qnUsesResistanceEncrypt(CATEGORY_128, va))
        val std = ByteArray(16)
        std[MFG_ENCRYPT_FLAG_INDEX_STD] = 1
        assertTrue(qnUsesResistanceEncrypt(CATEGORY_130, std))
        // Unlisted categories never encrypt.
        assertFalse(qnUsesResistanceEncrypt(CATEGORY_101, std))
    }

    @Test
    fun resistanceCryptSwapsBitPairsOnlyWhenEncrypted() {
        val raw = 0b0010_1001.toByte()
        assertEquals(0b0010_1001, resistanceCrypt(raw, false))
        // Bits 3<->5 and 0<->4 swapped: 0b00101001 -> bit3=1,bit5=0 swap; bit0=1,bit4=0 swap.
        val encrypted = resistanceCrypt(raw, true)
        assertEquals(swapBit(0, 4, swapBit(3, 5, 0b0010_1001)), encrypted)
        assertEquals(0b0010_1001, resistanceCrypt(encrypted.toByte(), true))
    }

    @Test
    fun weightDecodingRescalesAboveMax() {
        assertEquals(75.0, decodeWeight(7500, 100.0))
        assertEquals(75.0, decodeWeightByMultiplication(750, 0.1))
        // 500kg readback rescales by /10 until under 300.
        assertEquals(50.0, decodeWeight(50_000, 100.0))
        assertEquals(0.0, decodeWeight(0, 100.0))
    }

    @Test
    fun fourResNoContactDecodesZero() {
        assertEquals(0, fourResTwoByte2Int(0xFF.toByte(), 0xFF.toByte(), false))
        assertEquals(500, fourResTwoByte2Int(0x01, 0xF4.toByte(), false))
        assertEquals(500.0 * K_RATIO, eightDouble(0x01, 0xF4.toByte(), false))
    }

    @Test
    fun bottleHexCodecAndVolume() {
        assertEquals("00ff10", byteArrayOf(0, 0xFF.toByte(), 0x10).toHex())
        assertEquals("000a", toHex(10, 4))
        assertTrue(byteArrayOf(0xDE.toByte(), 0xAD.toByte()).contentEquals(hexToByteArray("DEAD")))
        assertTrue(byteArrayOf(0xDE.toByte(), 0xAD.toByte()).contentEquals(hexToByteArray("dead")))
        assertEquals(258, beInt(byteArrayOf(1, 2), 0))
        assertEquals(100, volumePct(1000))
        assertEquals(0, volumePct(0))
    }
}
