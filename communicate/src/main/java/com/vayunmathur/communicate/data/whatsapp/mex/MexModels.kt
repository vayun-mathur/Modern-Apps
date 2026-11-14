package com.vayunmathur.communicate.data.whatsapp.mex

import org.json.JSONObject

/**
 * A single GraphQL error entry from a MEX/WWW response `{"errors":[{message, code, …}]}`.
 */
data class MexError(
    val message: String?,
    val code: Int?,
)

/**
 * Result of a MEX (`w:mex` XMPP IQ) or WWW GraphQL call.
 *
 * - [data]: the `data` object of the GraphQL envelope `{"data":…,"errors":…}` (null on failure).
 * - [errors]: GraphQL-level errors returned by the server (may be non-empty even with partial data).
 * - [transportError]: a client/transport-side failure that never reached the GraphQL layer, e.g.
 *   `no_persisted_id:<op>` (the operation's doc_id isn't in the bundled JSON), `timeout`, `iq_error`,
 *   or `http:<status>`. Null when the request completed at the GraphQL layer.
 *
 * [isSuccess] is true only when there was no transport error and no GraphQL errors.
 */
data class MexResult(
    val data: JSONObject?,
    val errors: List<MexError> = emptyList(),
    val transportError: String? = null,
) {
    val isSuccess: Boolean get() = transportError == null && errors.isEmpty()

    /** Factories for [MexResult] (transport failures). */
    companion object {
        /** Build a transport-level failure result (no GraphQL round-trip completed). */
        fun transport(reason: String): MexResult = MexResult(null, emptyList(), reason)

        /**
         * Parse a GraphQL envelope string `{"data":…,"errors":[…]}` into a [MexResult].
         * Runtime-only (Android `org.json`); envelope *building* is done JVM-pure in [MexEnvelope].
         */
        fun fromEnvelope(envelopeJson: String): MexResult {
            return try {
                val root = JSONObject(envelopeJson)
                val data = root.optJSONObject("data")
                val errorsArr = root.optJSONArray("errors")
                val errors = buildList {
                    if (errorsArr != null) {
                        for (i in 0 until errorsArr.length()) {
                            val e = errorsArr.optJSONObject(i) ?: continue
                            add(
                                MexError(
                                    message = e.optString("message").ifEmpty { null },
                                    code = if (e.has("code") && !e.isNull("code")) e.optInt("code") else null,
                                ),
                            )
                        }
                    }
                }
                MexResult(data = data, errors = errors, transportError = null)
            } catch (expected: Throwable) {
                transport("parse_error:${expected.message}")
            }
        }
    }
}
