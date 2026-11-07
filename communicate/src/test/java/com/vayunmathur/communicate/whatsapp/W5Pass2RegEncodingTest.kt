package com.vayunmathur.communicate.whatsapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.communicate.data.whatsapp.registration.RegEncoding
import com.vayunmathur.communicate.data.whatsapp.registration.RegistrationHttpClient
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * Pass-2 slice-1 (whatsapp registration): the pure request encoders and the
 * endpoint result types' success predicates. The HTTP endpoints themselves
 * are live-server work and are not driven here.
 */
@RunWith(AndroidJUnit4::class)
class W5Pass2RegEncodingTest {

    @Test
    fun `b64url is url safe without padding`() {
        assertEquals("", RegEncoding.b64Url(byteArrayOf()))
        // 0xFB 0xFF 0xFE is "+//+" in standard base64, "-__-" URL-safe.
        assertEquals("-__-", RegEncoding.b64Url(byteArrayOf(-5, -1, -2)))
        assertEquals("aGk", RegEncoding.b64Url("hi".toByteArray()))
        assertFalse(RegEncoding.b64Url(ByteArray(32) { it.toByte() }).contains("="))
        assertFalse(RegEncoding.b64Url(ByteArray(32) { it.toByte() }).contains("\n"))
    }

    @Test
    fun `uuid bytes are the big endian form`() {
        val uuid = "00112233-4455-6677-8899-aabbccddeeff"
        val bytes = RegEncoding.uuidToBytes(uuid)
        assertEquals(16, bytes.size)
        assertEquals(0x00.toByte(), bytes[0])
        assertEquals(0x11.toByte(), bytes[1])
        assertEquals(0xFF.toByte(), bytes[15])
        // Round-trips through java.util.UUID.
        val msb = bytes.copyOfRange(0, 8).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        val lsb = bytes.copyOfRange(8, 16).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        assertEquals(UUID.fromString(uuid), UUID(msb, lsb))
        assertFailsWith<IllegalArgumentException> { RegEncoding.uuidToBytes("not-a-uuid") }
    }

    @Test
    fun `percent encode keeps only the unreserved set`() {
        assertEquals("abcXYZ019-._~", RegEncoding.percentEncode("abcXYZ019-._~".toByteArray()))
        assertEquals("%20", RegEncoding.percentEncode(" ".toByteArray()))
        assertEquals("%2B", RegEncoding.percentEncode("+".toByteArray()))
        assertEquals("%2F", RegEncoding.percentEncode("/".toByteArray()))
        assertEquals("%3D", RegEncoding.percentEncode("=".toByteArray()))
        assertEquals("%00%FF", RegEncoding.percentEncode(byteArrayOf(0, -1)))
        assertEquals("", RegEncoding.percentEncode(byteArrayOf()))
        assertEquals("a%20b", RegEncoding.percentEncode("a b".toByteArray()))
    }

    @Test
    fun `result success predicates match their endpoints`() {
        assertTrue(RegistrationHttpClient.CodeResult("sent", null, null, null, null, null, "").ok)
        assertTrue(RegistrationHttpClient.CodeResult("ok", null, null, null, null, null, "").ok)
        assertFalse(RegistrationHttpClient.CodeResult("error", null, null, null, null, null, "").ok)
        assertTrue(RegistrationHttpClient.RegisterResult("ok", null, null, null, null, null, null, "").ok)
        assertFalse(RegistrationHttpClient.RegisterResult("fail", null, null, null, null, null, null, "").ok)
        assertTrue(RegistrationHttpClient.ConsentResult("ok", null, null, null, "").ok)
        assertTrue(RegistrationHttpClient.AutoconfResult("sent", null, null, "").ok)
        assertFalse(RegistrationHttpClient.AutoconfResult("error", null, null, "").ok)
        assertTrue(RegistrationHttpClient.AutoconfVerifierResult("ok", null, "").ok)
        assertTrue(RegistrationHttpClient.CaptchaVerifyResult("verified", null, null, null, "").ok)
        assertFalse(RegistrationHttpClient.CaptchaVerifyResult("ok", null, null, null, "").ok)
        assertTrue(RegistrationHttpClient.ChallengeResult("ok", null, "").ok)
        assertTrue(RegistrationHttpClient.ClientLogResult("ok", "").ok)
        assertFalse(RegistrationHttpClient.ClientLogResult("error", "").ok)
        val exist = RegistrationHttpClient.ExistResult("ok", null, null, "raw-body")
        assertEquals("raw-body", exist.raw)
        // Result data-class semantics.
        assertEquals(
            RegistrationHttpClient.CodeResult("sent", "sms", 6, null, null, null, ""),
            RegistrationHttpClient.CodeResult("sent", "sms", 6, null, null, null, ""),
        )
    }
}
