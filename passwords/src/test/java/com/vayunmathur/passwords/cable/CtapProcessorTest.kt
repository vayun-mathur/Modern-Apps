package com.vayunmathur.passwords.cable

import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.PasskeyDao
import com.vayunmathur.passwords.util.Cbor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * End-to-end test of the caBLE getAssertion path (Phases 0/1/8): a real P-256 passkey is stored,
 * a CTAP getAssertion is processed, and the returned assertion signature is verified against the
 * stored public key. Proves the cross-device signer matches WebAuthn assertion semantics.
 */
class CtapProcessorTest {

    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

    private class FakePasskeyDao(initial: List<Passkey>) : PasskeyDao {
        val store = initial.toMutableList()
        override fun getAllFlow(): Flow<List<Passkey>> = flowOf(store.toList())
        override suspend fun getAll(): List<Passkey> = store.toList()
        override suspend fun getByRpId(rpId: String): List<Passkey> = store.filter { it.rpId == rpId }
        override suspend fun getByCredentialId(credentialId: String): Passkey? =
            store.firstOrNull { it.credentialId == credentialId }
        override suspend fun upsert(passkey: Passkey): Long {
            store.removeAll { it.id == passkey.id }
            store.add(passkey)
            return passkey.id
        }
        override suspend fun delete(passkey: Passkey): Int {
            return if (store.removeAll { it.id == passkey.id }) 1 else 0
        }
    }

    @Test fun getAssertionProducesVerifiableSignature() = runBlocking {
        val kp = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        val credIdBytes = ByteArray(16) { it.toByte() }
        val userIdBytes = byteArrayOf(9, 8, 7, 6)
        val passkey = Passkey(
            id = 1,
            rpId = "example.com",
            credentialId = urlEncoder.encodeToString(credIdBytes),
            userId = urlEncoder.encodeToString(userIdBytes),
            privateKeyBytes = kp.private.encoded,
            signCount = 5,
        )
        val dao = FakePasskeyDao(listOf(passkey))
        val aaguid = ByteArray(16) { (it + 100).toByte() }
        val processor = CtapProcessor(dao, aaguid, userVerified = true)

        val clientDataHash = MessageDigest.getInstance("SHA-256").digest("hello".toByteArray())
        val request = Cbor.encode(linkedMapOf<Any, Any>(
            1L to "example.com",
            2L to clientDataHash,
            5L to linkedMapOf<Any, Any>("up" to true, "uv" to true),
        ))
        val response = processor.process(byteArrayOf(Ctap.CMD_GET_ASSERTION.toByte()) + request)

        assertEquals(Ctap.OK.toByte(), response[0])
        val map = CborReader.decode(response.copyOfRange(1, response.size)) as Map<*, *>

        val cred = map[1L] as Map<*, *>
        assertArrayEquals(credIdBytes, cred["id"] as ByteArray)
        val authData = map[2L] as ByteArray
        val signature = map[3L] as ByteArray
        val user = map[4L] as Map<*, *>
        assertArrayEquals(userIdBytes, user["id"] as ByteArray)

        // The signature must verify over authData || clientDataHash against the stored public key.
        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(kp.public)
            update(authData)
            update(clientDataHash)
        }
        assertTrue("assertion signature must verify", verifier.verify(signature))

        // signCount was bumped and persisted.
        assertEquals(6, dao.getByCredentialId(passkey.credentialId)!!.signCount)
    }

    @Test fun getAssertionNoCredentialReturnsError() = runBlocking {
        val dao = FakePasskeyDao(emptyList())
        val processor = CtapProcessor(dao, ByteArray(16), userVerified = true)
        val request = Cbor.encode(linkedMapOf<Any, Any>(1L to "nobody.example", 2L to ByteArray(32)))
        val response = processor.process(byteArrayOf(Ctap.CMD_GET_ASSERTION.toByte()) + request)
        assertEquals(Ctap.ERR_NO_CREDENTIALS.toByte(), response[0])
    }

    @Test fun getInfoReturnsAaguid() = runBlocking {
        val dao = FakePasskeyDao(emptyList())
        val aaguid = ByteArray(16) { it.toByte() }
        val processor = CtapProcessor(dao, aaguid, userVerified = false)
        val response = processor.process(byteArrayOf(Ctap.CMD_GET_INFO.toByte()))
        assertEquals(Ctap.OK.toByte(), response[0])
        val map = CborReader.decode(response.copyOfRange(1, response.size)) as Map<*, *>
        assertArrayEquals(aaguid, map[3L] as ByteArray)
    }
}
