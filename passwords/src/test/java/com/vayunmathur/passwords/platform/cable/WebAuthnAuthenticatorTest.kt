package com.vayunmathur.passwords.platform.cable

import com.vayunmathur.passwords.platform.PasskeyUtils
import kotlinx.coroutines.runBlocking
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebAuthnAuthenticatorTest {

    @Test fun createCredentialPersistsAndBuildsAttestedData() = runBlocking {
        val store = FakePasskeyStore()
        val created = WebAuthnAuthenticator.createCredential(
            rpId = "example.com",
            rpName = "Example",
            userId = "user-1".toByteArray(),
            userName = "alice",
            userDisplayName = "Alice",
            aaguid = ByteArray(16),
            store = store,
        )
        assertEquals(1, store.store.size)
        assertEquals("example.com", created.passkey.rpId)
        assertEquals(16, created.credentialId.size)
        // 37-byte header + 16 aaguid + 2 credIdLen + 16 credId + COSE key.
        assertEquals(37 + 16 + 2 + 16, created.authenticatorData.size - created.coseKey.size)
        assertTrue(created.publicKeySpki.isNotEmpty())
        // AT flag set.
        assertTrue(created.authenticatorData[32].toInt() and 0x40 != 0)
    }

    @Test fun reRegisteringSameUserReplacesInPlace() = runBlocking {
        val store = FakePasskeyStore()
        val first = WebAuthnAuthenticator.createCredential(
            "example.com", "Example", "u".toByteArray(), "alice", "Alice", ByteArray(16), store,
        )
        val second = WebAuthnAuthenticator.createCredential(
            "example.com", "Example", "u".toByteArray(), "alice", "Alice", ByteArray(16), store,
        )
        assertEquals(1, store.store.size)
        assertEquals(first.passkey.id, second.passkey.id)
        assertEquals(first.passkey.syncId, second.passkey.syncId)
    }

    @Test fun signAssertionIncrementsCountAndVerifies() = runBlocking {
        val store = FakePasskeyStore()
        val created = WebAuthnAuthenticator.createCredential(
            "example.com", "Example", "u".toByteArray(), "alice", "Alice", ByteArray(16), store,
        )
        val hash = ByteArray(32) { it.toByte() }
        val result = WebAuthnAuthenticator.signAssertion(created.passkey, hash, store)
        assertEquals(1, result.newSignCount)
        assertEquals(1, store.store.single().signCount)
        assertEquals(37, result.authenticatorData.size)

        val publicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(created.publicKeySpki))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update(result.authenticatorData + hash)
        assertTrue(verifier.verify(result.signature))
    }

    @Test fun signWithPasskeyProducesVerifiableSignature() = runBlocking {
        val store = FakePasskeyStore()
        val created = WebAuthnAuthenticator.createCredential(
            "example.com", "Example", "u".toByteArray(), "alice", "Alice",
            PasskeyUtils.SAME_DEVICE_AAGUID, store,
        )
        val signature = WebAuthnAuthenticator.signWithPasskey(created.passkey, "data".toByteArray())
        val publicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(created.publicKeySpki))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey)
        verifier.update("data".toByteArray())
        assertTrue(verifier.verify(signature))
    }
}
