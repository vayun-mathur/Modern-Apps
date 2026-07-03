package com.vayunmathur.passwords.cable

import com.vayunmathur.passwords.util.Cbor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round-trip tests for the CBOR encoder ([Cbor]) + decoder ([CborReader]) and the CTAP2 models. */
class CborTest {

    @Test fun unsignedInts() {
        for (v in listOf(0L, 1L, 23L, 24L, 255L, 256L, 65535L, 65536L, 1_000_000L)) {
            assertEquals(v, CborReader.decode(Cbor.encode(v)))
        }
    }

    @Test fun negativeInts() {
        for (v in listOf(-1L, -24L, -25L, -256L, -257L, -65536L)) {
            assertEquals(v, CborReader.decode(Cbor.encode(v)))
        }
    }

    @Test fun byteStringRoundTrip() {
        val data = byteArrayOf(1, 2, 3, 0, -1, 42)
        assertArrayEquals(data, CborReader.decode(Cbor.encode(data)) as ByteArray)
    }

    @Test fun textStringRoundTrip() {
        val s = "example.com"
        assertEquals(s, CborReader.decode(Cbor.encode(s)))
    }

    @Test fun arrayRoundTrip() {
        val list = listOf(1L, "two", byteArrayOf(3))
        val decoded = CborReader.decode(Cbor.encode(list)) as List<*>
        assertEquals(1L, decoded[0])
        assertEquals("two", decoded[1])
        assertArrayEquals(byteArrayOf(3), decoded[2] as ByteArray)
    }

    @Test fun mapRoundTrip() {
        val map = linkedMapOf<Any, Any>(1L to "a", 2L to 100L, 3L to byteArrayOf(9))
        val decoded = CborReader.decode(Cbor.encode(map)) as Map<*, *>
        assertEquals("a", decoded[1L])
        assertEquals(100L, decoded[2L])
        assertArrayEquals(byteArrayOf(9), decoded[3L] as ByteArray)
    }

    @Test fun booleanValues() {
        assertEquals(true, CborReader.decode(Cbor.encode(true)))
        assertEquals(false, CborReader.decode(Cbor.encode(false)))
        // 0xf6 = null
        assertNull(CborReader.decode(byteArrayOf(0xF6.toByte())))
    }

    @Test fun coseKeyRoundTrip() {
        // Same shape produced by PasskeyAuthActivity when creating a credential.
        val x = ByteArray(32) { it.toByte() }
        val y = ByteArray(32) { (it + 32).toByte() }
        val cose = linkedMapOf<Any, Any>(1L to 2L, 3L to -7L, -1L to 1L, -2L to x, -3L to y)
        val decoded = CborReader.decode(Cbor.encode(cose)) as Map<*, *>
        assertEquals(2L, decoded[1L])
        assertEquals(-7L, decoded[3L])
        assertEquals(1L, decoded[-1L])
        assertArrayEquals(x, decoded[-2L] as ByteArray)
        assertArrayEquals(y, decoded[-3L] as ByteArray)
    }

    @Test fun getAssertionRequestParse() {
        val clientDataHash = ByteArray(32) { it.toByte() }
        val credId = byteArrayOf(10, 20, 30)
        val payload = Cbor.encode(linkedMapOf<Any, Any>(
            1L to "example.com",
            2L to clientDataHash,
            3L to listOf(linkedMapOf<Any, Any>("type" to "public-key", "id" to credId)),
            5L to linkedMapOf<Any, Any>("up" to true, "uv" to true),
        ))

        val req = CtapGetAssertionRequest.parse(payload)
        assertEquals("example.com", req.rpId)
        assertArrayEquals(clientDataHash, req.clientDataHash)
        assertEquals(1, req.allowList.size)
        assertArrayEquals(credId, req.allowList[0].id)
        assertTrue(req.userPresenceRequired)
        assertTrue(req.userVerificationRequired)
    }

    @Test fun getAssertionRequestDefaults() {
        val payload = Cbor.encode(linkedMapOf<Any, Any>(
            1L to "site.test",
            2L to ByteArray(32),
        ))
        val req = CtapGetAssertionRequest.parse(payload)
        assertTrue(req.allowList.isEmpty())
        assertTrue(req.userPresenceRequired)   // up defaults to true
        assertTrue(!req.userVerificationRequired) // uv defaults to false
    }

    @Test fun getAssertionResponseEncodeDecode() {
        val resp = CtapGetAssertionResponse(
            credentialId = byteArrayOf(1, 2, 3),
            authData = ByteArray(37) { it.toByte() },
            signature = byteArrayOf(9, 8, 7),
            userId = byteArrayOf(4, 5, 6),
            userName = "alice",
        )
        val decoded = CborReader.decode(resp.encode()) as Map<*, *>
        val cred = decoded[1L] as Map<*, *>
        assertEquals("public-key", cred["type"])
        assertArrayEquals(byteArrayOf(1, 2, 3), cred["id"] as ByteArray)
        assertArrayEquals(resp.authData, decoded[2L] as ByteArray)
        assertArrayEquals(byteArrayOf(9, 8, 7), decoded[3L] as ByteArray)
        val user = decoded[4L] as Map<*, *>
        assertArrayEquals(byteArrayOf(4, 5, 6), user["id"] as ByteArray)
        assertEquals("alice", user["name"])
    }

    @Test fun getInfoEncode() {
        val aaguid = ByteArray(16) { it.toByte() }
        val decoded = CborReader.decode(CtapGetInfoResponse(aaguid = aaguid).encode()) as Map<*, *>
        val versions = decoded[1L] as List<*>
        assertTrue(versions.contains("FIDO_2_0"))
        assertArrayEquals(aaguid, decoded[3L] as ByteArray)
        val options = decoded[4L] as Map<*, *>
        assertEquals(true, options["rk"])
    }
}
