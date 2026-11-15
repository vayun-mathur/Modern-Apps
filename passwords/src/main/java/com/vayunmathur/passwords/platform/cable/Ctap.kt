package com.vayunmathur.passwords.platform.cable

import com.vayunmathur.passwords.domain.Cbor

/**
 * CTAP2 command/status constants and the request/response models used by the caBLE authenticator.
 * Wire layout follows the FIDO CTAP 2.2 spec. The subset needed for cross-device sign-in and
 * registration (`authenticatorGetInfo`, `authenticatorGetAssertion`, `authenticatorMakeCredential`)
 * is modelled here.
 *
 * A CTAP message is `command_byte || CBOR_payload`. Requests are decoded with [CborReader];
 * responses are encoded as `status_byte || CBOR_payload` via [Cbor].
 */
object Ctap {
    // Command bytes (client -> authenticator).
    const val CMD_MAKE_CREDENTIAL = 0x01
    const val CMD_GET_ASSERTION = 0x02
    const val CMD_GET_INFO = 0x04
    const val CMD_GET_NEXT_ASSERTION = 0x08

    internal const val NUMBER_OF_CREDENTIALS_KEY = 5L
    internal const val ATTESTATION_FIRST_KEY = 1L
    internal const val ATTESTATION_SECOND_KEY = 2L
    internal const val ATTESTATION_THIRD_KEY = 3L
    internal const val KEY_CLIENT_DATA_HASH = 1L
    internal const val KEY_RP = 2L
    internal const val KEY_USER = 3L
    internal const val KEY_PUB_KEY_PARAMS = 4L
    internal const val KEY_EXCLUDE_LIST = 5L
    internal const val KEY_EXTENSIONS = 6L
    internal const val KEY_OPTIONS = 7L

    // Status bytes (authenticator -> client). CTAP2 error codes.
    const val OK = 0x00
    const val ERR_INVALID_PARAMETER = 0x02
    const val ERR_INVALID_CBOR = 0x12
    const val ERR_MISSING_PARAMETER = 0x14
    const val ERR_CREDENTIAL_EXCLUDED = 0x19
    const val ERR_UNSUPPORTED_ALGORITHM = 0x26
    const val ERR_NO_CREDENTIALS = 0x2E
    const val ERR_OPERATION_DENIED = 0x27
    const val ERR_UNSUPPORTED_OPTION = 0x2B
    const val ERR_INVALID_OPTION = 0x2C
    const val ERR_NOT_ALLOWED = 0x30
    const val ERR_OTHER = 0x7F

    /** COSE identifier for ES256, the only algorithm this authenticator implements. */
    const val ALG_ES256 = -7L

    /**
     * The AAGUID caBLE reports, in both [CtapGetInfoResponse] and the attestation produced by
     * `makeCredential`. All-zero is what Chromium's own caBLE authenticator uses; the two must
     * agree, so both read this.
     */
    val ZERO_AAGUID: ByteArray get() = ByteArray(16)

    /** Wraps a CTAP response: a status byte followed by an optional CBOR payload. */
    fun response(status: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        byteArrayOf(status.toByte()) + payload

    /** A `{ "type": "public-key", "id": <bytes> }` credential descriptor. */
    data class CredentialDescriptor(val id: ByteArray, val type: String = "public-key")
}

/**
 * Parsed `authenticatorGetAssertion` (0x02) request.
 *
 * CBOR map keys: 1=rpId, 2=clientDataHash, 3=allowList, 4=extensions, 5=options,
 * 6=pinUvAuthParam, 7=pinUvAuthProtocol.
 */
data class CtapGetAssertionRequest(
    val rpId: String,
    val clientDataHash: ByteArray,
    val allowList: List<Ctap.CredentialDescriptor>,
    val options: Map<String, Boolean>,
    val extensions: Map<*, *>?,
) {
    /** Effective user-presence requirement (defaults to true per CTAP2). */
    val userPresenceRequired: Boolean get() = options["up"] ?: true

    /** Effective user-verification requirement (defaults to false per CTAP2). */
    val userVerificationRequired: Boolean get() = options["uv"] ?: false

    /** Shared constants and helpers for CtapGetAssertionRequest. */
    companion object {
        /** Parses the CBOR payload (the bytes after the 0x02 command byte). */
        fun parse(payload: ByteArray): CtapGetAssertionRequest {
            val map = CborReader(payload).readIntMap()
            val rpId = map[1L] as? String ?: error("getAssertion: missing rpId")
            val clientDataHash = map[2L] as? ByteArray ?: error("getAssertion: missing clientDataHash")

            @Suppress("UNCHECKED_CAST")
            val allowList = (map[3L] as? List<Any?>).orEmptyList().mapNotNull { entry ->
                val m = entry as? Map<*, *> ?: return@mapNotNull null
                val id = m["id"] as? ByteArray ?: return@mapNotNull null
                val type = m["type"] as? String ?: "public-key"
                Ctap.CredentialDescriptor(id, type)
            }

            val options = (map[5L] as? Map<*, *>).orEmptyMap()
                .entries.mapNotNull { (k, v) ->
                    val key = k as? String ?: return@mapNotNull null
                    val value = v as? Boolean ?: return@mapNotNull null
                    key to value
                }.toMap()

            return CtapGetAssertionRequest(
                rpId = rpId,
                clientDataHash = clientDataHash,
                allowList = allowList,
                options = options,
                extensions = map[4L] as? Map<*, *>,
            )
        }
    }
}

private fun Map<*, *>?.orEmptyMap(): Map<*, *> = this ?: emptyMap<Any, Any>()

private fun List<Any?>?.orEmptyList(): List<Any?> = this ?: emptyList()

/**
 * `authenticatorGetAssertion` (0x02) response payload.
 *
 * CBOR map keys: 1=credential, 2=authData, 3=signature, 4=user, 5=numberOfCredentials.
 */
data class CtapGetAssertionResponse(
    val credentialId: ByteArray,
    val authData: ByteArray,
    val signature: ByteArray,
    val userId: ByteArray,
    val userName: String? = null,
    val userDisplayName: String? = null,
    val numberOfCredentials: Int? = null,
) {
    /** Encodes just the CBOR payload (without the leading status byte). */
    fun encode(): ByteArray {
        val user = linkedMapOf<String, Any>("id" to userId)
        userName?.let { user["name"] = it }
        userDisplayName?.let { user["displayName"] = it }

        val map = linkedMapOf<Long, Any>(
            1L to linkedMapOf<String, Any>(
                "type" to "public-key",
                "id" to credentialId,
            ),
            2L to authData,
            3L to signature,
            4L to user,
        )
        numberOfCredentials?.let { map[Ctap.NUMBER_OF_CREDENTIALS_KEY] = it.toLong() }
        return Cbor.encode(map)
    }
}

/**
 * Parsed `authenticatorMakeCredential` (0x01) request.
 *
 * CBOR map keys: 1=clientDataHash, 2=rp, 3=user, 4=pubKeyCredParams, 5=excludeList,
 * 6=extensions, 7=options, 8=pinUvAuthParam, 9=pinUvAuthProtocol.
 */
data class CtapMakeCredentialRequest(
    val clientDataHash: ByteArray,
    val rpId: String,
    val rpName: String,
    val userId: ByteArray,
    val userName: String,
    val userDisplayName: String,
    val algorithms: List<Long>,
    val excludeList: List<Ctap.CredentialDescriptor>,
    val options: Map<String, Boolean>,
    val extensions: Map<*, *>?,
) {
    /** True when the RP asked for a discoverable credential. We always make one either way. */
    val residentKey: Boolean get() = options["rk"] ?: false

    /** Effective user-verification requirement (defaults to false per CTAP2). */
    val userVerificationRequired: Boolean get() = options["uv"] ?: false

    /**
     * Raw `up` option. Nullable on purpose: unlike getAssertion, makeCredential has no meaningful
     * default and an explicit `up: false` is an error rather than a request to skip presence.
     */
    val userPresenceOption: Boolean? get() = options["up"]

    val supportsEs256: Boolean get() = algorithms.contains(Ctap.ALG_ES256)

    /** Shared constants and helpers for CtapMakeCredentialRequest. */
    companion object {
        /** Parses the CBOR payload (the bytes after the 0x01 command byte). */
        fun parse(payload: ByteArray): CtapMakeCredentialRequest {
            val map = CborReader(payload).readIntMap()
            val rp = map[Ctap.KEY_RP] as? Map<*, *> ?: error("makeCredential: missing rp")
            val user = map[Ctap.KEY_USER] as? Map<*, *> ?: error("makeCredential: missing user")
            val rpId = rp["id"] as? String ?: error("makeCredential: missing rp.id")
            val userName = user["name"] as? String ?: ""
            return CtapMakeCredentialRequest(
                clientDataHash = requiredBytes(map, Ctap.KEY_CLIENT_DATA_HASH, "clientDataHash"),
                rpId = rpId,
                rpName = rp["name"] as? String ?: rpId,
                userId = user["id"] as? ByteArray ?: error("makeCredential: missing user.id"),
                userName = userName,
                userDisplayName = user["displayName"] as? String ?: userName,
                algorithms = parseAlgorithms(map[Ctap.KEY_PUB_KEY_PARAMS]),
                excludeList = parseExcludeList(map[Ctap.KEY_EXCLUDE_LIST]),
                options = parseOptions(map[Ctap.KEY_OPTIONS]),
                extensions = map[Ctap.KEY_EXTENSIONS] as? Map<*, *>,
            )
        }

        private fun requiredBytes(map: Map<Long, Any?>, key: Long, name: String): ByteArray =
            map[key] as? ByteArray ?: error("makeCredential: missing $name")

        private fun parseAlgorithms(raw: Any?): List<Long> =
            (raw as? List<Any?>).orEmptyList().mapNotNull { entry ->
                val m = entry as? Map<*, *> ?: return@mapNotNull null
                if ((m["type"] as? String ?: "public-key") != "public-key") return@mapNotNull null
                m["alg"] as? Long
            }

        private fun parseExcludeList(raw: Any?): List<Ctap.CredentialDescriptor> =
            (raw as? List<Any?>).orEmptyList().mapNotNull { entry ->
                val m = entry as? Map<*, *> ?: return@mapNotNull null
                val id = m["id"] as? ByteArray ?: return@mapNotNull null
                Ctap.CredentialDescriptor(id, m["type"] as? String ?: "public-key")
            }

        private fun parseOptions(raw: Any?): Map<String, Boolean> =
            (raw as? Map<*, *>).orEmptyMap().entries.mapNotNull { (k, v) ->
                val key = k as? String ?: return@mapNotNull null
                val value = v as? Boolean ?: return@mapNotNull null
                key to value
            }.toMap()
    }
}

/**
 * `authenticatorMakeCredential` (0x01) response payload.
 *
 * CBOR map keys: 1=fmt, 2=authData, 3=attStmt.
 *
 * This is the CTAP-level response, **not** a WebAuthn `attestationObject`. The two look alike but
 * the attestationObject is a separate map keyed by the *strings* "fmt"/"attStmt"/"authData", and
 * the browser is the one that assembles it from these three fields. Nesting an attestationObject
 * in here would hand the relying party an `authData` that is a CBOR blob rather than authenticator
 * data, and every real RP would reject it.
 */
data class CtapMakeCredentialResponse(
    val authData: ByteArray,
    val fmt: String = "none",
    val attStmt: Map<String, Any> = emptyMap(),
) {
    /** Encodes just the CBOR payload (without the leading status byte). */
    fun encode(): ByteArray = Cbor.encode(
        linkedMapOf<Long, Any>(
            Ctap.ATTESTATION_FIRST_KEY to fmt,
            Ctap.ATTESTATION_SECOND_KEY to authData,
            Ctap.ATTESTATION_THIRD_KEY to LinkedHashMap(attStmt),
        )
    )
}

/**
 * `authenticatorGetInfo` (0x04) response payload, matching Chromium `BuildGetInfoResponse`
 * (`//device/fido/cable/v2_authenticator.cc`).
 *
 * CBOR map keys: 1=versions, 2=extensions, 3=aaguid, 4=options, 9=transports.
 * caBLE advertises an all-zero AAGUID here.
 */
data class CtapGetInfoResponse(
    val versions: List<String> = listOf("FIDO_2_0", "FIDO_2_1"),
    val extensions: List<String> = listOf("prf"),
    val aaguid: ByteArray = Ctap.ZERO_AAGUID,
    val options: Map<String, Boolean> = linkedMapOf("uv" to true, "rk" to true),
    val transports: List<String> = listOf("cable", "hybrid", "internal"),
) {
    /** Encodes just the CBOR payload (without the leading status byte). */
    fun encode(): ByteArray {
        val map = linkedMapOf<Long, Any>(
            1L to versions,
            2L to extensions,
            3L to aaguid,
            4L to LinkedHashMap(options),
            9L to transports,
        )
        return Cbor.encode(map)
    }
}
