package com.vayunmathur.passwords.cable

import com.vayunmathur.passwords.util.Cbor

/**
 * CTAP2 command/status constants and the request/response models used by the caBLE authenticator.
 * Wire layout follows the FIDO CTAP 2.2 spec. Only the subset needed for cross-device sign-in
 * (`authenticatorGetInfo` + `authenticatorGetAssertion`) is modelled here; `makeCredential` is
 * deliberately out of scope for v1.
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

    // Status bytes (authenticator -> client). CTAP2 error codes.
    const val OK = 0x00
    const val ERR_INVALID_CBOR = 0x12
    const val ERR_MISSING_PARAMETER = 0x14
    const val ERR_NO_CREDENTIALS = 0x2E
    const val ERR_OPERATION_DENIED = 0x27
    const val ERR_UNSUPPORTED_OPTION = 0x2B
    const val ERR_INVALID_OPTION = 0x2C
    const val ERR_NOT_ALLOWED = 0x30
    const val ERR_OTHER = 0x7F

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

    companion object {
        /** Parses the CBOR payload (the bytes after the 0x02 command byte). */
        fun parse(payload: ByteArray): CtapGetAssertionRequest {
            val map = CborReader(payload).readIntMap()
            val rpId = map[1L] as? String ?: error("getAssertion: missing rpId")
            val clientDataHash = map[2L] as? ByteArray ?: error("getAssertion: missing clientDataHash")

            @Suppress("UNCHECKED_CAST")
            val allowList = (map[3L] as? List<Any?>).orEmpty().mapNotNull { entry ->
                val m = entry as? Map<*, *> ?: return@mapNotNull null
                val id = m["id"] as? ByteArray ?: return@mapNotNull null
                val type = m["type"] as? String ?: "public-key"
                Ctap.CredentialDescriptor(id, type)
            }

            val options = (map[5L] as? Map<*, *>).orEmpty()
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

        private fun Map<*, *>?.orEmpty(): Map<*, *> = this ?: emptyMap<Any, Any>()
        private fun List<Any?>?.orEmpty(): List<Any?> = this ?: emptyList()
    }
}

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
        numberOfCredentials?.let { map[5L] = it.toLong() }
        return Cbor.encode(map)
    }
}

/**
 * `authenticatorGetInfo` (0x04) response payload.
 *
 * CBOR map keys: 1=versions, 3=aaguid, 4=options, 5=maxMsgSize, 0x0B=transports.
 */
data class CtapGetInfoResponse(
    val versions: List<String> = listOf("FIDO_2_0", "FIDO_2_1"),
    val aaguid: ByteArray,
    val options: Map<String, Boolean> = linkedMapOf("rk" to true, "uv" to true),
    val transports: List<String> = listOf("hybrid", "internal"),
) {
    /** Encodes just the CBOR payload (without the leading status byte). */
    fun encode(): ByteArray {
        val map = linkedMapOf<Long, Any>(
            1L to versions,
            3L to aaguid,
            4L to LinkedHashMap(options),
            0x0BL to transports,
        )
        return Cbor.encode(map)
    }
}
