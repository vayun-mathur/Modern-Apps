package com.vayunmathur.passwords.cable

import com.vayunmathur.passwords.util.Cbor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tests for the caBLE QR digit codec, CBOR field parse, and HKDF derivations. */
class CableQrTest {

    // --- Digit encoding (reference impl mirroring Chromium, for round-trip testing) ---

    private val partialDigits = intArrayOf(0, 3, 5, 8, 10, 13, 15, 0)

    private fun digitEncode(bytes: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (bytes.size - i >= 7) {
            sb.append(readLe(bytes, i, 7).toString().padStart(17, '0'))
            i += 7
        }
        val rem = bytes.size - i
        if (rem > 0) sb.append(readLe(bytes, i, rem).toString().padStart(partialDigits[rem], '0'))
        return sb.toString()
    }

    private fun readLe(bytes: ByteArray, off: Int, n: Int): Long {
        var v = 0L
        for (j in n - 1 downTo 0) v = (v shl 8) or (bytes[off + j].toLong() and 0xFF)
        return v
    }

    @Test fun digitRoundTripAllRemainders() {
        for (len in 0..40) {
            val data = ByteArray(len) { ((it * 37 + 11) and 0xFF).toByte() }
            val encoded = digitEncode(data)
            assertArrayEquals("len=$len", data, CableQrData.digitDecode(encoded))
        }
    }

    @Test fun parseFidoUri() {
        val peerKey = ByteArray(33) { (it + 1).toByte() }
        val secret = ByteArray(16) { (0xA0 + it).toByte() }
        val cbor = Cbor.encode(linkedMapOf<Any, Any>(
            0L to peerKey,
            1L to secret,
            2L to 2L,
            3L to 1_700_000_000L,
            5L to "ga",
        ))
        val uri = CableQrData.URI_PREFIX + digitEncode(cbor)

        val qr = CableQrData.parse(uri)
        assertArrayEquals(peerKey, qr.peerPublicKey)
        assertArrayEquals(secret, qr.qrSecret)
        assertEquals(2, qr.numKnownDomains)
        assertEquals(1_700_000_000L, qr.timestampSeconds)
        assertEquals("ga", qr.requestTypeHint)
        assertNull(qr.supportsLinking)
    }

    // --- HKDF known-answer test: RFC 5869 Appendix A.1 (HMAC-SHA256) ---

    @Test fun hkdfRfc5869TestCase1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }              // 0x00..0x0c
        val info = ByteArray(10) { (0xf0 + it).toByte() }     // 0xf0..0xf9
        val expected = hex(
            "3cb25f25faacd57a90434f64d0362f2a" +
            "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
            "34007208d5b887185865"
        )
        assertArrayEquals(expected, CableKeys.hkdf(ikm, salt, info, 42))
    }

    @Test fun deriveInfoIsLittleEndianUint32() {
        assertArrayEquals(byteArrayOf(3, 0, 0, 0), CableKeys.leUint32(CableKeys.Purpose.PSK.value))
        // Derived values are deterministic and length-correct.
        val secret = ByteArray(16) { it.toByte() }
        assertEquals(CableKeys.PSK_SIZE, CableKeys.psk(secret).size)
        assertEquals(CableKeys.EID_KEY_SIZE, CableKeys.eidKey(secret).size)
        assertEquals(CableKeys.TUNNEL_ID_SIZE, CableKeys.tunnelId(secret).size)
    }

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
