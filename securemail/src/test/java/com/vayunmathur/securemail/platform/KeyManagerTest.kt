package com.vayunmathur.securemail.platform

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.securemail.FakeAndroidKeyStore
import com.vayunmathur.securemail.platform.crypto.KeyManager
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class KeyManagerTest {

    @Before fun setUp() = FakeAndroidKeyStore.install()

    private fun km() = KeyManager(ApplicationProvider.getApplicationContext())

    @Test fun secretArmorRoundTripsThroughKeystoreWrap() {
        val manager = km()
        assertFalse(manager.hasIdentity())
        manager.storeSecretArmor("-----BEGIN PGP PRIVATE KEY BLOCK-----\nabc\n-----END-----")
        assertTrue(manager.hasIdentity())
        assertEquals(
            "-----BEGIN PGP PRIVATE KEY BLOCK-----\nabc\n-----END-----",
            manager.getSecretArmor(),
        )
        // A fresh instance reads the same sealed value.
        assertEquals(manager.getSecretArmor(), km().getSecretArmor())
    }

    @Test fun s2kPassphraseRoundTripsAndDefaultsNull() {
        val manager = km()
        assertNull(manager.getKeyPassphrase())
        manager.storeKeyPassphrase("correct horse battery staple")
        assertEquals("correct horse battery staple", manager.getKeyPassphrase())
    }

    @Test fun relayTokenAndAddressRoundTrip() {
        val manager = km()
        assertNull(manager.getRelayToken())
        assertNull(manager.getOwnAddress())
        manager.storeRelayToken("tok-123")
        manager.storeOwnAddress("me@maemail.org")
        assertEquals("tok-123", manager.getRelayToken())
        assertEquals("me@maemail.org", manager.getOwnAddress())
    }

    @Test fun clearRemovesEverything() {
        val manager = km()
        manager.storeSecretArmor("secret")
        manager.storeKeyPassphrase("pw")
        manager.storeRelayToken("tok")
        manager.storeOwnAddress("me@maemail.org")
        manager.clear()
        assertFalse(manager.hasIdentity())
        assertNull(manager.getRelayToken())
        assertNull(manager.getOwnAddress())
        assertNull(manager.getKeyPassphrase())
    }
}
