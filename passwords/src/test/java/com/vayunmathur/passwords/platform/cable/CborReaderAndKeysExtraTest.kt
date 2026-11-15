package com.vayunmathur.passwords.platform.cable

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CborReaderAndKeysExtraTest {

    @Test fun readerRejectsNullMapKeysAndOversizedLengths() {
        // Map with a null (0xF6) key.
        assertFailsWith<IllegalStateException> {
            CborReader.decode(byteArrayOf(0xA1.toByte(), 0xF6.toByte(), 0x01))
        }
        // Byte string claiming 2^32-1 bytes: length is out of Int range.
        assertFailsWith<IllegalStateException> {
            CborReader.decode(byteArrayOf(0x5A, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        }
        // Tag major type (6) is unsupported by this reader.
        assertFailsWith<IllegalStateException> { CborReader.decode(byteArrayOf(0xC0.toByte())) }
        // Multi-byte unsigned via the 8-byte argument form.
        assertEquals(1L, CborReader.decode(byteArrayOf(0x1B, 0, 0, 0, 0, 0, 0, 0, 1)))
        // Nested arrays keep their structure.
        val nested = CborReader.decode(
            com.vayunmathur.passwords.domain.Cbor.encode(listOf(listOf(1L, 2L), emptyList<Any>())),
        )
        assertEquals(listOf(listOf(1L, 2L), emptyList<Any>()), nested)
    }

    @Test fun hkdfLengthAndSaltSemantics() {
        val ikm = ByteArray(16) { it.toByte() }
        val info = byteArrayOf(1, 0, 0, 0)
        assertEquals(0, CableKeys.hkdf(ikm, null, info, 0).size)
        assertFailsWith<IllegalArgumentException> {
            CableKeys.hkdf(ikm, null, info, 255 * 32 + 1)
        }
        // Null and empty salt mean the same RFC 5869 zero salt; a real salt changes the output.
        val unsalted = CableKeys.hkdf(ikm, null, info, 32)
        assertEquals(unsalted.toList(), CableKeys.hkdf(ikm, ByteArray(0), info, 32).toList())
        assertFalse(unsalted.contentEquals(CableKeys.hkdf(ikm, byteArrayOf(9), info, 32)))
        // Longer outputs extend, never alter, the prefix (HKDF expand property).
        val longer = CableKeys.hkdf(ikm, null, info, 64)
        assertEquals(unsalted.toList(), longer.copyOfRange(0, 32).toList())
    }

    @Test fun derivedKeysArePurposeSeparatedAndSized() {
        val secret = ByteArray(16) { (it * 7).toByte() }
        assertEquals(64, CableKeys.eidKey(secret).size)
        assertEquals(16, CableKeys.tunnelId(secret).size)
        val eid = CableEid.buildPlaintext(ByteArray(10), ByteArray(3), 0)
        assertEquals(32, CableKeys.psk(secret, eid).size)
        // Every purpose derives different material from the same secret.
        val outputs = CableKeys.Purpose.entries.map { CableKeys.derive(secret, null, it, 16).toList() }
        assertEquals(outputs.size, outputs.distinct().size)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), CableKeys.Purpose.entries.map { it.value })
        // A different secret changes every derived key.
        assertFalse(CableKeys.eidKey(secret).contentEquals(CableKeys.eidKey(ByteArray(16) { 0x55 })))
        assertEquals(listOf(0x78, 0x56, 0x34, 0x12), CableKeys.leUint32(0x12345678).map { it.toInt() and 0xFF })
        assertTrue(CableKeys.leUint32(-1).all { it == 0xFF.toByte() })
    }
}
