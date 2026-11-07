package com.vayunmathur.communicate.data

import com.vayunmathur.communicate.data.rcs.e2e.RustMlsCrypto
import com.vayunmathur.communicate.data.whatsapp.e2e.RustWhatsAppCrypto
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The host cdylibs export the JNI bridges, so JVM tests exercise the real Rust crypto.
 * Robolectric: the loaders log through android.util.Log.
 */
@RunWith(AndroidJUnit4::class)
class HostNativeCryptoTest {

    @Test
    fun whatsAppX25519AgreementMatchesFromBothSides() {
        assertTrue(RustWhatsAppCrypto.isAvailable)
        val alice = assertNotNull(RustWhatsAppCrypto.generateKeyPair())
        val bob = assertNotNull(RustWhatsAppCrypto.generateKeyPair())
        assertEquals(64, alice.size)
        val (alicePriv, alicePub) = alice.copyOfRange(0, 32) to alice.copyOfRange(32, 64)
        val (bobPriv, bobPub) = bob.copyOfRange(0, 32) to bob.copyOfRange(32, 64)

        assertContentEquals(alicePub, RustWhatsAppCrypto.publicFromPrivate(alicePriv))
        val shared = assertNotNull(RustWhatsAppCrypto.x25519Agreement(alicePriv, bobPub))
        assertContentEquals(shared, RustWhatsAppCrypto.x25519Agreement(bobPriv, alicePub))
    }

    @Test
    fun mlsGeneratesAnIdentity() {
        assertTrue(RustMlsCrypto.isAvailable)
        val out = assertNotNull(RustMlsCrypto.generateIdentity("+15550100".encodeToByteArray()))
        assertEquals(2, out.size)
        assertTrue(out[1].isNotEmpty())
    }
}
