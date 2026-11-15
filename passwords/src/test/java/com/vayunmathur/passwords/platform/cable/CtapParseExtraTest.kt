package com.vayunmathur.passwords.platform.cable

import com.vayunmathur.passwords.domain.Cbor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CtapParseExtraTest {

    private val hash = ByteArray(32) { 0x11 }

    // ---- getAssertion parsing ----

    @Test fun getAssertionDefaultsAndFiltering() {
        val payload = Cbor.encode(
            linkedMapOf<Any, Any>(
                1L to "example.com",
                2L to hash,
                3L to listOf(
                    linkedMapOf<Any, Any>("id" to byteArrayOf(1, 2), "type" to "public-key"),
                    linkedMapOf<Any, Any>("type" to "public-key"), // no id: skipped
                    "garbage", // not a map: skipped
                    linkedMapOf<Any, Any>("id" to byteArrayOf(3)), // type defaults
                ),
                5L to linkedMapOf<Any, Any>("up" to false, "junk" to "not-bool", 9L to true),
            ),
        )
        val req = CtapGetAssertionRequest.parse(payload)
        assertEquals("example.com", req.rpId)
        assertEquals(32, req.clientDataHash.size)
        assertEquals(2, req.allowList.size)
        assertEquals(listOf(1.toByte(), 2.toByte()), req.allowList[0].id.toList())
        assertEquals("public-key", req.allowList[1].type)
        // Non-boolean and non-string-keyed options are dropped.
        assertEquals(mapOf("up" to false), req.options)
        assertEquals(false, req.userPresenceRequired)
        assertEquals(false, req.userVerificationRequired)
        assertEquals(null, req.extensions)
    }

    @Test fun getAssertionOptionDefaultsFollowCtap() {
        val payload = Cbor.encode(linkedMapOf<Any, Any>(1L to "a.com", 2L to hash))
        val req = CtapGetAssertionRequest.parse(payload)
        assertTrue(req.allowList.isEmpty())
        assertTrue(req.userPresenceRequired) // up defaults true
        assertEquals(false, req.userVerificationRequired) // uv defaults false
    }

    @Test fun getAssertionMissingRequiredFieldsThrows() {
        assertFailsWith<IllegalStateException> {
            CtapGetAssertionRequest.parse(Cbor.encode(linkedMapOf<Any, Any>(2L to hash)))
        }
        assertFailsWith<IllegalStateException> {
            CtapGetAssertionRequest.parse(Cbor.encode(linkedMapOf<Any, Any>(1L to "a.com")))
        }
    }

    @Test fun getAssertionResponseEncodeIncludesOptionalUserFields() {
        val response = CtapGetAssertionResponse(
            credentialId = byteArrayOf(9),
            authData = byteArrayOf(1, 2),
            signature = byteArrayOf(3, 4),
            userId = byteArrayOf(7, 8),
            userName = "alice",
            userDisplayName = "Alice A",
            numberOfCredentials = 3,
        )
        val map = CborReader.decode(response.encode()) as Map<*, *>
        val user = map[4L] as Map<*, *>
        assertEquals("alice", user["name"])
        assertEquals("Alice A", user["displayName"])
        assertEquals(3L, map[5L])
        val minimal = CtapGetAssertionResponse(byteArrayOf(9), byteArrayOf(1), byteArrayOf(2), byteArrayOf(7))
        val minMap = CborReader.decode(minimal.encode()) as Map<*, *>
        assertEquals(null, minMap[5L])
        assertEquals(setOf("id"), (minMap[4L] as Map<*, *>).keys)
    }

    // ---- makeCredential parsing ----

    private fun makePayload(
        rp: Any? = linkedMapOf<Any, Any>("id" to "example.com"),
        user: Any? = linkedMapOf<Any, Any>("id" to byteArrayOf(5, 6)),
        hashValue: Any? = hash,
        algorithms: Any? = listOf(linkedMapOf<Any, Any>("alg" to -7L, "type" to "public-key")),
    ): ByteArray {
        val map = linkedMapOf<Any, Any>()
        hashValue?.let { map[1L] = it }
        rp?.let { map[2L] = it }
        user?.let { map[3L] = it }
        algorithms?.let { map[4L] = it }
        return Cbor.encode(map)
    }

    @Test fun makeCredentialFallbacksAndDerivedFlags() {
        val req = CtapMakeCredentialRequest.parse(makePayload())
        assertEquals("example.com", req.rpId)
        assertEquals("example.com", req.rpName) // falls back to rpId
        assertEquals("", req.userName)
        assertEquals("", req.userDisplayName) // falls back to userName
        assertEquals(listOf(-7L), req.algorithms)
        assertTrue(req.supportsEs256)
        assertEquals(false, req.residentKey)
        assertEquals(false, req.userVerificationRequired)
        assertEquals(null, req.userPresenceOption)
        assertTrue(req.excludeList.isEmpty())
    }

    @Test fun makeCredentialAlgorithmAndExcludeFiltering() {
        val payload = run {
            val map = linkedMapOf<Any, Any>(
                1L to hash,
                2L to linkedMapOf<Any, Any>("id" to "example.com", "name" to "Example"),
                3L to linkedMapOf<Any, Any>(
                    "id" to byteArrayOf(5), "name" to "alice", "displayName" to "Alice",
                ),
                4L to listOf(
                    linkedMapOf<Any, Any>("alg" to -7L, "type" to "public-key"),
                    linkedMapOf<Any, Any>("alg" to -257L, "type" to "public-key"),
                    linkedMapOf<Any, Any>("alg" to -8L, "type" to "token"), // wrong type: skipped
                    linkedMapOf<Any, Any>("type" to "public-key"), // no alg: skipped
                ),
                5L to listOf(
                    linkedMapOf<Any, Any>("id" to byteArrayOf(1), "type" to "public-key"),
                    linkedMapOf<Any, Any>("type" to "public-key"), // no id: skipped
                ),
                7L to linkedMapOf<Any, Any>("rk" to true, "uv" to true, "up" to false),
            )
            Cbor.encode(map)
        }
        val req = CtapMakeCredentialRequest.parse(payload)
        assertEquals("Example", req.rpName)
        assertEquals("Alice", req.userDisplayName)
        assertEquals(listOf(-7L, -257L), req.algorithms)
        assertEquals(1, req.excludeList.size)
        assertTrue(req.residentKey)
        assertTrue(req.userVerificationRequired)
        assertEquals(false, req.userPresenceOption)
    }

    @Test fun makeCredentialMissingRequiredFieldsThrows() {
        assertFailsWith<IllegalStateException> { CtapMakeCredentialRequest.parse(makePayload(rp = null)) }
        assertFailsWith<IllegalStateException> { CtapMakeCredentialRequest.parse(makePayload(user = null)) }
        assertFailsWith<IllegalStateException> {
            CtapMakeCredentialRequest.parse(makePayload(rp = linkedMapOf<Any, Any>("name" to "x")))
        }
        assertFailsWith<IllegalStateException> {
            CtapMakeCredentialRequest.parse(makePayload(user = linkedMapOf<Any, Any>("name" to "x")))
        }
        assertFailsWith<IllegalStateException> { CtapMakeCredentialRequest.parse(makePayload(hashValue = null)) }
    }

    @Test fun ctapResponseAndDescriptorDefaults() {
        assertEquals(listOf(0.toByte()), Ctap.response(Ctap.OK).toList())
        val withPayload = Ctap.response(Ctap.ERR_OTHER, byteArrayOf(1, 2))
        assertEquals(Ctap.ERR_OTHER.toByte(), withPayload[0])
        assertEquals(3, withPayload.size)
        val descriptor = Ctap.CredentialDescriptor(byteArrayOf(4))
        assertEquals("public-key", descriptor.type)
        assertEquals(16, Ctap.ZERO_AAGUID.size)
        assertTrue(Ctap.ZERO_AAGUID.all { it == 0.toByte() })
    }

    @Test fun getInfoEncodeRoundTripsCustomValues() {
        val info = CtapGetInfoResponse(
            versions = listOf("FIDO_2_0"),
            extensions = emptyList(),
            options = linkedMapOf("uv" to false),
            transports = listOf("internal"),
        )
        val map = CborReader.decode(info.encode()) as Map<*, *>
        assertEquals(listOf("FIDO_2_0"), map[1L])
        assertEquals(emptyList<String>(), map[2L])
        assertEquals(mapOf("uv" to false), map[4L])
        assertEquals(listOf("internal"), map[9L])
        // MakeCredential response encode: fmt/authData/attStmt at keys 1/2/3.
        val mc = CtapMakeCredentialResponse(authData = byteArrayOf(8), fmt = "packed")
        val mcMap = CborReader.decode(mc.encode()) as Map<*, *>
        assertEquals("packed", mcMap[1L])
        assertEquals(listOf(8.toByte()), (mcMap[2L] as ByteArray).toList())
    }

    @Test fun makeCredentialSkipsMalformedAlgorithmExcludeAndOptionEntries() {
        val payload = run {
            val map = linkedMapOf<Any, Any>(
                1L to hash,
                2L to linkedMapOf<Any, Any>("id" to "example.com"),
                3L to linkedMapOf<Any, Any>("id" to byteArrayOf(5)),
                4L to listOf(
                    "not-a-map",
                    linkedMapOf<Any, Any>("alg" to "not-a-long", "type" to "public-key"),
                    linkedMapOf<Any, Any>("alg" to -7L, "type" to "public-key"),
                ),
                5L to listOf(
                    "not-a-map",
                    linkedMapOf<Any, Any>("id" to "not-bytes"),
                    linkedMapOf<Any, Any>("id" to byteArrayOf(1)),
                ),
                7L to linkedMapOf<Any, Any>("rk" to "not-a-bool", 4L to true, "uv" to true),
            )
            com.vayunmathur.passwords.domain.Cbor.encode(map)
        }
        val req = CtapMakeCredentialRequest.parse(payload)
        assertEquals(listOf(-7L), req.algorithms)
        assertEquals(1, req.excludeList.size)
        assertEquals(mapOf("uv" to true), req.options)
    }

    @Test fun getAssertionSkipsMalformedOptionEntries() {
        val payload = com.vayunmathur.passwords.domain.Cbor.encode(
            linkedMapOf<Any, Any>(
                1L to "example.com",
                2L to hash,
                5L to linkedMapOf<Any, Any>(9L to true, "up" to "not-a-bool", "uv" to true),
            ),
        )
        val req = CtapGetAssertionRequest.parse(payload)
        assertEquals(mapOf("uv" to true), req.options)
        assertTrue(req.userVerificationRequired)
    }
}
