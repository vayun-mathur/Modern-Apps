package com.vayunmathur.passwords.platform.cable

import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.domain.Cbor
import kotlinx.coroutines.runBlocking
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class CtapProcessorExtraTest {

    private val urlEncoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    private val hash = MessageDigest.getInstance("SHA-256").digest("h".toByteArray())

    private fun storedPasskey(
        id: Long,
        rpId: String,
        credBytes: ByteArray,
        lastUsed: Long = 0,
        userId: String = "dXNlcg", // "user"
    ): Passkey {
        val kp = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        return Passkey(
            id = id, rpId = rpId, credentialId = urlEncoder.encode(credBytes),
            userId = userId, userName = "user-$id", privateKeyBytes = kp.private.encoded,
            signCount = 0, lastUsedTime = lastUsed, syncId = "pk-$id",
        )
    }

    private fun assertionRequest(
        rpId: String,
        allow: List<ByteArray> = emptyList(),
        uv: Boolean = false,
    ): ByteArray {
        val map = linkedMapOf<Any, Any>(1L to rpId, 2L to hash)
        if (allow.isNotEmpty()) {
            map[3L] = allow.map { linkedMapOf<Any, Any>("id" to it, "type" to "public-key") }
        }
        map[5L] = linkedMapOf<Any, Any>("up" to true, "uv" to uv)
        return byteArrayOf(Ctap.CMD_GET_ASSERTION.toByte()) + Cbor.encode(map)
    }

    @Test fun emptyAndUnknownCommandsReturnStatusBytes() = runBlocking {
        val processor = CtapProcessor(FakePasskeyStore(), userVerified = true)
        assertEquals(Ctap.ERR_INVALID_CBOR.toByte(), processor.process(ByteArray(0))[0])
        assertEquals(Ctap.ERR_NOT_ALLOWED.toByte(), processor.process(byteArrayOf(0x7F))[0])
        assertEquals(Ctap.ERR_NOT_ALLOWED.toByte(), processor.process(byteArrayOf(Ctap.CMD_GET_NEXT_ASSERTION.toByte()))[0])
        // A getInfo command with trailing garbage payload still answers getInfo.
        assertEquals(Ctap.OK.toByte(), processor.process(byteArrayOf(Ctap.CMD_GET_INFO.toByte(), 0xFF.toByte()))[0])
    }

    @Test fun getAssertionUvRequiredButUnverifiedIsDeniedBeforeLookup() = runBlocking {
        val cred = ByteArray(16) { 1 }
        val store = FakePasskeyStore(listOf(storedPasskey(1, "example.com", cred)))
        val processor = CtapProcessor(store, userVerified = false)
        val response = processor.process(assertionRequest("example.com", uv = true))
        assertEquals(Ctap.ERR_OPERATION_DENIED.toByte(), response[0])
        // Denial happens before signing: the counter must not move.
        assertEquals(0, store.store.single().signCount)
    }

    @Test fun allowListSelectsMatchingCredentialAndIgnoresOthers() = runBlocking {
        val credA = ByteArray(16) { 0x0A }
        val credB = ByteArray(16) { 0x0B }
        val a = storedPasskey(1, "example.com", credA, lastUsed = 9_999)
        val b = storedPasskey(2, "example.com", credB, lastUsed = 1)
        val store = FakePasskeyStore(listOf(a, b))
        val processor = CtapProcessor(store, userVerified = true)

        // allowList wins over recency: B is older but explicitly allowed.
        val response = processor.process(assertionRequest("example.com", allow = listOf(credB)))
        assertEquals(Ctap.OK.toByte(), response[0])
        assertEquals(1, store.store.first { it.id == 2L }.signCount)
        assertEquals(0, store.store.first { it.id == 1L }.signCount)

        // An allowList naming only a credential for another RP matches nothing here.
        val foreign = processor.process(assertionRequest("other.com", allow = listOf(credA)))
        assertEquals(Ctap.ERR_NO_CREDENTIALS.toByte(), foreign[0])
        // Unknown allowList ids are skipped, not fatal, when nothing else matches.
        val unknown = processor.process(assertionRequest("example.com", allow = listOf(ByteArray(16) { 0x7E })))
        assertEquals(Ctap.ERR_NO_CREDENTIALS.toByte(), unknown[0])
    }

    @Test fun emptyAllowListPicksMostRecentlyUsed() = runBlocking {
        val older = storedPasskey(1, "example.com", ByteArray(16) { 0x0A }, lastUsed = 10)
        val newer = storedPasskey(2, "example.com", ByteArray(16) { 0x0B }, lastUsed = 20)
        val store = FakePasskeyStore(listOf(older, newer))
        val response = CtapProcessor(store, true).process(assertionRequest("example.com"))
        assertEquals(Ctap.OK.toByte(), response[0])
        assertEquals(1, store.store.first { it.id == 2L }.signCount)
        assertEquals(0, store.store.first { it.id == 1L }.signCount)
    }

    @Test fun assertionWithRawUtf8UserIdFallsBackGracefully() = runBlocking {
        // A stored user handle that is not valid base64url is echoed as raw UTF-8 bytes.
        val cred = ByteArray(16) { 0x0C }
        val pk = storedPasskey(1, "example.com", cred, userId = "raw user!")
        val store = FakePasskeyStore(listOf(pk))
        val response = CtapProcessor(store, true).process(assertionRequest("example.com"))
        assertEquals(Ctap.OK.toByte(), response[0])
        assertTrue(response.size > 1)
    }

    @Test fun malformedAssertionPayloadMapsToErrorStatus() = runBlocking {
        val processor = CtapProcessor(FakePasskeyStore(), userVerified = true)
        // Command byte + truncated CBOR map: parse failure must surface as a CTAP
        // status byte (ERR_OTHER via the processor's catch), never as a throw.
        val response = processor.process(byteArrayOf(Ctap.CMD_GET_ASSERTION.toByte(), 0xA1.toByte()))
        assertTrue(response.isNotEmpty())
        assertTrue(response[0] != Ctap.OK.toByte())
    }

    @Test fun makeCredentialMissingParametersMapsToMissingParameter() = runBlocking {
        val processor = CtapProcessor(FakePasskeyStore(), userVerified = true)
        val emptyMap = byteArrayOf(Ctap.CMD_MAKE_CREDENTIAL.toByte()) + Cbor.encode(emptyMap<Any, Any>())
        assertEquals(Ctap.ERR_MISSING_PARAMETER.toByte(), processor.process(emptyMap)[0])
    }
}
