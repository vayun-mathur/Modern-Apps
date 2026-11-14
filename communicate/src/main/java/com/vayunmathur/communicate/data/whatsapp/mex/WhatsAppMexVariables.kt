package com.vayunmathur.communicate.data.whatsapp.mex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64 as JavaBase64

/**
 * JVM-pure `variables` builders for the MEX operations in [WhatsAppMexOps].
 *
 * Shapes follow the SDL argument names from w2.md §8.0 (a single `variables` map keyed by
 * argument name); JIDs and binary fields are standard Base64 (Argo BYTES over the JSON shim).
 * Kept separate from the operations so the builders are unit-testable off-device.
 */
object WhatsAppMexVariables {

    private const val PREKEY_VERSION_BYTE = 0x05
    private const val SHIFT_BYTE2 = 16
    private const val SHIFT_BYTE1 = 8

    private val json = Json { encodeDefaults = true }

fun buildGroupQueryByIdVariables(groupJid: String, queryContext: String): String =
    json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            putJsonObject("group_input") {
                put("group_jid", groupJid)
                put("query_context", queryContext)
            }
        },
    )

fun buildParticipatingGroupsVariables(userJid: String?): String =
    json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            putJsonObject("participating_groups_input") {
                if (userJid != null) put("id", userJid)
            }
        },
    )

fun buildGroupBatchQueryVariables(groupJids: List<String>, queryContext: String): String =
    json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            putJsonObject("batch_query_input") {
                putJsonArray("groups") {
                    groupJids.forEach { jid ->
                        addJsonObject {
                            put("group_jid", jid)
                            put("query_context", queryContext)
                        }
                    }
                }
            }
        },
    )

fun buildContactDiscoveryVariables(rawPhoneNumbers: List<String>, discoveryContext: String): String =
    json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            putJsonObject("input") {
                putJsonArray("contacts") {
                    rawPhoneNumbers.forEach { pn ->
                        addJsonObject { putJsonObject("phone") { put("raw_pn", pn) } }
                    }
                }
                put("context", discoveryContext)
            }
        },
    )

fun buildPrimaryContactsFullSyncVariables(
    rawPhoneNumbers: List<String>,
    sessionId: String,
    pageIndex: Int,
    last: Boolean,
    syncContext: String,
): String = json.encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
        putJsonObject("input") {
            putJsonArray("primary_contacts") {
                rawPhoneNumbers.forEach { pn ->
                    addJsonObject { putJsonObject("phone") { put("raw_pn", pn) } }
                }
            }
            putJsonObject("cursor") {
                put("session_id", sessionId)
                put("page_index", pageIndex)
                put("last", last)
            }
            put("context", syncContext)
        }
    },
)

fun buildUsernameCheckVariables(
    username: String,
    includeSuggestions: Boolean,
    source: String,
    sessionId: String?): String =
    json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("username", username)
            put("include_suggestions", includeSuggestions)
            put("source", source)
            if (sessionId != null) put("session_id", sessionId)
        },
    )

fun buildUsernameSetVariables(username: String, pin: String?, source: String, sessionId: String?): String =
    json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("username", username)
            put("source", source)
            if (pin != null) put("pin", pin)
            if (sessionId != null) put("session_id", sessionId)
        },
    )

fun buildBlocklistGetVariables(dhash: String?): String =
    json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject { if (dhash != null) put("dhash", dhash) },
    )

fun buildUpdateBlocklistLidVariables(
    userLid: String,
    identifierKey: String,
    identifierValue: String,
    action: String,
    dhash: String?,
): String = json.encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
        putJsonObject("block_input") {
            put("user", userLid)
            putJsonObject("identifier") { put(identifierKey, identifierValue) }
            put("action", action)
            if (dhash != null) put("dhash", dhash)
        }
    },
)

fun buildOnlineOrLastStatusVariables(lidJids: List<String>, lastActiveFilter: String?): String =
    json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            putJsonObject("get_online_or_last_status_input") {
                putJsonArray("online_or_last_status_input") {
                    lidJids.forEach { jid -> addJsonObject { put("jid", jid) } }
                }
                if (lastActiveFilter != null) put("last_active_filter", lastActiveFilter)
            }
        },
    )

fun buildNewsletterVariables(key: String, keyType: String, viewRole: String?): String =
    json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            putJsonObject("input") {
                put("key", key)
                put("type", keyType)
                if (viewRole != null) put("view_role", viewRole)
            }
        },
    )

fun buildNewsletterIdVariables(newsletterId: String): String =
    json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject { put("newsletter_id", newsletterId) },
    )

fun buildGetMessagingKeysVariables(userJids: List<String>): String =
    json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            putJsonObject("input") {
                putJsonArray("users") { userJids.forEach { add(it) } }
            }
        },
    )

/**
 * Pure builder for the `xwa2_set_messaging_keys` `variables` (§8.2). Binary fields are standard
 * Base64; ids are 3-byte big-endian (skey/prekey id space), `type` is Base64 of `0x05`.
 */
fun buildSetMessagingKeysVariables(
    identity: ByteArray,
    skeyId: Int,
    skeyValue: ByteArray,
    skeySignature: ByteArray,
    prekeys: List<Pair<Int, ByteArray>>,
): String = json.encodeToString(
    JsonObject.serializer(),
    buildJsonObject {
        putJsonObject("input") {
            put("type", b64(byteArrayOf(PREKEY_VERSION_BYTE.toByte())))
            put("identity", b64(identity))
            putJsonObject("skey") {
                put("id", b64(int3BE(skeyId)))
                put("value", b64(skeyValue))
                put("signature", b64(skeySignature))
            }
            putJsonArray("prekeys") {
                prekeys.forEach { (id, value) ->
                    addJsonObject {
                        put("id", b64(int3BE(id)))
                        put("value", b64(value))
                    }
                }
            }
        }
    },
)

    /** Standard Base64 (with padding), JVM-pure so the builders are unit-testable off-device. */
    private fun b64(bytes: ByteArray): String = JavaBase64.getEncoder().encodeToString(bytes)

    /** 3-byte big-endian encoding of a prekey/skey id (matches the classic wire encoding). */
    private fun int3BE(id: Int): ByteArray = byteArrayOf(
        (id ushr SHIFT_BYTE2).toByte(),
        (id ushr SHIFT_BYTE1).toByte(),
        id.toByte(),
    )
}
