package com.vayunmathur.communicate.whatsapp

import com.vayunmathur.communicate.data.whatsapp.mex.MexPersistedQueryProvider
import com.vayunmathur.communicate.data.whatsapp.registration.RegEncoding
import com.vayunmathur.communicate.data.whatsapp.registration.RegistrationIntegrity
import com.vayunmathur.communicate.data.whatsapp.registration.WhatsAppRegistrationConstants
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 1 (w5): pure registration helpers — RegEncoding (minus android.util.Base64),
 * RegistrationIntegrity pure encoders, constants, and the MEX persist-id parser.
 * All JVM-pure (java.util.Base64 / kotlinx.serialization).
 */
class W5Slice1RegPureTest {

    // ---- RegEncoding.percentEncode ----

    @Test
    fun percentEncode_unreservedPassThrough() {
        val unreserved = "ABCabc019-._~".toByteArray(Charsets.US_ASCII)
        assertEquals("ABCabc019-._~", RegEncoding.percentEncode(unreserved))
    }

    @Test
    fun percentEncode_reservedAreUpperHex() {
        assertEquals("%00%0A%0D%20%2F%3D%3F%25", RegEncoding.percentEncode(byteArrayOf(0x00, 0x0A, 0x0D, 0x20, 0x2F, 0x3D, 0x3F, 0x25)))
        assertEquals("%2B", RegEncoding.percentEncode(byteArrayOf(0x2B)))
    }

    @Test
    fun percentEncode_utf8BytesEncodedPerByte() {
        // 'é' = 0xC3 0xA9
        assertEquals("%C3%A9", RegEncoding.percentEncode("é".toByteArray(Charsets.UTF_8)))
    }

    // ---- RegEncoding.uuidToBytes ----

    @Test
    fun uuidToBytes_matchesBigEndianLongs() {
        val bytes = RegEncoding.uuidToBytes("550e8400-e29b-41d4-a716-446655440000")
        val u = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
        val expected = ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()
        assertTrue(bytes.contentEquals(expected))
        // spot-check first bytes: 0x55 0x0e 0x84 0x00
        assertEquals(0x55.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[3])
    }

    // ---- RegistrationIntegrity pure encoders ----

    @Test
    fun aidOf_isBase64Sha256() {
        val expected = Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest("android-id-1".toByteArray()),
        )
        assertEquals(expected, RegistrationIntegrity.aidOf("android-id-1"))
        assertEquals(44, RegistrationIntegrity.aidOf("x").length)
    }

    @Test
    fun permissionsHashOf_sortsBeforeHashing() {
        val a = RegistrationIntegrity.permissionsHashOf(listOf("b.perm", "a.perm"))
        val b = RegistrationIntegrity.permissionsHashOf(listOf("a.perm", "b.perm"))
        assertEquals(a, b)
        assertEquals(44, a.length)
        val different = RegistrationIntegrity.permissionsHashOf(listOf("a.perm"))
        assertTrue(a != different)
    }

    @Test
    fun tField_isBase64BigEndianLong() {
        val expected = Base64.getEncoder().encodeToString(ByteBuffer.allocate(8).putLong(1700000000L).array())
        assertEquals(expected, RegistrationIntegrity.tField(1700000000L))
    }

    @Test
    fun jsonShapes_haveStableKeyOrder() {
        assertEquals("""{"sv":true,"sb":false}""", RegistrationIntegrity.emulationJson(true, false))
        assertEquals(
            """{"mp":false,"mu":true,"ae":1,"ap":2,"ai":3}""",
            RegistrationIntegrity.automationJson(false, true, 1, 2, 3),
        )
        assertEquals("""{"em":"abc"}""", RegistrationIntegrity.nativeSignalsJson("abc"))
    }

    // ---- constants ----

    @Test
    fun endpoint_buildsV2Url() {
        assertEquals("https://v.whatsapp.net/v2/exist", WhatsAppRegistrationConstants.endpoint("exist"))
        assertEquals("com.whatsapp", WhatsAppRegistrationConstants.PACKAGE_NAME)
        assertEquals(0x05.toByte(), WhatsAppRegistrationConstants.KEY_TYPE_CURVE25519)
        assertEquals(0x08.toByte(), WhatsAppRegistrationConstants.KEY_TYPE_KYBER)
    }

    // ---- MexPersistedQueryProvider.parsePersistIds ----

    @Test
    fun parsePersistIds_validShape() {
        val map = MexPersistedQueryProvider.parsePersistIds(
            """{"version":1,"data":{"OpA":"12345678901234567","OpB":"999"}}""",
        )
        assertEquals(mapOf("OpA" to "12345678901234567", "OpB" to "999"), map)
    }

    @Test
    fun parsePersistIds_malformed_returnsEmpty() {
        assertEquals(emptyMap(), MexPersistedQueryProvider.parsePersistIds("not json"))
        assertEquals(emptyMap(), MexPersistedQueryProvider.parsePersistIds("""{"version":1}"""))
        assertEquals(emptyMap(), MexPersistedQueryProvider.parsePersistIds(""))
    }
}
