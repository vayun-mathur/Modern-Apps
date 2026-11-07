package com.vayunmathur.findfamily.util

import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Networking entry points before any identity exists (pqcReady=false):
 * every PQC-gated helper must degrade to null/UNKNOWN, never throw.
 */
class NetworkingOfflineTest {

    private fun user(key: String? = null) = User(
        name = "Ann", photo = null, locationName = "Home",
        sendingEnabled = true, requestStatus = RequestStatus.MUTUAL_CONNECTION,
        pqcEncryptionKey = key)

    @Test fun securityCodeWithoutKeyOrIdentityIsNull() = runTest {
        assertNull(Networking.securityCode(user()))
        assertNull(Networking.securityCodePqc(user()))
        // Even with a cached bundle, an uninitialised identity yields null.
        assertNull(Networking.securityCodePqc(user(key = "a2V5")))
        assertNull(Networking.myInviteFingerprint())
        assertNull(Networking.signAsMe("data".encodeToByteArray()))
        assertEquals(0L, Networking.userid)
    }

    @Test fun peerCryptoStatusOfflineIsUnknown() = runTest {
        val check = Networking.peerCryptoStatus(123L)
        assertEquals(Networking.PeerCrypto.UNKNOWN, check.status)
        assertNull(check.verifiedBundleB64)
        val withFingerprint = Networking.peerCryptoStatus(123L, expectedFingerprint = "abc")
        assertEquals(Networking.PeerCrypto.UNKNOWN, withFingerprint.status)
    }

    @Test fun linkKeyGenerationShape() {
        // PQC link keygen runs on the host native build in JVM tests; where
        // the native lib is unavailable this would throw, which is exactly
        // what createTemporaryLink guards against in production.
        val result = runCatching { Networking.generatePqcLinkKey() }
        if (result.isSuccess) {
            val key = result.getOrThrow()
            assertTrue(key.seedB64Url.isNotBlank())
            assertTrue(!key.seedB64Url.contains('='), "seed must be unpadded base64url")
            assertTrue(key.publicBundleB64.isNotBlank())
            assertNotEquals(key.seedB64Url, Networking.generatePqcLinkKey().seedB64Url)
        } else {
            assertTrue(result.exceptionOrNull() is UnsatisfiedLinkError)
        }
    }

    @Test fun identityKeyPairShape() {
        val result = runCatching { Networking.generatePqcIdentityKeyPair() }
        if (result.isSuccess) {
            val pair = result.getOrThrow()
            assertTrue(pair.publicBundleB64.isNotBlank())
            assertTrue(pair.privateBundleB64.isNotBlank())
            assertNotEquals(pair.publicBundleB64, pair.privateBundleB64)
        } else {
            assertTrue(result.exceptionOrNull() is UnsatisfiedLinkError)
        }
    }
}
