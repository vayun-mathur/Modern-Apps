package com.vayunmathur.passwords.platform

import com.vayunmathur.passwords.data.Passkey
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * The response JSON is what crosses into the caller's process (Vanadium's
 * passkey parser included). Its shape must be valid by construction: these
 * tests pin the get happy path and the crash shapes that used to be emitted
 * or thrown mid-flow — padded/non-canonical stored ids, garbage handles,
 * and empty/truncated signed pieces — which now fail as
 * [IllegalArgumentException] in our process (mapped to RESULT_CANCELED by
 * the auth activity) instead of a malformed RESULT_OK in the caller's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PasskeyResponsesTest {

    private val credentialBytes = ByteArray(16) { (it * 7 + 1).toByte() }
    private val userBytes = byteArrayOf(9, 8, 7, 6, 5)
    private val authenticatorData = PasskeyUtils.buildAuthenticatorData("example.com", signCount = 3)
    private val signature = ByteArray(71) { (it + 2).toByte() }
    private val clientDataJsonB64 = PasskeyUtils.encodeB64Url("""{"type":"webauthn.get"}""".toByteArray())

    private fun passkey(
        credentialId: String = PasskeyUtils.encodeB64Url(credentialBytes),
        userId: String = PasskeyUtils.encodeB64Url(userBytes),
    ) = Passkey(
        id = 1, rpId = "example.com", rpName = "Example",
        credentialId = credentialId, userId = userId,
        userName = "alice", userDisplayName = "Alice", syncId = "pk-1",
    )

    private fun assertNoNonUrlSafeChars(json: JSONObject) {
        // Every base64 field in a WebAuthn response is base64url without
        // padding: no '+', '/', or '=' may reach the caller's parser.
        for (key in listOf("id", "rawId")) {
            val value = json.getString(key)
            assertFalse(value.any { it == '+' || it == '/' || it == '=' }, "$key=$value")
        }
        val response = json.getJSONObject("response")
        for (key in response.keys()) {
            val value = response.getString(key)
            assertFalse(value.any { it == '+' || it == '/' || it == '=' }, "$key=$value")
        }
    }

    @Test fun getHappyPathResponseShape() {
        val json = JSONObject(
            buildAssertionResponseJson(passkey(), authenticatorData, signature, clientDataJsonB64),
        )
        assertEquals("public-key", json.getString("type"))
        assertEquals("platform", json.getString("authenticatorAttachment"))
        assertEquals(json.getString("id"), json.getString("rawId"))
        assertEquals(credentialBytes.toList(), PasskeyUtils.decodeB64Url(json.getString("id")).toList())
        val response = json.getJSONObject("response")
        assertEquals(clientDataJsonB64, response.getString("clientDataJSON"))
        assertEquals(authenticatorData.toList(), PasskeyUtils.decodeB64Url(response.getString("authenticatorData")).toList())
        assertEquals(signature.toList(), PasskeyUtils.decodeB64Url(response.getString("signature")).toList())
        assertEquals(userBytes.toList(), PasskeyUtils.decodeB64Url(response.getString("userHandle")).toList())
        assertEquals(0, json.getJSONObject("clientExtensionResults").length())
        assertNoNonUrlSafeChars(json)
    }

    @Test fun getCanonicalisesPaddedStoredIds() {
        // KDBX/relay spellings can carry padding: id must still equal the
        // canonical rawId, not the stored spelling.
        val padded = passkey(
            credentialId = PasskeyUtils.encodeB64Url(credentialBytes) + "==",
            userId = PasskeyUtils.encodeB64Url(userBytes) + "=",
        )
        val json = JSONObject(
            buildAssertionResponseJson(padded, authenticatorData, signature, clientDataJsonB64),
        )
        assertEquals(json.getString("id"), json.getString("rawId"))
        assertFalse(json.getString("id").contains('='))
        assertEquals(credentialBytes.toList(), PasskeyUtils.decodeB64Url(json.getString("id")).toList())
        assertEquals(
            userBytes.toList(),
            PasskeyUtils.decodeB64Url(json.getJSONObject("response").getString("userHandle")).toList(),
        )
        assertNoNonUrlSafeChars(json)
    }

    @Test fun getOmitsBlankUserHandle() {
        val json = JSONObject(
            buildAssertionResponseJson(passkey(userId = ""), authenticatorData, signature, clientDataJsonB64),
        )
        assertFalse(json.getJSONObject("response").has("userHandle"))
    }

    @Test fun getRejectsMalformedStoredDataInsteadOfEmittingIt() {
        // Standard-base64 / garbage ids and handles: previously the id went
        // out verbatim (or the strict NO_PADDING decode threw past the
        // specific catches); now the builder refuses to build the response.
        assertFailsWith<IllegalArgumentException> {
            buildAssertionResponseJson(passkey(credentialId = "a+b/c=="), authenticatorData, signature, clientDataJsonB64)
        }
        assertFailsWith<IllegalArgumentException> {
            buildAssertionResponseJson(passkey(credentialId = "!!!not-base64!!!"), authenticatorData, signature, clientDataJsonB64)
        }
        assertFailsWith<IllegalArgumentException> {
            buildAssertionResponseJson(passkey(credentialId = ""), authenticatorData, signature, clientDataJsonB64)
        }
        assertFailsWith<IllegalArgumentException> {
            buildAssertionResponseJson(passkey(userId = "not b64url!!"), authenticatorData, signature, clientDataJsonB64)
        }
    }

    @Test fun getRejectsTruncatedOrEmptySignedPieces() {
        assertFailsWith<IllegalArgumentException> {
            buildAssertionResponseJson(passkey(), ByteArray(36), signature, clientDataJsonB64)
        }
        assertFailsWith<IllegalArgumentException> {
            buildAssertionResponseJson(passkey(), authenticatorData, ByteArray(0), clientDataJsonB64)
        }
        assertFailsWith<IllegalArgumentException> {
            buildAssertionResponseJson(passkey(), authenticatorData, signature, "not b64url!!")
        }
        assertFailsWith<IllegalArgumentException> {
            buildAssertionResponseJson(passkey(), authenticatorData, signature, "")
        }
    }

    @Test fun createHappyPathResponseShape() {
        val attestation = byteArrayOf(1, 2, 3, 4)
        val spki = byteArrayOf(5, 6, 7, 8, 9)
        val json = JSONObject(
            buildCreateResponseJson(credentialBytes, attestation, spki, authenticatorData, clientDataJsonB64),
        )
        assertEquals("public-key", json.getString("type"))
        assertEquals(json.getString("id"), json.getString("rawId"))
        val response = json.getJSONObject("response")
        assertEquals(attestation.toList(), PasskeyUtils.decodeB64Url(response.getString("attestationObject")).toList())
        assertEquals(spki.toList(), PasskeyUtils.decodeB64Url(response.getString("publicKey")).toList())
        assertEquals(authenticatorData.toList(), PasskeyUtils.decodeB64Url(response.getString("authenticatorData")).toList())
        assertEquals(-7, response.getInt("publicKeyAlgorithm"))
        assertEquals(listOf("internal", "hybrid"), List(2) { response.getJSONArray("transports").getString(it) })
        assertNoNonUrlSafeChars(json)
    }

    @Test fun createRejectsEmptyPieces() {
        val attestation = byteArrayOf(1)
        val spki = byteArrayOf(2)
        assertFailsWith<IllegalArgumentException> {
            buildCreateResponseJson(ByteArray(0), attestation, spki, authenticatorData, clientDataJsonB64)
        }
        assertFailsWith<IllegalArgumentException> {
            buildCreateResponseJson(credentialBytes, ByteArray(0), spki, authenticatorData, clientDataJsonB64)
        }
        assertFailsWith<IllegalArgumentException> {
            buildCreateResponseJson(credentialBytes, attestation, ByteArray(0), authenticatorData, clientDataJsonB64)
        }
        assertFailsWith<IllegalArgumentException> {
            buildCreateResponseJson(credentialBytes, attestation, spki, ByteArray(10), clientDataJsonB64)
        }
    }
}
