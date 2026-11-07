package com.vayunmathur.email.data

import com.vayunmathur.email.FakeAndroidKeyStore
import javax.crypto.AEADBadTagException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [CredentialCrypto] against an in-memory AndroidKeyStore: the account
 * password must round-trip byte-for-byte, each encryption must use a
 * fresh IV (so two encryptions of one password differ), and tampered
 * ciphertext must fail GCM authentication instead of decrypting to
 * garbage a login would silently send.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CredentialCryptoTest {

    @BeforeTest
    fun installKeyStore() {
        FakeAndroidKeyStore.install()
        FakeAndroidKeyStore.clear()
    }

    @Test
    fun `encrypt decrypt round-trips plain and unicode passwords`() {
        val (cipher, iv) = CredentialCrypto.encrypt("s3cret-app-password")
        assertFalse(cipher.contentEquals("s3cret-app-password".toByteArray()))
        assertEquals("s3cret-app-password", CredentialCrypto.decrypt(cipher, iv))
        val (c2, iv2) = CredentialCrypto.encrypt("pâsswörd-🔑")
        assertEquals("pâsswörd-🔑", CredentialCrypto.decrypt(c2, iv2))
    }

    @Test
    fun `empty password round-trips`() {
        val (cipher, iv) = CredentialCrypto.encrypt("")
        assertEquals("", CredentialCrypto.decrypt(cipher, iv))
    }

    @Test
    fun `each encryption uses a fresh iv so ciphertexts differ`() {
        val (c1, iv1) = CredentialCrypto.encrypt("same-password")
        val (c2, iv2) = CredentialCrypto.encrypt("same-password")
        assertFalse(iv1.contentEquals(iv2))
        assertFalse(c1.contentEquals(c2))
        assertTrue(iv1.size == 12)
        // Both still decrypt to the original under their own IV.
        assertEquals("same-password", CredentialCrypto.decrypt(c1, iv1))
        assertEquals("same-password", CredentialCrypto.decrypt(c2, iv2))
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val (cipher, iv) = CredentialCrypto.encrypt("s3cret")
        val tampered = cipher.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFailsWith<AEADBadTagException> { CredentialCrypto.decrypt(tampered, iv) }
    }

    @Test
    fun `wrong iv fails authentication`() {
        val (cipher, _) = CredentialCrypto.encrypt("s3cret")
        val wrongIv = ByteArray(12) { 0x2A }
        assertFailsWith<Exception> { CredentialCrypto.decrypt(cipher, wrongIv) }
    }
}
