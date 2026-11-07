package com.vayunmathur.passwords.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TotpAndCborTest {

    // RFC 4226/6238 test key "12345678901234567890" (ASCII), base32-encoded.
    private val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    @Test fun totpMatchesRfcVectorAt59Seconds() {
        // Counter 1, HOTP value from RFC 4226 Appendix D.
        assertEquals("287082", TOTP.generate(secret, 59))
    }

    @Test fun totpMatchesRfcVectorAt1111111109() {
        assertEquals("081804", TOTP.generate(secret, 1_111_111_109))
    }

    @Test fun totpIsStableWithinStepAndChangesAcrossSteps() {
        assertEquals(TOTP.generate(secret, 60), TOTP.generate(secret, 89))
        assertTrue(TOTP.generate(secret, 60) != TOTP.generate(secret, 90))
    }

    @Test fun totpNormalizesCaseSpacesAndPadding() {
        assertEquals(
            TOTP.generate(secret, 59),
            TOTP.generate(" gezdgnbv gy3tqojqgezdgnbvgy3tqojq== ", 59),
        )
    }

    @Test fun totpEmptySecretIsRejected() {
        assertFailsWith<IllegalArgumentException> { TOTP.generate("", 59) }
    }

    @Test fun cborEncodesScalars() {
        assertEquals(listOf(0x00.toByte()), Cbor.encode(0L).toList())
        assertEquals(listOf(0x17.toByte()), Cbor.encode(23).toList())
        assertEquals(listOf(0x18.toByte(), 0x18.toByte()), Cbor.encode(24).toList())
        assertEquals(listOf(0x20.toByte()), Cbor.encode(-1L).toList())
        assertEquals(listOf(0xF4.toByte()), Cbor.encode(false).toList())
        assertEquals(listOf(0xF5.toByte()), Cbor.encode(true).toList())
    }

    @Test fun cborEncodesStringsByUtf8Length() {
        assertEquals(listOf(0x61.toByte(), 'a'.code.toByte()), Cbor.encode("a").toList())
        // "é" is 2 UTF-8 bytes, so the header says length 2.
        val encoded = Cbor.encode("é")
        assertEquals(0x62.toByte(), encoded[0])
        assertEquals(3, encoded.size)
    }

    @Test fun cborEncodesByteArraysListsAndMaps() {
        assertEquals(
            listOf(0x43, 1, 2, 3),
            Cbor.encode(byteArrayOf(1, 2, 3)).map { it.toInt() and 0xFF },
        )
        assertEquals(
            listOf(0x82, 0x01, 0x61, 0x61),
            Cbor.encode(listOf(1L, "a")).map { it.toInt() and 0xFF },
        )
        val map = Cbor.encode(mapOf(1L to "x"))
        assertEquals(0xA1.toByte(), map[0])
    }

    @Test fun cborRejectsUnsupportedType() {
        assertFailsWith<IllegalArgumentException> { Cbor.encode(Any()) }
    }
}
