package com.vayunmathur.communicate.whatsapp

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.communicate.data.whatsapp.registration.RegistrationAttestation
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * Slice 1 (w5): registration attestation vectors (RegistrationAttestation.kt).
 * Robolectric (assets + Android Base64/Log).
 *
 * - computeToken is deterministic per number, differs per number, and is
 *   standard-base64 (NO_WRAP) of 20 HMAC-SHA1 bytes (28 chars incl. padding).
 * - signWithAttestation(key, body) matches a reference HMAC-SHA256.
 * - md5 matches the platform digest.
 */
@RunWith(AndroidJUnit4::class)
class W5Slice1AttestationTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun computeToken_deterministicAndNumberBound() {
        val a = RegistrationAttestation.computeToken(context, "2024561111")
        val b = RegistrationAttestation.computeToken(context, "2024561111")
        assertEquals(a, b)
        // 20 bytes -> 28 base64 chars with single padding
        assertEquals(28, a.length)
        assertTrue(a.endsWith("="), a)
        val other = RegistrationAttestation.computeToken(context, "2024561112")
        assertTrue(a != other)
    }

    @Test
    fun signWithAttestation_matchesReferenceHmac() {
        val key = ByteArray(32) { it.toByte() }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val expected = android.util.Base64.encodeToString(
            mac.doFinal("hello body".toByteArray(Charsets.UTF_8)),
            android.util.Base64.NO_WRAP,
        )
        assertEquals(expected, RegistrationAttestation.signWithAttestation(key, "hello body"))
    }

    @Test
    fun md5_matchesPlatform() {
        val expected = MessageDigest.getInstance("MD5").digest("abc".toByteArray())
        assertTrue(RegistrationAttestation.md5("abc".toByteArray()).contentEquals(expected))
        assertEquals(16, RegistrationAttestation.md5(ByteArray(0)).size)
    }
}
