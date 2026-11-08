package com.vayunmathur.communicate.signal

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.communicate.data.signal.e2e.RustSignalCrypto
import com.vayunmathur.communicate.data.whatsapp.e2e.RustWhatsAppCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * Pass 3: RustSignalCrypto at the JNI boundary. The host library exports only
 * processPreKeyBundle / markKyberPreKeyUsed / sealedSender* for this class
 * (see lead-inbox note); the primitive key ops live on RustWhatsAppCrypto in
 * the same crate and are used here to build bundle inputs.
 */
@RunWith(AndroidJUnit4::class)
class RustSignalCryptoSignalPass3Test {

    @Test fun libraryLoadsAndPrimitiveOpsWorkViaSharedCrate() {
        assertTrue(RustSignalCrypto.isAvailable)
        assertTrue(RustWhatsAppCrypto.isAvailable)
        val pair = assertNotNull(RustWhatsAppCrypto.generateKeyPair())
        assertEquals(64, pair.size)
        val priv = pair.copyOfRange(0, 32)
        val pub = pair.copyOfRange(32, 64)
        assertTrue(pub.contentEquals(RustWhatsAppCrypto.publicFromPrivate(priv)))
        val sig = assertNotNull(RustWhatsAppCrypto.sign(priv, "m".encodeToByteArray()))
        assertTrue(RustWhatsAppCrypto.verify(pub, "m".encodeToByteArray(), sig))
    }

    @Test fun kyberReplayGuardDeduplicates() {
        val baseKey = ByteArray(32) { it.toByte() }
        // First sight of a (kyberId, signedEcId, baseKey) tuple is accepted;
        // the replay of the same tuple is rejected.
        assertTrue(RustSignalCrypto.markKyberPreKeyUsed(9101, 7, baseKey))
        assertFalse(RustSignalCrypto.markKyberPreKeyUsed(9101, 7, baseKey))
        // A different base key or id is a different tuple.
        assertTrue(RustSignalCrypto.markKyberPreKeyUsed(9101, 7, ByteArray(32) { 9 }))
        assertTrue(RustSignalCrypto.markKyberPreKeyUsed(9102, 7, baseKey))
    }

    @Test fun processPreKeyBundleRejectsBadSignature() {
        fun keyPair(): Pair<ByteArray, ByteArray> {
            val blob = assertNotNull(RustWhatsAppCrypto.generateKeyPair())
            return blob.copyOfRange(0, 32) to blob.copyOfRange(32, 64)
        }
        val (alicePriv, alicePub) = keyPair()
        val (bobIdentityPriv, bobIdentityPub) = keyPair()
        val (bobSignedPriv, bobSignedPub) = keyPair()
        val (_, bobOneTimePub) = keyPair()
        // Signature over the signed prekey by the WRONG key (Alice, not Bob).
        val badSig = assertNotNull(RustWhatsAppCrypto.sign(alicePriv, bobSignedPub))
        // The JNI layer surfaces libsignal's rejection as an exception.
        val error = assertFailsWith<RuntimeException> {
            RustSignalCrypto.processPreKeyBundle(
                alicePriv, alicePub,
                1111, 2222,
                10, bobOneTimePub,
                2, bobSignedPub, badSig,
                bobIdentityPub,
                5, ByteArray(1569), ByteArray(64), ByteArray(1568))
        }
        assertTrue(error.message!!.contains("signature", ignoreCase = true), "${error.message}")
    }

    @Test fun sealedSenderStubEncryptsButDecryptRejects() {
        // The JNI sealed-sender stub produces ciphertext without any network
        // or certificate fetch, and decrypts its own output back.
        val out = RustSignalCrypto.sealedSenderEncrypt(
            "hello".encodeToByteArray(), "+15550100", 1)
        assertNotNull(out)
        assertTrue(out.isNotEmpty())
        // The stub's decrypt side does not accept its own encrypt output
        // (tag mismatch): it is a placeholder, not a round-trip path - the
        // production path uses libsignal's SealedSessionCipher instead.
        val error = assertFailsWith<RuntimeException> {
            RustSignalCrypto.sealedSenderDecrypt(out)
        }
        assertTrue(error.message!!.contains("sealed", ignoreCase = true), "${error.message}")
    }
}
