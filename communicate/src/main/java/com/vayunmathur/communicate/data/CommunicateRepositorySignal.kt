package com.vayunmathur.communicate.data

import android.content.Context
import android.telephony.TelephonyManager
import com.vayunmathur.communicate.data.signal.SignalCachedMessage
import com.vayunmathur.communicate.data.signal.SignalClient
import com.vayunmathur.communicate.data.signal.SignalConversation
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.signal.SignalFeature
import com.vayunmathur.communicate.data.signal.SignalLineSession
import com.vayunmathur.communicate.data.signal.SignalServiceData
import com.vayunmathur.communicate.data.signal.acceptIdentityChange
import com.vayunmathur.communicate.data.signal.createGroup
import com.vayunmathur.communicate.data.signal.editMessage
import com.vayunmathur.communicate.data.signal.pendingIdentityChange
import com.vayunmathur.communicate.data.signal.poll
import com.vayunmathur.communicate.data.signal.readReceipt
import com.vayunmathur.communicate.data.signal.revoke
import com.vayunmathur.communicate.data.signal.safetyNumber
import com.vayunmathur.communicate.data.signal.sendMedia
import com.vayunmathur.communicate.data.signal.sendPollVote
import com.vayunmathur.communicate.data.signal.sendReaction
import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CommunicateRepository Signal primary line (split from CommunicateRepository.kt for file length).
 *
 * Threads/messages from Room (daemon writes); rich actions delegate to SignalClient —
 * mirrors the WhatsApp section. Extension functions on [CommunicateRepository];
 * behavior identical, call sites unchanged.
 */

// ------------------------------------------------------------------
// Signal primary line: threads/messages from Room (daemon writes),
// rich actions delegate to SignalClient — mirrors WhatsApp section.
// ------------------------------------------------------------------

internal suspend fun CommunicateRepository.loadSignalThreads(context: Context): List<SmsThread> {
    if (!SignalFeature.enabled) return emptyList()
    if (!SignalLineSession.get(context).isSignedIn()) return emptyList()
    return runCatching {
        val db = SignalDatabase.getDatabase(context)
        db.cachedMessageDao().getLatestPerConversation().map { m ->
            val sd = SignalServiceData.parse(m.serviceData)
            val cid = m.conversationId
            val conv = db.conversationDao().getConversation(cid)
            val isGroup = conv?.isGroup ?: sd?.isGroup ?: false
            val unread = conv?.unreadCount ?: 0
            val participants = parseParticipantsCsv(conv?.participants)
            val groupTitle = conv?.name?.takeIf { it.isNotBlank() }
            SmsThread(
                threadId = stableThreadId(cid),
                address = signalJidToDisplayAddress(cid),
                displayName = if (isGroup) (groupTitle ?: signalDisplayName(context, cid, sd))
                    else signalDisplayName(context, cid, sd),
                snippet = if (m.isRevoked) "This message was deleted" else m.body,
                timestampMillis = m.timestamp,
                unreadCount = unread,
                line = CommunicateLine.Signal,
                remoteId = cid,
                isGroup = isGroup,
                avatarUrl = null,
                participants = participants,
                groupTitle = groupTitle,
            )
        }
    }.getOrDefault(emptyList())
}

internal suspend fun CommunicateRepository.loadSignalMessages(
    context: Context,
    conversationId: String): List<SmsMessage> =
    runCatching {
        val db = SignalDatabase.getDatabase(context)
        db.cachedMessageDao().getForConversationBefore(conversationId).asReversed().map { m ->
            SmsMessage(
                id = (m.messageId.hashCode().toLong() and 0xFFFFFFFFL),
                threadId = stableThreadId(conversationId),
                address = signalJidToDisplayAddress(conversationId),
                body = if (m.isRevoked) "" else m.body,
                timestampMillis = m.timestamp,
                outgoing = m.outgoing,
                read = true,
                line = CommunicateLine.Signal,
                remoteId = m.messageId,
                serviceData = m.serviceData,
                senderAddress = m.senderId.takeIf { it.isNotBlank() && !m.outgoing },
                status = m.status.let { s ->
                    MessageStatus.entries.getOrElse(s) { MessageStatus.None }
                },
            )
        }
    }.getOrDefault(emptyList())

// -- Signal rich actions (pass-through delegates) --

suspend fun CommunicateRepository.sendSignalReaction(
    conversationId: String,
    messageId: String,
    emoji: String,
): Boolean = withContext(Dispatchers.IO) {
    SignalClient.sendReaction(conversationId, messageId, emoji)
}

suspend fun CommunicateRepository.editSignalMessage(
    conversationId: String,
    messageId: String,
    newBody: String,
): Boolean = withContext(Dispatchers.IO) { SignalClient.editMessage(conversationId, messageId, newBody) }

suspend fun CommunicateRepository.revokeSignalMessage(
    conversationId: String,
    messageId: String,
): Boolean = withContext(Dispatchers.IO) { SignalClient.revoke(conversationId, messageId) }

suspend fun CommunicateRepository.sendSignalPoll(
    conversationId: String,
    question: String,
    options: List<String>,
): String? = withContext(Dispatchers.IO) { SignalClient.poll(conversationId, question, options) }

suspend fun CommunicateRepository.sendSignalPollVote(
    conversationId: String,
    pollMessageId: String,
    selectedOptions: List<String>,
): Boolean = withContext(Dispatchers.IO) { SignalClient.sendPollVote(conversationId, pollMessageId, selectedOptions) }

suspend fun CommunicateRepository.sendSignalMedia(
    conversationId: String,
    bytes: ByteArray,
    mimeType: String,
): String? = withContext(Dispatchers.IO) { SignalClient.sendMedia(conversationId, bytes, mimeType) }

suspend fun CommunicateRepository.sendSignalReadReceipt(
    conversationId: String,
    lastMessageId: String?,
    lastTimestamp: Long,
): Boolean = withContext(Dispatchers.IO) { SignalClient.readReceipt(conversationId, lastMessageId, lastTimestamp) }

suspend fun CommunicateRepository.markSignalRead(
    context: Context,
    remoteId: String?,
    address: String,
    alreadyReadId: String?,
): String? = withContext(Dispatchers.IO) {
    val cid = remoteId?.takeIf { it.isNotBlank() } ?: toSignalRecipient(context, address)
    val db = SignalDatabase.getDatabase(context)
    runCatching {
        db.conversationDao().getConversation(cid)?.let {
            if (it.unreadCount != 0) db.conversationDao().upsert(it.copy(unreadCount = 0))
        }
    }
    val lastInbound = runCatching {
        db.cachedMessageDao().getForConversation(cid).lastOrNull { !it.outgoing }
    }.getOrNull() ?: return@withContext alreadyReadId
    if (lastInbound.messageId == alreadyReadId) return@withContext alreadyReadId
    runCatching { SignalClient.readReceipt(cid, lastInbound.messageId, lastInbound.timestamp) }
    lastInbound.messageId
}

/**
 * The pending identity-key change for a Signal conversation, as (safety number, key hex), or null
 * when there is nothing to verify. The hex is passed back to [acceptSignalIdentity] so acceptance
 * can only apply to the key whose number was shown.
 */
suspend fun CommunicateRepository.signalPendingIdentityChange(
    context: Context,
    remoteId: String?,
    address: String,
): Pair<String, String>? = withContext(Dispatchers.IO) {
    val aci = remoteId?.takeIf { it.isNotBlank() } ?: toSignalRecipient(context, address)
    val pending = SignalClient.pendingIdentityChange(aci) ?: return@withContext null
    val safetyNumber = SignalClient.safetyNumber(aci) ?: return@withContext null
    safetyNumber to pending.joinToString("") { "%02x".format(it) }
}

suspend fun CommunicateRepository.acceptSignalIdentity(
    context: Context,
    remoteId: String?,
    address: String,
    keyHex: String,
): Boolean = withContext(Dispatchers.IO) {
    val aci = remoteId?.takeIf { it.isNotBlank() } ?: toSignalRecipient(context, address)
    SignalClient.acceptIdentityChange(aci, keyHex)
}

suspend fun CommunicateRepository.createSignalGroup(
    context: Context,
    subject: String,
    contacts: List<String>,
): String? = withContext(Dispatchers.IO) {
    val ids = contacts.map { toSignalRecipient(context, it) }.filter { it.isNotBlank() }.distinct()
    if (ids.isEmpty()) return@withContext null
    SignalClient.createGroup(subject, ids)
}

fun CommunicateRepository.isSignalConnected(): Boolean = SignalClient.isConnected()

private val uuidPattern =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

/**
 * Build a Signal recipient id from a phone number / address / ACI.
 * Already-qualified identifiers (contain @ or look like a UUID ACI/PNI) pass through.
 * Otherwise the phone number is normalized to E.164 via libphonenumber.
 */
internal fun CommunicateRepository.toSignalRecipient(context: Context, address: String): String {
    if (address.isBlank()) return ""
    // Group or already-qualified identifier.
    if (address.contains("@")) return address
    // UUID-shaped ACI/PNI.
    if (address.matches(uuidPattern)) return address
    val region = runCatching {
        val tm = context.getSystemService(TelephonyManager::class.java)
        (tm?.simCountryIso?.takeIf { it.isNotBlank() } ?: tm?.networkCountryIso)?.uppercase()
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: context.resources.configuration.locales[0].country.ifEmpty { "US" }
    val e164 = runCatching {
        val util = com.google.i18n.phonenumbers.PhoneNumberUtil.getInstance()
        val parsed = util.parse(address, region)
        util.format(parsed, com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat.E164)
    }.getOrNull()
    return e164 ?: address
}

private fun CommunicateRepository.signalJidToDisplayAddress(conversationId: String): String {
    // Group ids contain ':' or look like UUIDs — keep as-is for group rendering.
    if (conversationId.contains(":") || conversationId.contains("group")) return conversationId
    // ACI/PNI UUIDs are not phone numbers — keep as-is; UI will resolve via contacts.
    if (conversationId.matches(uuidPattern)) return conversationId
    return conversationId
}

private fun CommunicateRepository.signalDisplayName(
    context: Context,
    conversationId: String,
    sd: SignalServiceData?): String? {
    // Group name from conversation metadata already handled; use senderName or contact lookup.
    if (conversationId.matches(Regex("[0-9a-fA-F]{8}-.*"))) return sd?.senderName
    return findContactName(context, conversationId) ?: sd?.senderName ?: conversationId
}

/** Insert an outgoing Signal message into the local cache so it shows in our own thread. */
internal suspend fun CommunicateRepository.cacheOutgoingSignal(
    context: Context,
    conversationId: String,
    body: String,
    messageId: String) {
    runCatching {
        val db = SignalDatabase.getDatabase(context)
        val now = System.currentTimeMillis()
        db.cachedMessageDao().upsert(
            SignalCachedMessage(
                messageId = messageId,
                conversationId = conversationId,
                body = body,
                timestamp = now,
                outgoing = true,
                status = 1,
            ),
        )
        val existing = db.conversationDao().getConversation(conversationId)
        db.conversationDao().upsert(
            (existing ?: SignalConversation(chatId = conversationId)).copy(lastMessageTimestamp = now),
        )
    }
}
