package com.vayunmathur.camera.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/** QrClassification CBOR/digit-decode edges beyond the happy-path hints. */
class QrClassificationEdgesTest {

    private fun digitEncode(cbor: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (cbor.size - i >= 7) {
            var v = 0L
            for (b in 0 until 7) v = v or ((cbor[i + b].toLong() and 0xFF) shl (8 * b))
            sb.append(v.toString().padStart(17, '0'))
            i += 7
        }
        val remaining = cbor.size - i
        if (remaining > 0) {
            var v = 0L
            for (b in 0 until remaining) v = v or ((cbor[i + b].toLong() and 0xFF) shl (8 * b))
            val digits = intArrayOf(0, 3, 5, 8, 10, 13, 15, 0)[remaining]
            sb.append(v.toString().padStart(digits, '0'))
        }
        return sb.toString()
    }

    private fun uri(cbor: ByteArray, prefix: String = "FIDO:/") = prefix + digitEncode(cbor)

    /** Map{5: "ga"} with the key/value encoded the long way (minor 24). */
    @Test fun longFormKeyAndTextStillDecode() {
        val cbor = byteArrayOf(
            0xA1.toByte(), 0x18, 0x05, 0x78, 0x02, 'g'.code.toByte(), 'a'.code.toByte(),
        )
        assertEquals(FidoRequestType.SIGN_IN, fidoRequestType(uri(cbor)))
    }

    @Test fun unknownHintTextIsUnknown() {
        val cbor = byteArrayOf(0xA1.toByte(), 0x05, 0x62, 'x'.code.toByte(), 'x'.code.toByte())
        assertEquals(FidoRequestType.UNKNOWN, fidoRequestType(uri(cbor)))
    }

    @Test fun nonTextHintValueIsUnknown() {
        // Key 5 maps to a byte string, not text.
        val cbor = byteArrayOf(0xA1.toByte(), 0x05, 0x42, 'g'.code.toByte(), 'a'.code.toByte())
        assertEquals(FidoRequestType.UNKNOWN, fidoRequestType(uri(cbor)))
    }

    @Test fun hintAfterSkippableEntries() {
        // Map{0: [1,2], 1: {0: 7}, 2: -1, 5: "mc"} exercises array/map/negative skips.
        val cbor = byteArrayOf(
            0xA4.toByte(),
            0x00, 0x82.toByte(), 0x01, 0x02,
            0x01, 0xA1.toByte(), 0x00, 0x07,
            0x02, 0x20,
            0x05, 0x62, 'm'.code.toByte(), 'c'.code.toByte(),
        )
        assertEquals(FidoRequestType.CREATE, fidoRequestType(uri(cbor)))
    }

    @Test fun nonMapTopLevelIsUnknown() {
        val cbor = byteArrayOf(0x81.toByte(), 0x00) // array of one int
        assertEquals(FidoRequestType.UNKNOWN, fidoRequestType(uri(cbor)))
    }

    @Test fun indefiniteAndTruncatedPayloadsAreUnknown() {
        assertEquals(FidoRequestType.UNKNOWN, fidoRequestType(uri(byteArrayOf(0xBF.toByte()))))
        // Map of 3 entries but the bytes end after the header.
        assertEquals(FidoRequestType.UNKNOWN, fidoRequestType(uri(byteArrayOf(0xA3.toByte()))))
        // Text value claims 5 bytes, only 2 present.
        val truncated = byteArrayOf(0xA1.toByte(), 0x05, 0x65, 'g'.code.toByte(), 'a'.code.toByte())
        assertEquals(FidoRequestType.UNKNOWN, fidoRequestType(uri(truncated)))
    }

    @Test fun invalidTrailingDigitCountsAreUnknown() {
        // 18 digits = one full chunk (17) + 1 trailing digit (invalid count).
        assertEquals(FidoRequestType.UNKNOWN, fidoRequestType("FIDO:/" + "1".repeat(18)))
        // 2 digits is not a valid partial-chunk size either.
        assertEquals(FidoRequestType.UNKNOWN, fidoRequestType("FIDO:/12"))
        // Non-digit bodies and empty bodies.
        assertEquals(FidoRequestType.UNKNOWN, fidoRequestType("FIDO:/ 123"))
        assertEquals(FidoRequestType.UNKNOWN, fidoRequestType("FIDO:"))
    }

    @Test fun lowercaseAndMixedPrefixes() {
        val cbor = byteArrayOf(0xA1.toByte(), 0x05, 0x62, 'm'.code.toByte(), 'c'.code.toByte())
        assertEquals(FidoRequestType.CREATE, fidoRequestType(uri(cbor, "fido:/")))
        assertEquals(FidoRequestType.CREATE, fidoRequestType(uri(cbor, "Fido:")))
        assertEquals(FidoRequestType.UNKNOWN, fidoRequestType("WIFI:T:WPA;S:x;;"))
    }

    @Test fun multiByteArgumentsInSkippedValues() {
        // Map{0: h'...' (byte string len via minor 24), 5: "ga"} and a
        // skipped uint with a 2-byte argument (minor 25) before the hint.
        val cbor = byteArrayOf(
            0xA3.toByte(),
            0x00, 0x58, 0x03, 1, 2, 3,
            0x01, 0x19, 0x03, 0xE8.toByte(),
            0x05, 0x62, 'g'.code.toByte(), 'a'.code.toByte(),
        )
        assertEquals(FidoRequestType.SIGN_IN, fidoRequestType(uri(cbor)))
    }
}
