package com.vayunmathur.passwords.platform

import com.vayunmathur.passwords.data.Passkey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds and validates the WebAuthn JSON returned to a Credential Manager
 * caller (e.g. a browser). Nothing in here touches Android or the vault: the
 * inputs are the already-signed pieces, and every field that will cross into
 * the caller's process is decoded and canonically re-encoded before it is
 * put in the JSON.
 *
 * Why canonicalise: stored credential ids / user handles can arrive padded
 * or otherwise non-canonically spelled (KDBX/KeePassXC import, everysync
 * relay, older rows). The pre-hardening get flow emitted `id` verbatim while
 * re-encoding `rawId` from a strict `NO_PADDING` decode, so a padded stored
 * id either threw mid-flow or produced `id != base64url(rawId)`. A caller
 * (Vanadium/Chromium) parsing a mismatched or non-base64url assertion has no
 * defined recovery; the provider must never hand it one. Any input that
 * cannot be canonicalised fails here with [IllegalArgumentException], which
 * the auth activity maps to RESULT_CANCELED — an error outcome in our
 * process, never a malformed RESULT_OK in the caller's.
 */

/** Minimum authenticator-data size: 32-byte rpId hash + flags + sign count. */
private const val MIN_AUTHENTICATOR_DATA = 37

/** WebAuthn credential ids are at most 1023 bytes (CTAP credId length field). */
private const val MAX_CREDENTIAL_ID_BYTES = 1023

/**
 * Decodes [value] as base64url (padding tolerated) and re-encodes it in the
 * canonical unpadded form our responses use. Blank or undecodable input is
 * rejected: emitting it verbatim would hand the caller a field its parser
 * cannot decode.
 */
private fun canonicalB64Url(value: String, field: String): String {
    require(value.isNotBlank()) { "$field is blank" }
    val bytes = try {
        PasskeyUtils.decodeB64Url(value)
    } catch (expected: IllegalArgumentException) {
        throw IllegalArgumentException("$field is not valid base64url", expected)
    }
    require(bytes.isNotEmpty()) { "$field decodes to no bytes" }
    return PasskeyUtils.encodeB64Url(bytes)
}

/** Requires [value] to be non-blank, decodable base64url; returns it canonically. */
private fun validatedB64Url(value: String, field: String): String = canonicalB64Url(value, field)

/**
 * The `AuthenticatorAssertionResponse` JSON for a get (USE) ceremony.
 *
 * [clientDataJsonB64] is the base64url clientDataJSON this provider built,
 * or the placeholder for a privileged caller (which substitutes its own).
 * Throws [IllegalArgumentException] on any malformed piece instead of
 * returning JSON a caller could choke on.
 */
fun buildAssertionResponseJson(
    passkey: Passkey,
    authenticatorData: ByteArray,
    signature: ByteArray,
    clientDataJsonB64: String,
): String {
    require(authenticatorData.size >= MIN_AUTHENTICATOR_DATA) {
        "authenticatorData is ${authenticatorData.size} bytes, need at least $MIN_AUTHENTICATOR_DATA"
    }
    require(signature.isNotEmpty()) { "signature is empty" }
    val credentialId = canonicalB64Url(passkey.credentialId, "credentialId")
    val credentialIdBytes = PasskeyUtils.decodeB64Url(credentialId)
    require(credentialIdBytes.size <= MAX_CREDENTIAL_ID_BYTES) { "credentialId is too long" }
    // A blank user handle is simply omitted (WebAuthn allows an absent
    // handle); a non-blank one must be valid base64url.
    val userHandle = passkey.userId.takeIf { it.isNotBlank() }?.let {
        canonicalB64Url(it, "userHandle")
    }
    val response = JSONObject().apply {
        put("clientDataJSON", validatedB64Url(clientDataJsonB64, "clientDataJSON"))
        put("authenticatorData", PasskeyUtils.encodeB64Url(authenticatorData))
        put("signature", PasskeyUtils.encodeB64Url(signature))
        if (userHandle != null) put("userHandle", userHandle)
    }
    return JSONObject().apply {
        put("id", credentialId)
        put("rawId", credentialId)
        put("type", "public-key")
        put("authenticatorAttachment", "platform")
        put("response", response)
        put("clientExtensionResults", JSONObject())
    }.toString()
}

/**
 * The `AuthenticatorAttestationResponse` JSON for a create ceremony.
 * Same contract as [buildAssertionResponseJson]: validated or throws.
 */
fun buildCreateResponseJson(
    credentialId: ByteArray,
    attestationObject: ByteArray,
    publicKeySpki: ByteArray,
    authenticatorData: ByteArray,
    clientDataJsonB64: String,
): String {
    require(credentialId.isNotEmpty() && credentialId.size <= MAX_CREDENTIAL_ID_BYTES) {
        "credentialId has invalid length ${credentialId.size}"
    }
    require(attestationObject.isNotEmpty()) { "attestationObject is empty" }
    require(publicKeySpki.isNotEmpty()) { "publicKey is empty" }
    require(authenticatorData.size >= MIN_AUTHENTICATOR_DATA) {
        "authenticatorData is ${authenticatorData.size} bytes, need at least $MIN_AUTHENTICATOR_DATA"
    }
    val credentialIdB64 = PasskeyUtils.encodeB64Url(credentialId)
    val response = JSONObject().apply {
        put("clientDataJSON", validatedB64Url(clientDataJsonB64, "clientDataJSON"))
        put("attestationObject", PasskeyUtils.encodeB64Url(attestationObject))
        put("transports", JSONArray(listOf("internal", "hybrid")))
        put("publicKeyAlgorithm", -7)
        put("publicKey", PasskeyUtils.encodeB64Url(publicKeySpki))
        put("authenticatorData", PasskeyUtils.encodeB64Url(authenticatorData))
    }
    return JSONObject().apply {
        put("id", credentialIdB64)
        put("rawId", credentialIdB64)
        put("type", "public-key")
        put("authenticatorAttachment", "platform")
        put("response", response)
        put("clientExtensionResults", JSONObject())
    }.toString()
}
