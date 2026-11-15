package com.vayunmathur.passwords.platform.cable

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CryptoEdgesExtraTest {

    @Test fun eidValidationAndLayout() {
        assertFailsWith<IllegalArgumentException> {
            CableEid.buildPlaintext(ByteArray(3), ByteArray(3), 0)
        }
        assertFailsWith<IllegalArgumentException> {
            CableEid.buildPlaintext(ByteArray(10), ByteArray(2), 0)
        }
        assertFailsWith<IllegalArgumentException> { CableEid.encrypt(ByteArray(3), ByteArray(64)) }
        assertFailsWith<IllegalArgumentException> { CableEid.encrypt(ByteArray(16), ByteArray(10)) }

        val nonce = ByteArray(10) { (it + 1).toByte() }
        val eid = CableEid.buildPlaintext(nonce, byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()), 0x1234)
        assertEquals(0.toByte(), eid[0]) // reserved
        assertEquals(nonce.toList(), eid.copyOfRange(1, 11).toList())
        assertEquals(listOf(0xAA, 0xBB, 0xCC), eid.copyOfRange(11, 14).map { it.toInt() and 0xFF })
        // Domain id is little-endian.
        assertEquals(0x34, eid[14].toInt() and 0xFF)
        assertEquals(0x12, eid[15].toInt() and 0xFF)

        assertEquals(10, CableEid.randomNonce().size)
        assertFalse(CableEid.randomNonce().contentEquals(CableEid.randomNonce()))
        assertEquals(20, CableEid.ADVERT_SIZE)
        assertEquals(0xFDE2, CableEid.SERVICE_DATA_UUID16)
    }

    @Test fun eidEncryptIsDeterministicAndKeySensitive() {
        val plaintext = CableEid.buildPlaintext(ByteArray(10) { 1 }, ByteArray(3) { 2 }, 7)
        val key = ByteArray(64) { it.toByte() }
        val a = CableEid.encrypt(plaintext, key)
        val b = CableEid.encrypt(plaintext, key)
        assertEquals(a.toList(), b.toList())
        assertEquals(CableEid.ADVERT_SIZE, a.size)
        val other = CableEid.encrypt(plaintext, ByteArray(64) { (it + 100).toByte() })
        assertFalse(a.contentEquals(other))
    }

    @Test fun p256RejectsBadEncodings() {
        assertFailsWith<IllegalArgumentException> { P256.decodePoint(byteArrayOf(0x05, 1, 2)) }
        assertFailsWith<IllegalArgumentException> { P256.decodePoint(byteArrayOf(0x04, 1, 2)) } // too short
        assertFailsWith<IllegalArgumentException> { P256.decodePoint(byteArrayOf(0x02, 1, 2)) } // too short
        assertFailsWith<IndexOutOfBoundsException> { P256.decodePoint(ByteArray(0)) }
    }

    @Test fun p256UncompressedRoundTripAndSizes() {
        val pair = P256.generateKeyPair()
        val uncompressed = P256.toUncompressed(pair.public)
        assertEquals(P256.UNCOMPRESSED_SIZE, uncompressed.size)
        assertEquals(0x04, uncompressed[0].toInt())
        val decoded = P256.decodePoint(uncompressed)
        assertEquals(P256.toCompressed(pair.public).toList(), P256.toCompressed(decoded).toList())
        // ECDH with itself equals ECDH in the other argument position (same key both sides).
        val secret = P256.ecdh(pair.private, pair.public)
        assertEquals(P256.DH_OUTPUT_SIZE, secret.size)
        assertTrue(secret.any { it != 0.toByte() })
    }
}
