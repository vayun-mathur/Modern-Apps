package com.vayunmathur.library.intents.communicate

import kotlinx.serialization.Serializable

/**
 * Shared wire DTOs for communicate's cross-app assistant intents
 * ([com.vayunmathur.library.util.AssistantIntent]): `FindConversationsIntent`,
 * `GetMessagesIntent` and `SendMessageIntent` in `com.vayunmathur.communicate.intents`,
 * answered only for a co-signed caller behind
 * `com.vayunmathur.communicate.permissions.ACCESS_COMMUNICATE`.
 *
 * Thin and UI-free by design: line is the `CommunicateLine` name (`Sim`, `GoogleVoice`,
 * `WhatsApp`, `Signal`, `Rcs`) as a plain string so this package never depends on the
 * communicate app's own types. Every field has a default so older/newer callers can
 * omit fields without breaking decode (library DTO-compat rule).
 */

/** Find conversations whose name, address or snippet contains [query] (blank = all). */
@Serializable
data class FindConversationsQuery(
    val query: String = "",
    val limit: Int = 5,
)

/**
 * One conversation as the assistant sees it.
 *
 * @property threadKey stable key `"<line>:<id>"` (e.g. `Sim:42`, `WhatsApp:123@s.whatsapp.net`,
 * `GoogleVoice:t.<id>`); hand it back verbatim to [GetMessagesQuery].
 */
@Serializable
data class ConversationData(
    val threadKey: String = "",
    val address: String = "",
    val displayName: String? = null,
    val snippet: String = "",
    val line: String = "",
    val isGroup: Boolean = false,
)

/** Read the messages of the conversation identified by [threadKey]. */
@Serializable
data class GetMessagesQuery(
    val threadKey: String = "",
    val limit: Int = 10,
)

/** One message in a conversation, oldest-first within the returned window. */
@Serializable
data class ConversationMessageData(
    val body: String = "",
    val address: String = "",
    val timestampMillis: Long = 0L,
    val outgoing: Boolean = false,
    val line: String = "",
)

/**
 * Send [body] to [address] on [line]. [threadRemoteId] is the provider-side thread /
 * conversation id (WhatsApp JID, Signal recipient, RCS/GV thread id) when replying to
 * an existing conversation; null starts (or resolves) a 1:1 from [address] alone.
 */
@Serializable
data class SendMessageRequest(
    val address: String = "",
    val line: String = "",
    val body: String = "",
    val threadRemoteId: String? = null,
)

/**
 * Outcome of a [SendMessageRequest]. Failures are typed results, never exceptions:
 * [error] is a stable machine-readable code (e.g. `GOOGLE_VOICE_REQUIRES_ACTIVITY`,
 * `INVALID_ADDRESS`, `INVALID_BODY`, `UNKNOWN_LINE`, `SEND_FAILED`).
 */
@Serializable
data class SendMessageResult(
    val success: Boolean = false,
    val error: String? = null,
)
