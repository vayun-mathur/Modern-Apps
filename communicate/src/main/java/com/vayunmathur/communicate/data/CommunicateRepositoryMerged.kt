package com.vayunmathur.communicate.data

import android.content.Context
import android.provider.CallLog
import android.provider.Telephony
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceClient
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceSession
import com.vayunmathur.communicate.data.googlevoice.GvCall
import com.vayunmathur.communicate.data.googlevoice.GvCallType
import com.vayunmathur.communicate.data.googlevoice.GvVoicemail
import com.vayunmathur.communicate.data.googlevoice.GvMessage
import com.vayunmathur.communicate.data.googlevoice.GvThread
import com.vayunmathur.communicate.data.signal.SignalClient
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.signal.poll
import com.vayunmathur.communicate.data.signal.sendContactCard
import com.vayunmathur.communicate.data.signal.sendPollVote
import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient
import com.vayunmathur.communicate.data.whatsapp.deleteChat
import com.vayunmathur.communicate.data.whatsapp.sendContact
import com.vayunmathur.communicate.data.whatsapp.sendPoll
import com.vayunmathur.communicate.data.whatsapp.sendPollVote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CommunicateRepository merged views, cross-line dispatch, delete routing and
 * Google Voice helpers (split from CommunicateRepository.kt for file length).
 *
 * Extension functions on [CommunicateRepository]; behavior identical, call sites unchanged.
 */

/** Toggle a Google Voice thread attribute (read/archive/spam) via batchupdateattributes. */
suspend fun CommunicateRepository.updateGoogleVoiceThread(
    context: Context,
    remoteId: String,
    action: com.vayunmathur.communicate.data.googlevoice.GoogleVoiceParser.ThreadAction,
): Boolean = runCatching {
    GoogleVoiceClient.get(context).updateThreadAttributes(remoteId, action)
    true
}.getOrDefault(false)

internal suspend fun CommunicateRepository.loadGoogleVoiceThreads(context: Context): List<SmsThread> {
    val session = GoogleVoiceSession.get(context)
    if (!session.hasUsableCredentials()) return emptyList()
    return runCatching {
        GoogleVoiceClient.get(context).listThreads().map { it.toSmsThread(context) }
    }.getOrDefault(emptyList())
}

internal suspend fun CommunicateRepository.loadGoogleVoiceCalls(context: Context): List<CommunicateCallLogEntry> {
    val session = GoogleVoiceSession.get(context)
    if (!session.hasUsableCredentials()) return emptyList()
    return runCatching {
        GoogleVoiceClient.get(context).listCalls().map { it.toCallLogEntry(context) }
    }.getOrDefault(emptyList())
}

/** Google Voice voicemails (folder 4) for the Voicemail tab; empty when signed out. */
internal suspend fun CommunicateRepository.loadGoogleVoiceVoicemails(context: Context): List<VoicemailEntry> {
    val session = GoogleVoiceSession.get(context)
    if (!session.hasUsableCredentials()) return emptyList()
    return runCatching {
        val cookie = session.cookieHeader().orEmpty()
        val headers = if (cookie.isNotBlank()) mapOf("Cookie" to cookie) else emptyMap()
        GoogleVoiceClient.get(context).listVoicemails().map { it.toVoicemailEntry(context, headers) }
    }.getOrDefault(emptyList())
}

/** Voicemail tab contents: carrier rows plus GV rows, newest first. */
suspend fun CommunicateRepository.loadVoicemailsMerged(context: Context): List<VoicemailEntry> =
    withContext(Dispatchers.IO) {
        (loadCarrierVoicemails(context) + loadGoogleVoiceVoicemails(context))
            .sortedByDescending { it.timestampMillis }
    }

/** Mark a voicemail read, routing by line (provider write vs GV batchupdate). */
suspend fun CommunicateRepository.markVoicemailEntryRead(
    context: Context,
    entry: VoicemailEntry,
): Boolean = withContext(Dispatchers.IO) {
    when (entry.line) {
        CommunicateLine.GoogleVoice -> {
            val remoteId = entry.remoteId ?: return@withContext false
            runCatching {
                GoogleVoiceClient.get(context).markVoicemailRead(remoteId)
                true
            }.getOrDefault(false)
        }
        else -> markVoicemailRead(context, entry, read = true)
    }
}

/** Delete/archive a voicemail, routing by line (provider delete vs GV archive). */
suspend fun CommunicateRepository.deleteVoicemailEntry(
    context: Context,
    entry: VoicemailEntry,
): Boolean = withContext(Dispatchers.IO) {
    when (entry.line) {
        CommunicateLine.GoogleVoice -> {
            val remoteId = entry.remoteId ?: return@withContext false
            runCatching {
                GoogleVoiceClient.get(context).updateThreadAttributes(
                    remoteId,
                    com.vayunmathur.communicate.data.googlevoice.GoogleVoiceParser.ThreadAction.Archive,
                )
                true
            }.getOrDefault(false)
        }
        else -> deleteVoicemail(context, entry)
    }
}

// ── Delete ( #542 ) ────────────────────────────────────────────────

/** Delete an entire conversation/thread, routing by [CommunicateLine]. */
suspend fun CommunicateRepository.deleteConversation(
    context: Context,
    thread: SmsThread): Boolean = withContext(Dispatchers.IO) {
    when (thread.line) {
        CommunicateLine.Sim -> deleteSimThread(context, thread.threadId)
        CommunicateLine.GoogleVoice -> thread.remoteId?.let {
            runCatching {
                GoogleVoiceClient.get(context).updateThreadAttributes(
                    it,
                    com.vayunmathur.communicate.data.googlevoice.GoogleVoiceParser.ThreadAction.Archive)
                true
            }.getOrDefault(false)
        } ?: false
        CommunicateLine.WhatsApp -> thread.remoteId?.let { jid ->
            runCatching { WhatsAppClient.deleteChat(jid, leaveGroup = true) }.getOrDefault(false)
        } ?: false
        CommunicateLine.Signal -> thread.remoteId?.let { id ->
            runCatching {
                val db = SignalDatabase.getDatabase(context)
                db.conversationDao().delete(id)
                db.cachedMessageDao().deleteConversation(id)
                true
            }.getOrDefault(false)
        } ?: false
        CommunicateLine.Rcs -> thread.remoteId?.let { id ->
            runCatching {
                val db = com.vayunmathur.communicate.data.rcs.RcsDatabase.getDatabase(context)
                db.conversationDao().delete(id)
                db.cachedMessageDao().deleteConversation(id)
                true
            }.getOrDefault(false)
        } ?: false
    }
}

private fun CommunicateRepository.deleteSimThread(context: Context, threadId: Long): Boolean = runCatching {
    // Provider deletes the thread + its SMS/MMS rows when the conversation is removed.
    val deleted = context.contentResolver.delete(
        android.net.Uri.parse("content://mms-sms/conversations/$threadId"), null, null,
    )
    if (deleted > 0) return true
    // Fallback: delete SMS and MMS rows directly.
    context.contentResolver.delete(
        Telephony.Sms.CONTENT_URI,
        "${Telephony.Sms.THREAD_ID} = ?",
        arrayOf(threadId.toString()))
    context.contentResolver.delete(
        Telephony.Mms.CONTENT_URI,
        "${Telephony.Mms.THREAD_ID} = ?",
        arrayOf(threadId.toString()))
    true
}.getOrDefault(false)

/** Delete a call-log entry, routing by [CommunicateLine]. */
suspend fun CommunicateRepository.deleteCallLog(
    context: Context,
    entry: CommunicateCallLogEntry): Boolean = withContext(Dispatchers.IO) {
    when (entry.line) {
        CommunicateLine.Sim -> runCatching {
            context.contentResolver.delete(
                CallLog.Calls.CONTENT_URI, "${CallLog.Calls._ID} = ?", arrayOf(entry.id.toString()),
            ) >= 0
        }.getOrDefault(false)
        CommunicateLine.GoogleVoice -> entry.let {
            // GV calls are read-only from the GV API; treat as no-op success locally.
            true
        }
        else -> false
    }
}

/** Whether [line] can send a poll. Only the two in-app protocols have one. */
fun CommunicateRepository.canSendPoll(line: CommunicateLine): Boolean = when (line) {
    CommunicateLine.WhatsApp -> com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled
    CommunicateLine.Signal -> com.vayunmathur.communicate.data.signal.SignalFeature.enabled
    CommunicateLine.Rcs -> com.vayunmathur.communicate.data.rcs.RcsFeature.enabled
    else -> false
}

/**
 * Whether [line] can share a contact. MMS could carry a vCard, but inbound vCard parts are currently
 * discarded, so offering it there would send something the recipient cannot open.
 */
fun CommunicateRepository.canShareContact(line: CommunicateLine): Boolean = when (line) {
    CommunicateLine.WhatsApp -> com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled
    CommunicateLine.Signal -> com.vayunmathur.communicate.data.signal.SignalFeature.enabled
    CommunicateLine.Rcs -> com.vayunmathur.communicate.data.rcs.RcsFeature.enabled
    else -> false
}

/** Send a poll on whichever line owns [conversationId]. */
suspend fun CommunicateRepository.sendPoll(
    context: Context,
    line: CommunicateLine,
    conversationId: String,
    question: String,
    options: List<String>,
): Boolean = withContext(Dispatchers.IO) {
    when (line) {
        CommunicateLine.WhatsApp -> WhatsAppClient.sendPoll(conversationId, question, options, false) != null
        CommunicateLine.Signal ->
            SignalClient.get(context).poll(conversationId, question, options) != null
        else -> false
    }
}

/**
 * Vote in a poll. [selectedOptions] are option names, which is what both protocols hash or index.
 *
 * WhatsApp additionally binds a vote to the poll's creator, so [pollCreatorId] and [pollFromMe] are required
 * there; Signal identifies the poll by its message id alone.
 */
suspend fun CommunicateRepository.sendPollVote(
    context: Context,
    line: CommunicateLine,
    conversationId: String,
    pollMessageId: String,
    selectedOptions: List<String>,
    pollCreatorId: String? = null,
    pollFromMe: Boolean = false,
): Boolean = withContext(Dispatchers.IO) {
    when (line) {
        CommunicateLine.WhatsApp -> WhatsAppClient.sendPollVote(
            conversationId,
            pollMessageId,
            pollCreatorId ?: conversationId,
            pollFromMe,
            selectedOptions,
        )
        CommunicateLine.Signal ->
            SignalClient.get(context).sendPollVote(conversationId, pollMessageId, selectedOptions)
        else -> false
    }
}

/** Share a contact card on whichever line owns the conversation. */
suspend fun CommunicateRepository.shareContact(
    context: Context,
    line: CommunicateLine,
    conversationId: String,
    contact: com.vayunmathur.communicate.ui.SharedContactCard,
): Boolean = withContext(Dispatchers.IO) {
    when (line) {
        CommunicateLine.Signal -> SignalClient.get(context).sendContactCard(
            conversationId = conversationId,
            givenName = contact.givenName,
            familyName = contact.familyName,
            phoneNumbers = contact.phoneNumbers,
            emails = contact.emails,
        )
        CommunicateLine.WhatsApp -> {
            // WhatsApp carries a vCard rather than a structured card.
            val display = listOfNotNull(contact.givenName, contact.familyName)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            WhatsAppClient.sendContact(conversationId, display, buildVCard(contact))
        }
        else -> false
    }
}

/** Minimal vCard 3.0 — what WhatsApp expects for a shared contact. */
private fun CommunicateRepository.buildVCard(contact: com.vayunmathur.communicate.ui.SharedContactCard): String {
    val display = listOfNotNull(contact.givenName, contact.familyName)
        .filter { it.isNotBlank() }
        .joinToString(" ")
    return buildString {
        appendLine("BEGIN:VCARD")
        appendLine("VERSION:3.0")
        appendLine("N:${contact.familyName.orEmpty()};${contact.givenName};;;")
        appendLine("FN:$display")
        contact.phoneNumbers.forEach { (number, label) ->
            val type = label?.takeIf { it.isNotBlank() } ?: "CELL"
            appendLine("TEL;TYPE=$type:$number")
        }
        contact.emails.forEach { appendLine("EMAIL:$it") }
        append("END:VCARD")
    }
}

internal fun GvThread.toSmsThread(context: Context): SmsThread = SmsThread(
    threadId = CommunicateRepository.stableThreadId(id),
    address = phoneNumber,
    displayName = displayName ?: CommunicateRepository.findContactName(context, phoneNumber),
    snippet =
        snippet.ifBlank { if (messages.any { it.hasMedia }) context.getString(R.string.media_message) else "" },
    timestampMillis = timestampMillis,
    unreadCount = unreadCount,
    line = CommunicateLine.GoogleVoice,
    remoteId = id,
)

internal fun GvMessage.toSmsMessage(threadId: Long, context: Context): SmsMessage = SmsMessage(
    id = ("$threadId#$id").hashCode().toLong(),
    threadId = threadId,
    address = phoneNumber,
    body = text.ifBlank { if (hasMedia) context.getString(R.string.media_message) else "" },
    timestampMillis = timestampMillis,
    outgoing = outgoing,
    read = read,
    line = CommunicateLine.GoogleVoice,
    remoteId = id,
    attachments = mediaUrls.map { CommunicateAttachment(it, "image/*") },
)

internal fun GvCall.toCallLogEntry(context: Context): CommunicateCallLogEntry = CommunicateCallLogEntry(
    id = CommunicateRepository.stableThreadId(id),
    displayName = displayName ?: CommunicateRepository.findContactName(context, phoneNumber),
    phoneNumber = phoneNumber,
    type = type.toCommunicateCallType(),
    timestampMillis = timestampMillis,
    durationSeconds = durationSeconds,
    line = CommunicateLine.GoogleVoice,
)

internal fun GvVoicemail.toVoicemailEntry(context: Context, audioHeaders: Map<String, String>): VoicemailEntry =
    VoicemailEntry(
        id = CommunicateRepository.stableThreadId(id),
        phoneNumber = phoneNumber,
        timestampMillis = timestampMillis,
        durationSeconds = durationSeconds,
        contentUri = audioUrl.orEmpty(),
        mimeType = "audio/mpeg",
        hasAudio = !audioUrl.isNullOrBlank(),
        transcript = transcript,
        isRead = read,
        subscriptionId = null,
        line = CommunicateLine.GoogleVoice,
        audioHeaders = audioHeaders,
        remoteId = id,
    )

private fun GvCallType.toCommunicateCallType(): CommunicateCallType = when (this) {
    GvCallType.Incoming -> CommunicateCallType.Incoming
    GvCallType.Outgoing -> CommunicateCallType.Outgoing
    GvCallType.Missed -> CommunicateCallType.Missed
    GvCallType.Voicemail -> CommunicateCallType.Voicemail
    GvCallType.Unknown -> CommunicateCallType.Unknown
}
