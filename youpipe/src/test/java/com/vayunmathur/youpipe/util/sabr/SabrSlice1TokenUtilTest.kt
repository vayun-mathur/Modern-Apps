package com.vayunmathur.youpipe.util.sabr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice-1 tests for the SABR token utilities ([SabrLocalDomPoTokenUtil.kt]):
 * challenge payload building, integrity-token parsing, and u8 conversions.
 * Pure JVM — no Android, no network.
 */
class SabrSlice1TokenUtilTest {

    @Test
    fun challengeDataBuildsInterpreterPayload() {
        val json = buildSabrAttChallengeData(
            SabrAttChallengeData(
                program = "PROG",
                globalName = "GLOB",
                interpreterJavascript = null,
                interpreterUrl = null,
            ),
            "console.log(1)",
        )
        assertTrue(json.contains("privateDoNotAccessOrElseSafeScriptWrappedValue"))
        assertTrue(json.contains("console.log(1)"))
        assertTrue(json.contains("\"program\":\"PROG\""))
        assertTrue(json.contains("\"globalName\":\"GLOB\""))
    }

    @Test
    fun integrityTokenParsesBase64AndExpiry() {
        // ["aGk=", "123"] -> ("new Uint8Array([104,105])", 123).
        val (u8, expiry) = parseSabrIntegrityTokenData("[\"aGk=\", \"123\"]")
        assertEquals("new Uint8Array([104,105])", u8)
        assertEquals(123L, expiry)
    }

    @Test
    fun stringToU8WrapsBytes() {
        assertEquals("new Uint8Array([65])", stringToSabrU8("A"))
        assertEquals("new Uint8Array([])", stringToSabrU8(""))
    }

    @Test
    fun csvU8RoundTrips() {
        val bytes = csvU8ToByteArray("104,105")
        assertTrue(bytes.contentEquals(byteArrayOf(104, 105)))
        assertTrue(csvU8ToByteArray("").isEmpty())
        assertTrue(csvU8ToByteArray("   ").isEmpty())
        // Full 0..255 range survives unsigned conversion.
        val all = (0..255).joinToString(",")
        val decoded = csvU8ToByteArray(all)
        assertEquals(256, decoded.size)
        assertEquals(0.toByte(), decoded[0])
        assertEquals(0xFF.toByte(), decoded[255])
    }

    @Test
    fun downloadExceptionCarriesReason() {
        for (reason in SabrDownloadException.Reason.entries) {
            val e = SabrDownloadException(reason, "msg-$reason")
            assertEquals(reason, e.reason)
            assertEquals("msg-$reason", e.message)
        }
        val cause = IllegalStateException("root")
        val wrapped = SabrDownloadException(SabrDownloadException.Reason.NETWORK, "net", cause)
        assertEquals(cause, wrapped.cause)
        assertNull(SabrDownloadException(SabrDownloadException.Reason.FORMAT, "f").cause)
    }
}
