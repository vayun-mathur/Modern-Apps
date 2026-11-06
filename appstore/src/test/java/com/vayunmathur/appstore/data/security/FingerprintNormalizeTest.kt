package com.vayunmathur.appstore.data.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fingerprint normalisation: F-Droid publishes lowercase hex while Play's
 * `CertificateSet.sha256` is base64url of the raw 32 bytes. Both must land on
 * the same lowercase-hex value or the signer comparison can never match.
 */
@RunWith(RobolectricTestRunner::class)
class FingerprintNormalizeTest {

    private val hex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Test
    fun `lowercase hex passes through unchanged`() {
        assertEquals(hex, ApkCertificates.normalizeFingerprint(hex))
    }

    @Test
    fun `uppercase hex is lowercased`() {
        assertEquals(hex, ApkCertificates.normalizeFingerprint(hex.uppercase()))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(hex, ApkCertificates.normalizeFingerprint("  $hex\n"))
    }

    @Test
    fun `base64url of the raw bytes decodes to the same hex`() {
        // base64url(SHA-256("")) == "47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU".
        val base64url = "47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU"

        assertEquals(hex, ApkCertificates.normalizeFingerprint(base64url))
    }

    @Test
    fun `wrong-length values are rejected`() {
        assertNull(ApkCertificates.normalizeFingerprint("abcd"))
        assertNull(ApkCertificates.normalizeFingerprint("z".repeat(64)))
        assertNull(ApkCertificates.normalizeFingerprint(""))
    }

    @Test
    fun `file hashing matches the known empty-input vector`() {
        val file = kotlin.io.path.createTempFile(suffix = ".bin").toFile()
        try {
            assertEquals(hex, ApkCertificates.sha256(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `abbreviation shows the first eight bytes`() {
        assertEquals("E3:B0:C4:42:98:FC:1C:14", ApkCertificates.abbreviate(hex))
    }

    // --- Signify framing ---

    @Test
    fun `garbage keys and signatures fail closed`() {
        assertFalse(Signify.verify(ByteArray(4), "not-base64!!", "also-not-base64!!"))
        assertFalse(Signify.verify(ByteArray(4), "", ""))
    }

    @Test
    fun `a short key blob fails closed`() {
        val short = android.util.Base64.encodeToString(ByteArray(8), android.util.Base64.NO_WRAP)

        assertFalse(Signify.verify("msg".toByteArray(), short, short))
    }

    @Test
    fun `a comment-only signature file fails closed`() {
        val key = android.util.Base64.encodeToString(ByteArray(42), android.util.Base64.NO_WRAP)

        assertFalse(Signify.verify("msg".toByteArray(), "untrusted comment: sign\n", key))
    }

    @Test
    fun `a well-framed but wrong signature fails verification`() {
        // 74 zero bytes: parses as a signature blob, but ed25519 rejects it.
        val zeros74 = android.util.Base64.encodeToString(ByteArray(74), android.util.Base64.NO_WRAP)
        val zeros42 = android.util.Base64.encodeToString(ByteArray(42), android.util.Base64.NO_WRAP)
        val withComment = "untrusted comment: verify with signify\n$zeros74\n"

        assertFalse(Signify.verify("hello".toByteArray(), withComment, zeros42))
        assertTrue(Signify.verify("hello".toByteArray(), withComment, zeros42) == false)
    }
}
