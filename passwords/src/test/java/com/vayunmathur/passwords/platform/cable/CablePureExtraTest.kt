package com.vayunmathur.passwords.platform.cable

import com.vayunmathur.passwords.domain.Cbor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CablePureExtraTest {

    // ---- CborReader: every major type, widths, and failure modes ----

    @Test fun readerDecodesAllWidthsAndTypes() {
        assertEquals(0L, CborReader.decode(byteArrayOf(0x00)))
        assertEquals(255L, CborReader.decode(byteArrayOf(0x18, 0xFF.toByte())))
        assertEquals(65_536L, CborReader.decode(byteArrayOf(0x1A, 0, 1, 0, 0)))
        assertEquals(-1L, CborReader.decode(byteArrayOf(0x20)))
        assertEquals(-257L, CborReader.decode(byteArrayOf(0x39, 0x01, 0x00)))
        assertEquals("abc", CborReader.decode(byteArrayOf(0x63, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte())))
        assertEquals(listOf(1L, "x"), CborReader.decode(Cbor.encode(listOf(1L, "x"))))
        assertEquals(true, CborReader.decode(byteArrayOf(0xF5.toByte())))
        assertEquals(false, CborReader.decode(byteArrayOf(0xF4.toByte())))
        assertEquals(null, CborReader.decode(byteArrayOf(0xF6.toByte())))
        assertEquals(null, CborReader.decode(byteArrayOf(0xF7.toByte())))
        // Round-trip through the canonical encoder for a CTAP-shaped int-keyed map.
        val wire = Cbor.encode(mapOf(1L to byteArrayOf(4, 5), 2L to -3L, 3L to "ctap"))
        val decoded = CborReader(wire).readIntMap()
        assertEquals(listOf(4.toByte(), 5.toByte()), (decoded[1] as ByteArray).toList())
        assertEquals(-3L, decoded[2])
        assertEquals("ctap", decoded[3])
    }

    @Test fun readerTracksRemainingAndTypedGetters() {
        val reader = CborReader(Cbor.encode(listOf(7L, byteArrayOf(1))))
        val list = reader.readValue() as List<*>
        assertEquals(7L, list[0])
        assertEquals(0, reader.remaining)
        assertEquals(42L, CborReader(byteArrayOf(0x18, 42)).readInt())
        assertEquals(listOf(9.toByte()), CborReader(byteArrayOf(0x41, 9)).readByteString().toList())
    }

    @Test fun readerRejectsTruncatedAndUnsupportedInput() {
        assertFailsWith<IllegalStateException> { CborReader.decode(ByteArray(0)) }
        assertFailsWith<IllegalStateException> { CborReader.decode(byteArrayOf(0x18)) } // missing arg byte
        assertFailsWith<IllegalStateException> { CborReader.decode(byteArrayOf(0x43, 1)) } // short byte string
        assertFailsWith<IllegalStateException> { CborReader.decode(byteArrayOf(0x1F)) } // reserved additional info
        assertFailsWith<IllegalStateException> { CborReader.decode(byteArrayOf(0xF8.toByte(), 0)) } // simple next-byte
        assertFailsWith<IllegalStateException> { CborReader(byteArrayOf(0x61, 'a'.code.toByte())).readInt() }
        assertFailsWith<IllegalStateException> { CborReader(byteArrayOf(0x01)).readByteString() }
        assertFailsWith<IllegalStateException> { CborReader(byteArrayOf(0x01)).readIntMap() }
    }

    // ---- CableQrData: parsing validation ----

    private fun qrCbor(extra: Map<Long, Any> = emptyMap()): ByteArray {
        val base = linkedMapOf<Any, Any>(
            0L to ByteArray(CableQrData.PEER_KEY_SIZE) { (it + 1).toByte() },
            1L to ByteArray(CableQrData.QR_SECRET_SIZE) { (it + 40).toByte() },
            2L to 2L,
        )
        for ((k, v) in extra) base[k] = v
        return Cbor.encode(base)
    }

    @Test fun qrFromCborReadsOptionalFields() {
        val data = CableQrData.fromCbor(qrCbor(mapOf(3L to 1_700_000_000L, 4L to true, 5L to "ga")))
        assertEquals(CableQrData.PEER_KEY_SIZE, data.peerPublicKey.size)
        assertEquals(CableQrData.QR_SECRET_SIZE, data.qrSecret.size)
        assertEquals(2, data.numKnownDomains)
        assertEquals(1_700_000_000L, data.timestampSeconds)
        assertEquals(true, data.supportsLinking)
        assertEquals("ga", data.requestTypeHint)
        val minimal = CableQrData.fromCbor(qrCbor())
        assertEquals(null, minimal.timestampSeconds)
        assertEquals(null, minimal.supportsLinking)
        assertEquals(null, minimal.requestTypeHint)
    }

    @Test fun qrRejectsBadShapes() {
        assertFailsWith<IllegalStateException> { CableQrData.parse("https://example.com") }
        assertFailsWith<IllegalArgumentException> { CableQrData.digitDecode("12a45") }
        assertFailsWith<IllegalArgumentException> { CableQrData.digitDecode("1") } // 1 digit is not a valid chunk
        assertFailsWith<IllegalStateException> { CableQrData.fromCbor(Cbor.encode(listOf(1L))) } // not a map
        assertFailsWith<IllegalStateException> {
            CableQrData.fromCbor(Cbor.encode(mapOf(1L to ByteArray(16)))) // missing peer key
        }
        assertFailsWith<IllegalArgumentException> {
            CableQrData.fromCbor(
                Cbor.encode(mapOf(0L to ByteArray(10), 1L to ByteArray(16))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CableQrData.fromCbor(
                Cbor.encode(mapOf(0L to ByteArray(33), 1L to ByteArray(3))),
            )
        }
        // Lowercase scheme reaches the same digit decoder as the canonical uppercase one.
        assertFailsWith<IllegalArgumentException> { CableQrData.parse("fido:/12a") }
    }

    @Test fun qrDigitDecodePartialChunks() {
        // 3 digits encode 1 byte, 5 digits 2 bytes, 17 digits a full 7-byte chunk.
        assertEquals(listOf(5.toByte()), CableQrData.digitDecode("005").toList())
        assertEquals(listOf(1.toByte(), 2.toByte()), CableQrData.digitDecode("00513").toList())
        assertEquals(ByteArray(7).toList(), CableQrData.digitDecode("0".repeat(17)).toList())
        assertEquals(0, CableQrData.digitDecode("").size)
    }

    // ---- Crypter padding / nonce ----

    @Test fun crypterNonceLayoutAndPadding() {
        val nonce = Crypter.nonce(0x01020304)
        assertEquals(12, nonce.size)
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4), nonce.map { it.toInt() and 0xFF })

        val empty = Crypter.pad(ByteArray(0))
        assertEquals(32, empty.size)
        assertEquals(31, empty.last().toInt())
        assertEquals(0, Crypter.unpad(empty).size)

        val exact = ByteArray(31) { it.toByte() }
        val padded = Crypter.pad(exact)
        assertEquals(32, padded.size)
        assertEquals(0, padded.last().toInt())
        assertEquals(exact.toList(), Crypter.unpad(padded).toList())

        val over = ByteArray(32) { it.toByte() }
        assertEquals(64, Crypter.pad(over).size)
        assertEquals(over.toList(), Crypter.unpad(Crypter.pad(over)).toList())

        assertFailsWith<IllegalArgumentException> { Crypter.unpad(ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { Crypter.unpad(byteArrayOf(0x7F)) }
    }

    @Test fun crypterSequencesAreIndependentPerDirection() {
        val keyA = ByteArray(32) { 1 }
        val keyB = ByteArray(32) { 2 }
        val alice = Crypter(keyA, keyB)
        val bob = Crypter(keyB, keyA)
        val first = alice.encrypt("hello cable".toByteArray())
        val second = alice.encrypt("second".toByteArray())
        assertEquals("hello cable", bob.decrypt(first).decodeToString())
        assertEquals("second", bob.decrypt(second).decodeToString())
        // Replaying the first ciphertext as if it were the second message must not decrypt
        // to the first plaintext under the advanced counter (GCM tag or nonce mismatch).
        val bobFresh = Crypter(keyB, keyA)
        bobFresh.decrypt(first)
        assertFailsWith<Exception> { bobFresh.decrypt(first) }
    }

    @Test fun qrDigitRoundTripForEveryLength() {
        // Test-side encoder mirroring the QR digit scheme: 7 bytes -> 17 digits,
        // trailing partial chunks use 3/5/8/10/13/15 digits for 1..6 bytes.
        val partialDigits = intArrayOf(0, 3, 5, 8, 10, 13, 15, 0)
        fun encode(bytes: ByteArray): String {
            val sb = StringBuilder()
            var i = 0
            while (i + 7 <= bytes.size) {
                var v = 0L
                for (j in 6 downTo 0) v = (v shl 8) or (bytes[i + j].toLong() and 0xFF)
                sb.append(v.toString().padStart(17, '0'))
                i += 7
            }
            val rem = bytes.size - i
            if (rem > 0) {
                var v = 0L
                for (j in rem - 1 downTo 0) v = (v shl 8) or (bytes[i + j].toLong() and 0xFF)
                sb.append(v.toString().padStart(partialDigits[rem], '0'))
            }
            return sb.toString()
        }
        for (length in 0..22) {
            val bytes = ByteArray(length) { (it * 37 + length).toByte() }
            assertEquals(bytes.toList(), CableQrData.digitDecode(encode(bytes)).toList(), "length $length")
        }
        // Full URI parse round-trip through the digit encoding.
        val cbor = qrCbor(mapOf(5L to "mc"))
        val parsed = CableQrData.parse("FIDO:/" + encode(cbor))
        assertEquals("mc", parsed.requestTypeHint)
        assertEquals(2, parsed.numKnownDomains)
        val lower = CableQrData.parse("  fido:/" + encode(cbor) + "  ")
        assertEquals("mc", lower.requestTypeHint)
    }
}
