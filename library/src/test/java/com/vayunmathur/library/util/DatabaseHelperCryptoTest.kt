package com.vayunmathur.library.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** DatabaseHelper AndroidKeyStore passphrase lifecycle. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseHelperCryptoTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    companion object {
        @org.junit.BeforeClass @JvmStatic fun installKeyStore() = FakeAndroidKeyStore.install()
    }

    @Test fun passphraseRoundTripsThroughKeystore() {
        val helper = DatabaseHelper(ctx)
        helper.deleteKey()
        assertFalse(helper.isKeyGenerated())
        helper.storePassphrase("correct horse battery staple")
        assertTrue(helper.isKeyGenerated())
        assertEquals("correct horse battery staple", helper.getPassphrase())
        // Overwriting stores the new passphrase under a fresh IV.
        helper.storePassphrase("second secret")
        assertEquals("second secret", helper.getPassphrase())
        helper.deleteKey()
        assertFalse(helper.isKeyGenerated())
    }

    private class CustomHelper(context: Context) : DatabaseHelper(context) {
        override val keyStoreAlias = "custom_alias"
        override val sharedPrefsName = "custom_prefs"
        override val passphraseKey = "custom_pass"
        override val ivKey = "custom_iv"
    }

    @Test fun orphanKeyWithoutIvIsCleanedUp() {
        val helper = DatabaseHelper(ctx)
        helper.deleteKey()
        helper.generateKey() // key only: no IV/passphrase persisted yet
        // isKeyGenerated treats a key with no IV as corruption and deletes it.
        assertFalse(helper.isKeyGenerated())
        assertFalse(helper.isKeyGenerated())
    }

    @Test fun customAliasAndPrefsAreIndependent() {
        val custom = CustomHelper(ctx)
        val standard = DatabaseHelper(ctx)
        custom.deleteKey(); standard.deleteKey()
        custom.storePassphrase("custom secret")
        assertEquals("custom secret", custom.getPassphrase())
        // The default helper reads different prefs, so it has no IV to decrypt with.
        try {
            standard.getPassphrase()
            throw AssertionError("expected default helper to have no passphrase")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("not found"))
        }
        custom.deleteKey(); standard.deleteKey()
    }

    @Test fun createAndStorePassphraseGeneratesDecryptableSecret() {
        val helper = DatabaseHelper(ctx)
        helper.deleteKey()
        helper.generateKey()
        val generated = helper.createAndStorePassphrase(helper.getCipherForEncryption())
        assertTrue(generated.isNotBlank())
        assertEquals(generated, helper.decryptPassphrase(helper.getCipherForDecryption()))
        helper.deleteKey()
        assertFalse(helper.isKeyGenerated())
    }
}
