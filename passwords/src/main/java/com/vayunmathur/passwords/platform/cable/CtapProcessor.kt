package com.vayunmathur.passwords.platform.cable

import com.vayunmathur.library.log.Log
import com.vayunmathur.passwords.data.PasskeyStore
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Handles decrypted CTAP2 commands for the caBLE authenticator and produces the response bytes
 * (`status || CBOR`). Supports `authenticatorGetInfo`, `authenticatorGetAssertion` and
 * `authenticatorMakeCredential`, so a passkey can be both registered and used cross-device.
 *
 * Credential creation and signing reuse the shared [WebAuthnAuthenticator] core and the passkey
 * store, so cross-device registration produces credentials indistinguishable from ones made by the
 * same-device Credential Manager path, and either can be used from either transport.
 */
@OptIn(ExperimentalEncodingApi::class)
class CtapProcessor(
    private val store: PasskeyStore,
    /** Whether the user was verified (biometric) when the session was approved. */
    private val userVerified: Boolean,
) {
    private val urlDecoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
    private val urlEncoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    /** Processes one CTAP command message; never throws (errors map to CTAP status bytes). */
    suspend fun process(command: ByteArray): ByteArray {
        if (command.isEmpty()) return Ctap.response(Ctap.ERR_INVALID_CBOR)
        val payload = command.copyOfRange(1, command.size)
        return try {
            when (command[0].toInt() and BYTE_MASK) {
                Ctap.CMD_GET_INFO ->
                    Ctap.response(Ctap.OK, CtapGetInfoResponse().encode())
                Ctap.CMD_MAKE_CREDENTIAL -> handleMakeCredential(payload)
                Ctap.CMD_GET_ASSERTION -> handleGetAssertion(payload)
                else -> Ctap.response(Ctap.ERR_NOT_ALLOWED)
            }
        } catch (expected: IllegalStateException) {
            Log.error(TAG, "CTAP processing error", expected)
            Ctap.response(Ctap.ERR_OTHER)
        }
    }

    private suspend fun handleMakeCredential(payload: ByteArray): ByteArray {
        val req = try {
            CtapMakeCredentialRequest.parse(payload)
        } catch (e: IllegalStateException) {
            Log.status(TAG, "makeCredential: ${e.message}")
            return Ctap.response(Ctap.ERR_MISSING_PARAMETER)
        }
        Log.dev(TAG, "makeCredential rpId=${req.rpId} user=${req.userName} " +
            "algs=${req.algorithms} exclude=${req.excludeList.size} rk=${req.residentKey} " +
            "uv=${req.userVerificationRequired}")

        // Unlike getAssertion, up has no meaningful default here and cannot be waived.
        if (req.userPresenceOption == false) {
            Log.status(TAG, "makeCredential: up=false is not a valid option")
            return Ctap.response(Ctap.ERR_INVALID_OPTION)
        }

        if (req.userVerificationRequired && !userVerified) {
            Log.status(TAG, "UV required but user not verified")
            return Ctap.response(Ctap.ERR_OPERATION_DENIED)
        }

        if (!req.supportsEs256) {
            Log.status(TAG, "no ES256 in pubKeyCredParams -> ERR_UNSUPPORTED_ALGORITHM")
            return Ctap.response(Ctap.ERR_UNSUPPORTED_ALGORITHM)
        }

        val excluded = req.excludeList.any { desc ->
            store.getPasskeyByCredentialId(urlEncoder.encode(desc.id))?.rpId == req.rpId
        }
        if (excluded) {
            Log.status(TAG, "excludeList matches a stored credential -> ERR_CREDENTIAL_EXCLUDED")
            return Ctap.response(Ctap.ERR_CREDENTIAL_EXCLUDED)
        }

        val created = WebAuthnAuthenticator.createCredential(
            rpId = req.rpId,
            rpName = req.rpName,
            userId = req.userId,
            userName = req.userName,
            userDisplayName = req.userDisplayName,
            aaguid = Ctap.ZERO_AAGUID,
            store = store,
            userVerified = userVerified,
        )
        Log.dev(TAG, "created credId=${created.passkey.credentialId} " +
            "authData=${created.authenticatorData.size}B")

        return Ctap.response(
            Ctap.OK,
            CtapMakeCredentialResponse(authData = created.authenticatorData).encode(),
        )
    }

    private suspend fun handleGetAssertion(payload: ByteArray): ByteArray {
        val req = CtapGetAssertionRequest.parse(payload)
        Log.dev(TAG, "getAssertion rpId=${req.rpId} allowList=${req.allowList.size} " +
            "up=${req.userPresenceRequired} uv=${req.userVerificationRequired} " +
            "clientDataHash=${req.clientDataHash.size}B")
        req.allowList.forEachIndexed { i, d -> Log.dev(TAG, "  allow[$i] id=${hex(d.id)}") }

        if (req.userVerificationRequired && !userVerified) {
            Log.status(TAG, "UV required but user not verified")
            return Ctap.response(Ctap.ERR_OPERATION_DENIED)
        }

        val allForRp = store.getPasskeysByRpId(req.rpId)
        Log.dev(TAG, "stored passkeys for ${req.rpId}: ${allForRp.size} " +
            allForRp.joinToString { "credId=${it.credentialId}" })

        val passkey = resolveCredential(req) ?: run {
            Log.status(TAG, "no matching credential -> ERR_NO_CREDENTIALS")
            return Ctap.response(Ctap.ERR_NO_CREDENTIALS)
        }
        Log.dev(TAG, "using credential credId=${passkey.credentialId} rpId=${passkey.rpId} userId=${passkey.userId}")

        val assertion = WebAuthnAuthenticator.signAssertion(
            passkey = passkey,
            clientDataHash = req.clientDataHash,
            store = store,
            userPresent = req.userPresenceRequired,
            userVerified = userVerified,
        )

        val response = CtapGetAssertionResponse(
            credentialId = runCatching { urlDecoder.decode(passkey.credentialId) }.getOrElse { ByteArray(0) },
            authData = assertion.authenticatorData,
            signature = assertion.signature,
            userId = decodeUserId(passkey.userId),
        )
        Log.dev(TAG, "assertion signed: authData=${assertion.authenticatorData.size}B sig=${assertion.signature.size}B")
        return Ctap.response(Ctap.OK, response.encode())
    }

    /** allowList (by credential id) takes precedence; otherwise the first passkey for the rpId. */
    private suspend fun resolveCredential(req: CtapGetAssertionRequest) =
        if (req.allowList.isNotEmpty()) {
            req.allowList.firstNotNullOfOrNull { desc ->
                store.getPasskeyByCredentialId(urlEncoder.encode(desc.id))
                    ?.takeIf { it.rpId == req.rpId }
            }
        } else {
            store.getPasskeysByRpId(req.rpId).maxByOrNull { it.lastUsedTime }
        }

    /** Stored user handles are base64url; fall back to raw UTF-8 if not decodable. */
    private fun decodeUserId(userId: String): ByteArray =
        runCatching { urlDecoder.decode(userId) }.getOrElse { userId.toByteArray() }

    /** Shared constants and helpers for CtapProcessor. */
    companion object {
        private const val TAG = "CtapProcessor"
        private const val BYTE_MASK = 0xFF

        private fun hex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }
    }
}
