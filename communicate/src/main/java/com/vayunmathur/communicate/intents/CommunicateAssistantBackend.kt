package com.vayunmathur.communicate.intents

import android.content.Context
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.LineChoice
import com.vayunmathur.communicate.data.SimManager
import com.vayunmathur.communicate.data.SmsMessage
import com.vayunmathur.communicate.data.SmsThread
import com.vayunmathur.communicate.data.loadSmsMessagesMerged
import com.vayunmathur.communicate.data.loadSmsThreadsMerged
import com.vayunmathur.communicate.data.sendMessage
import com.vayunmathur.library.intents.communicate.ConversationData
import com.vayunmathur.library.intents.communicate.ConversationMessageData
import com.vayunmathur.library.intents.communicate.FindConversationsQuery
import com.vayunmathur.library.intents.communicate.GetMessagesQuery
import com.vayunmathur.library.intents.communicate.SendMessageRequest
import com.vayunmathur.library.intents.communicate.SendMessageResult

/** Typed send-failure codes returned in [SendMessageResult.error] (never thrown). */
internal const val ERROR_GOOGLE_VOICE_REQUIRES_ACTIVITY = "GOOGLE_VOICE_REQUIRES_ACTIVITY"
internal const val ERROR_INVALID_ADDRESS = "INVALID_ADDRESS"
internal const val ERROR_INVALID_BODY = "INVALID_BODY"
internal const val ERROR_UNKNOWN_LINE = "UNKNOWN_LINE"
internal const val ERROR_SEND_FAILED = "SEND_FAILED"

internal const val DEFAULT_FIND_LIMIT = 5
internal const val MAX_FIND_LIMIT = 50
internal const val DEFAULT_MESSAGES_LIMIT = 10
internal const val MAX_MESSAGES_LIMIT = 100

/**
 * Repository seam behind the communicate assistant intents. Production delegates to
 * [CommunicateRepository]; tests install a fake per line so provider logic (filtering,
 * limits, validation, Google Voice exclusion) is asserted without telephony/network.
 */
internal interface CommunicateAssistantBackend {
    suspend fun loadThreads(context: Context): List<SmsThread>
    suspend fun loadMessages(context: Context, thread: SmsThread): List<SmsMessage>
    suspend fun send(
        context: Context,
        choice: LineChoice,
        address: String,
        body: String,
        threadRemoteId: String?,
    ): Boolean
}

internal object RealCommunicateAssistantBackend : CommunicateAssistantBackend {
    override suspend fun loadThreads(context: Context): List<SmsThread> =
        with(CommunicateRepository) { loadSmsThreadsMerged(context) }

    override suspend fun loadMessages(context: Context, thread: SmsThread): List<SmsMessage> =
        with(CommunicateRepository) { loadSmsMessagesMerged(context, thread) }

    override suspend fun send(
        context: Context,
        choice: LineChoice,
        address: String,
        body: String,
        threadRemoteId: String?,
    ): Boolean = with(CommunicateRepository) {
        sendMessage(context, choice, address, body, threadRemoteId)
    }
}

/** Test seam, mirroring the `overrideForTest` singletons elsewhere in this app. */
internal var communicateAssistantBackendForTest: CommunicateAssistantBackend? = null

internal fun communicateAssistantBackend(): CommunicateAssistantBackend =
    communicateAssistantBackendForTest ?: RealCommunicateAssistantBackend

/** `"<line>:<id>"` — remote id when the line has one, else the numeric thread id. */
internal fun SmsThread.threadKey(): String =
    "${line.name}:${remoteId?.takeIf { it.isNotBlank() } ?: threadId}"

internal fun SmsThread.toConversationData(): ConversationData = ConversationData(
    threadKey = threadKey(),
    address = address,
    displayName = displayName,
    snippet = snippet,
    line = line.name,
    isGroup = isGroup,
)

internal fun SmsMessage.toConversationMessageData(): ConversationMessageData =
    ConversationMessageData(
        body = body,
        address = address,
        timestampMillis = timestampMillis,
        outgoing = outgoing,
        line = line.name,
    )

internal fun parseCommunicateLine(raw: String): CommunicateLine? {
    val normalized = raw.trim().replace(" ", "")
    if (normalized.isEmpty()) return null
    return CommunicateLine.entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
}

internal suspend fun findConversations(
    context: Context,
    input: FindConversationsQuery,
    backend: CommunicateAssistantBackend,
): List<ConversationData> {
    val threads = runCatching { backend.loadThreads(context) }.getOrDefault(emptyList())
    val query = input.query.trim()
    val filtered = if (query.isEmpty()) {
        threads
    } else {
        threads.filter { thread ->
            thread.displayName?.contains(query, ignoreCase = true) == true ||
                thread.address.contains(query, ignoreCase = true) ||
                thread.snippet.contains(query, ignoreCase = true) ||
                thread.participants.any { it.contains(query, ignoreCase = true) }
        }
    }
    val limit = if (input.limit <= 0) DEFAULT_FIND_LIMIT else input.limit.coerceAtMost(MAX_FIND_LIMIT)
    return filtered.take(limit).map { it.toConversationData() }
}

internal suspend fun getConversationMessages(
    context: Context,
    input: GetMessagesQuery,
    backend: CommunicateAssistantBackend,
): List<ConversationMessageData> {
    val key = input.threadKey.trim()
    if (key.isEmpty() || !key.contains(':')) return emptyList()
    val threads = runCatching { backend.loadThreads(context) }.getOrDefault(emptyList())
    val thread = threads.firstOrNull { it.threadKey() == key } ?: return emptyList()
    val messages = runCatching { backend.loadMessages(context, thread) }.getOrDefault(emptyList())
    val limit = if (input.limit <= 0) {
        DEFAULT_MESSAGES_LIMIT
    } else {
        input.limit.coerceAtMost(MAX_MESSAGES_LIMIT)
    }
    // Loaders return oldest-first; keep the most recent window in that order.
    return messages.takeLast(limit).map { it.toConversationMessageData() }
}

internal suspend fun sendAssistantMessage(
    context: Context,
    input: SendMessageRequest,
    backend: CommunicateAssistantBackend,
): SendMessageResult {
    val address = input.address.trim()
    val body = input.body.trim()
    if (address.isEmpty()) return SendMessageResult(success = false, error = ERROR_INVALID_ADDRESS)
    if (body.isEmpty()) return SendMessageResult(success = false, error = ERROR_INVALID_BODY)
    val line = parseCommunicateLine(input.line)
        ?: return SendMessageResult(success = false, error = ERROR_UNKNOWN_LINE)
    // Google Voice mints its bot-defense token in an offscreen WebView hosted by a
    // foreground Activity (see CommunicateRepositorySend). A NoDisplay intent has no
    // such Activity, so this can never succeed here — fail typed, never crash, and
    // never touch the repository (the caller falls back to opening the conversation).
    if (line == CommunicateLine.GoogleVoice) {
        return SendMessageResult(success = false, error = ERROR_GOOGLE_VOICE_REQUIRES_ACTIVITY)
    }
    val choice: LineChoice = when (line) {
        CommunicateLine.Sim -> runCatching { SimManager.simLineChoices(context).firstOrNull() }
            .getOrNull() ?: LineChoice.Sim(SimManager.defaultSmsSubscriptionId(), "SIM")
        CommunicateLine.Rcs -> LineChoice.Rcs
        CommunicateLine.WhatsApp -> LineChoice.WhatsApp
        CommunicateLine.Signal -> LineChoice.Signal
        CommunicateLine.GoogleVoice -> return SendMessageResult(
            success = false,
            error = ERROR_GOOGLE_VOICE_REQUIRES_ACTIVITY,
        )
    }
    val sent = runCatching {
        backend.send(context, choice, address, body, input.threadRemoteId?.takeIf { it.isNotBlank() })
    }.getOrDefault(false)
    return if (sent) {
        SendMessageResult(success = true, error = null)
    } else {
        SendMessageResult(success = false, error = ERROR_SEND_FAILED)
    }
}
