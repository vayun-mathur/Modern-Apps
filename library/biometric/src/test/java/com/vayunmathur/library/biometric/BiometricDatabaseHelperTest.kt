package com.vayunmathur.library.biometric

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.security.KeyStore
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Tests for [BiometricDatabaseHelper] over an in-memory fake AndroidKeyStore. */
@RunWith(RobolectricTestRunner::class)
class BiometricDatabaseHelperTest {

    @Before
    fun setUp() {
        FakeAndroidKeyStore.install()
        // Start clean: the fake keystore is process-global.
        FakeAndroidKeyStore.remove("db_auth_key")
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun helper() = BiometricDatabaseHelper(ctx)

    private fun keyStoreHas(alias: String): Boolean {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        return ks.containsAlias(alias)
    }

    @Test
    fun generateKeyCreatesAuthKeyUnderBiometricAlias() {
        val helper = helper()
        helper.generateKey()
        assertTrue(keyStoreHas("db_auth_key"))
        assertFalse(keyStoreHas("db_no_auth_key"), "base-class alias must not be used")
    }

    @Test
    fun keyWithoutIvIsOrphanedAndCleanedUp() {
        val helper = helper()
        helper.generateKey()
        // No IV has been persisted yet, so the key counts as not generated and
        // isKeyGenerated deletes the orphaned key.
        assertFalse(helper.isKeyGenerated())
        assertFalse(keyStoreHas("db_auth_key"))
    }

    @Test
    fun storeAndGetPassphraseRoundTrips() {
        val helper = helper()
        helper.storePassphrase("correct horse battery staple")
        assertTrue(helper.isKeyGenerated())
        assertEquals("correct horse battery staple", helper.getPassphrase())
        // Stored under the biometric prefs keys, not the base-class ones.
        val prefs = ctx.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)
        assertTrue(prefs.contains("encrypted_passphrase"))
        assertTrue(prefs.contains("passphrase_iv"))
        assertFalse(prefs.contains("encrypted_passphrase_no_auth"))
        // The stored value is ciphertext, not the passphrase itself.
        assertNotEquals(
            "correct horse battery staple",
            prefs.getString("encrypted_passphrase", null),
        )
    }

    @Test
    fun createAndStorePassphraseReturnsDecryptableSecret() {
        val helper = helper()
        helper.generateKey()
        val passphrase = helper.createAndStorePassphrase(helper.getCipherForEncryption())
        assertTrue(passphrase.isNotBlank())
        val decrypted = helper.decryptPassphrase(helper.getCipherForDecryption())
        assertEquals(passphrase, decrypted)
    }

    @Test
    fun deleteKeyRemovesKeyAndPrefs() {
        val helper = helper()
        helper.storePassphrase("to-delete")
        assertTrue(helper.isKeyGenerated())
        helper.deleteKey()
        assertFalse(helper.isKeyGenerated())
        assertFalse(keyStoreHas("db_auth_key"))
        val prefs = ctx.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)
        assertFalse(prefs.contains("encrypted_passphrase"))
        assertFalse(prefs.contains("passphrase_iv"))
    }

    @Test
    fun decryptWithoutPassphraseThrows() {
        val helper = helper()
        helper.generateKey()
        helper.storePassphrase("x")
        // Remove just the passphrase, leaving the IV: decryption must fail loudly.
        ctx.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)
            .edit().remove("encrypted_passphrase").commit()
        assertFailsWith<IllegalStateException> {
            helper.decryptPassphrase(helper.getCipherForDecryption())
        }
    }

    @Test
    fun decryptionCipherWithoutIvThrows() {
        val helper = helper()
        helper.generateKey()
        assertFailsWith<IllegalStateException> { helper.getCipherForDecryption() }
    }

    @Test
    fun tamperedCiphertextDoesNotDecryptToPassphrase() {
        val helper = helper()
        helper.storePassphrase("sensitive")
        val prefs = ctx.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)
        val stored = prefs.getString("encrypted_passphrase", null)!!
        // Flip the stored ciphertext; GCM must reject it rather than return garbage.
        val tampered = (if (stored.first() == 'A') 'B' else 'A') + stored.substring(1)
        prefs.edit().putString("encrypted_passphrase", tampered).commit()
        runCatching { helper.getPassphrase() }
            .onSuccess { throw AssertionError("tampered ciphertext decrypted to: $it") }
    }
}
