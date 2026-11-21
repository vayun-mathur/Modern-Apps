package com.vayunmathur.communicate.data

import androidx.core.net.toUri
import android.content.Context
import android.net.Uri
import android.telephony.TelephonyManager
import com.vayunmathur.communicate.data.whatsapp.WhatsAppCachedMessage
import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient
import com.vayunmathur.communicate.data.whatsapp.WhatsAppConversation
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDatabase
import com.vayunmathur.communicate.data.whatsapp.WhatsAppLineSession
import com.vayunmathur.communicate.data.whatsapp.WhatsAppServiceData
import com.vayunmathur.communicate.data.whatsapp.call.WhatsAppCallManager
import com.vayunmathur.communicate.data.whatsapp.call.WhatsAppCallState
import com.vayunmathur.communicate.data.whatsapp.createGroup
import com.vayunmathur.communicate.data.whatsapp.placeCall
import com.vayunmathur.communicate.data.whatsapp.sendContact
import com.vayunmathur.communicate.data.whatsapp.sendEdit
import com.vayunmathur.communicate.data.whatsapp.sendMedia
import com.vayunmathur.communicate.data.whatsapp.sendPollCreation
import com.vayunmathur.communicate.data.whatsapp.sendPollVote
import com.vayunmathur.communicate.data.whatsapp.sendReaction
import com.vayunmathur.communicate.data.whatsapp.sendReadReceipt
import com.vayunmathur.communicate.data.whatsapp.sendRevoke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

private const val MS_PER_SECOND = 1000L

/**
 * CommunicateRepository WhatsApp primary line (split from CommunicateRepository.kt for file length).
 *
 * Threads/messages read from the local Room cache (populated by WhatsAppEventProcessor);
 * rich actions delegate to WhatsAppClient. Extension functions on [CommunicateRepository];
 * behavior identical, call sites unchanged.
 */

// ------------------------------------------------------------------
// WhatsApp primary line: threads/messages read from the local Room cache
// (populated by WhatsAppEventProcessor), rich actions delegate to WhatsAppClient.
// ------------------------------------------------------------------

internal suspend fun CommunicateRepository.loadWhatsAppThreads(context: Context): List<SmsThread> {
    if (!com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) return emptyList()
    if (!WhatsAppLineSession.get(context).isSignedIn()) return emptyList()
    return runCatching {
        val db = WhatsAppDatabase.getDatabase(context)
        db.cachedMessageDao().getLatestPerConversation().map { m ->
            val sd = WhatsAppServiceData.parse(m.serviceData)
            val jid = m.conversationJid
            val conv = db.conversationDao().getConversation(jid)
            val isGroup = conv?.isGroup ?: sd?.isGroup ?: jid.endsWith("@g.us")
            val unread = conv?.unreadCount ?: 0
            val participants = parseParticipantsCsv(conv?.participants)
            val groupTitle = conv?.name?.takeIf { it.isNotBlank() }
            SmsThread(
                threadId = stableThreadId(jid),
                address = jidToDisplayAddress(jid),
                displayName = if (isGroup) (groupTitle ?: whatsAppDisplayName(context, jid, sd))
                    else whatsAppDisplayName(context, jid, sd),
                snippet = if (m.isRevoked) "This message was deleted" else m.body,
                timestampMillis = m.timestamp,
                unreadCount = unread,
                line = CommunicateLine.WhatsApp,
                remoteId = jid,
                isGroup = isGroup,
                avatarUrl = null,
                participants = participants,
                groupTitle = groupTitle,
            )
        }
    }.getOrDefault(emptyList())
}

/** Parse the conversation's stored participants column (JSON array of names) into a list. */
internal fun CommunicateRepository.parseParticipantsCsv(stored: String?): List<String> {
    if (stored.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = org.json.JSONArray(stored)
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrElse {
        // Legacy/plain CSV fallback.
        stored.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
}

internal suspend fun CommunicateRepository.loadWhatsAppMessages(context: Context, jid: String): List<SmsMessage> =
    runCatching {
        val db = WhatsAppDatabase.getDatabase(context)
        db.cachedMessageDao().getForConversationBefore(jid).asReversed().map { m ->
            SmsMessage(
                id = (m.messageId.hashCode().toLong() and 0xFFFFFFFFL),
                threadId = stableThreadId(jid),
                address = jidToDisplayAddress(jid),
                body = if (m.isRevoked) "" else m.body,
                timestampMillis = m.timestamp,
                outgoing = m.outgoing,
                read = true,
                line = CommunicateLine.WhatsApp,
                remoteId = m.messageId,
                serviceData = m.serviceData,
                senderAddress = m.senderJid.takeIf { it.isNotBlank() && !m.outgoing },
                status = m.status.let { s ->
                    com.vayunmathur.communicate.data.MessageStatus.entries.getOrElse(s) {
                        com.vayunmathur.communicate.data.MessageStatus.None
                    }
                },
            )
        }
    }.getOrDefault(emptyList())

// -- WhatsApp rich actions (delegate to the client) --

suspend fun CommunicateRepository.sendWhatsAppReaction(
    jid: String,
    messageId: String,
    emoji: String,
    targetFromMe: Boolean,
    targetSenderJid: String?,
): Boolean = withContext(Dispatchers.IO) {
    WhatsAppClient.sendReaction(jid, messageId, emoji, targetFromMe, targetSenderJid)
}

suspend fun CommunicateRepository.editWhatsAppMessage(jid: String, messageId: String, newBody: String): Boolean =
    withContext(Dispatchers.IO) { WhatsAppClient.sendEdit(jid, messageId, newBody) }

suspend fun CommunicateRepository.revokeWhatsAppMessage(
    jid: String,
    messageId: String,
    senderJid: String = ""): Boolean =
    withContext(Dispatchers.IO) { WhatsAppClient.sendRevoke(jid, messageId, senderJid) }

suspend fun CommunicateRepository.sendWhatsAppPollVote(
    jid: String,
    pollMessageId: String,
    pollCreatorJid: String,
    pollFromMe: Boolean,
    selectedOptionNames: List<String>,
): Boolean = withContext(Dispatchers.IO) {
    WhatsAppClient.sendPollVote(jid, pollMessageId, pollCreatorJid, pollFromMe, selectedOptionNames)
}

suspend fun CommunicateRepository.createWhatsAppPoll(
    jid: String,
    question: String,
    options: List<String>,
    selectableCount: Int = 0,
): String? = withContext(Dispatchers.IO) {
    WhatsAppClient.sendPollCreation(jid, question, options, selectableCount)
}

/**
 * Read each attachment and hand its bytes to [send], returning false if any failed.
 *
 * Reading happens here rather than in each line's client so the content-resolver work is done once, and so a
 * line that cannot read a uri fails visibly instead of silently sending nothing.
 */
internal suspend fun CommunicateRepository.sendAttachments(
    context: Context,
    attachments: List<CommunicateAttachment>,
    send: suspend (ByteArray, String, String?) -> Boolean,
): Boolean = withContext(Dispatchers.IO) {
    var allOk = attachments.isNotEmpty()
    for (attachment in attachments) {
        val bytes = try {
            context.contentResolver.openInputStream(attachment.contentUri.toUri())?.use { it.readBytes() }
        } catch (_: Throwable) {
            null
        }
        if (bytes == null || bytes.isEmpty()) {
            allOk = false
            continue
        }
        val ok = try {
            send(bytes, attachment.mimeType, attachment.fileName)
        } catch (_: Throwable) {
            false
        }
        if (!ok) allOk = false
    }
    allOk
}

suspend fun CommunicateRepository.sendWhatsAppMedia(
    jid: String,
    bytes: ByteArray,
    mimeType: String,
    fileName: String?): Boolean =
    withContext(Dispatchers.IO) { WhatsAppClient.sendMedia(jid, bytes, mimeType, fileName) }

/** True only when the WhatsApp primary client is logged in (needed for send/group ops). */
fun CommunicateRepository.isWhatsAppConnected(): Boolean = WhatsAppClient.isConnected()

// -- WhatsApp MEX / GraphQL (dev-only scaffolding, gated on WhatsAppFeature.enabled) --
//
// Thin pass-throughs to the xwa2_* operation catalog so the new MEX capabilities are
// callable/integrable without new UI. Every entry is gated behind WhatsAppFeature.enabled
// (stripped from release by R8) and runs on Dispatchers.IO. Each returns a MexResult; when the
// feature is off or an op's persisted doc_id isn't captured yet, callers get a typed transport
// failure rather than a crash.

private val CommunicateRepository.mexDisabled: com.vayunmathur.communicate.data.whatsapp.mex.MexResult
    get() = com.vayunmathur.communicate.data.whatsapp.mex.MexResult.transport("disabled")

/** MEX group metadata read (`xwa2_group_query_by_id`). */
suspend fun CommunicateRepository.whatsAppGroupInfo(
    context: Context,
    groupJid: String,
): com.vayunmathur.communicate.data.whatsapp.mex.MexResult = withContext(Dispatchers.IO) {
    if (!com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) return@withContext mexDisabled
    com.vayunmathur.communicate.data.whatsapp.mex.WhatsAppMexOps.groupQueryById(context, groupJid)
}

/** MEX contact discovery (`xwa2_contact_discovery`) for raw phone numbers. */
suspend fun CommunicateRepository.whatsAppContactDiscovery(
    context: Context,
    rawPhoneNumbers: List<String>,
    discoveryContext: String = "SEARCH",
): com.vayunmathur.communicate.data.whatsapp.mex.MexResult = withContext(Dispatchers.IO) {
    if (!com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) return@withContext mexDisabled
    com.vayunmathur.communicate.data.whatsapp.mex.WhatsAppMexOps.contactDiscovery(
        context,
        rawPhoneNumbers,
        discoveryContext)
}

/** MEX username read (`xwa2_username_get`). */
suspend fun CommunicateRepository.whatsAppUsernameGet(
    context: Context,
): com.vayunmathur.communicate.data.whatsapp.mex.MexResult = withContext(Dispatchers.IO) {
    if (!com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) return@withContext mexDisabled
    com.vayunmathur.communicate.data.whatsapp.mex.WhatsAppMexOps.usernameGet(context)
}

/** MEX username claim (`xwa2_username_set`). */
suspend fun CommunicateRepository.whatsAppUsernameSet(
    context: Context,
    username: String,
    pin: String? = null,
    sessionId: String? = null,
): com.vayunmathur.communicate.data.whatsapp.mex.MexResult = withContext(Dispatchers.IO) {
    if (!com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) return@withContext mexDisabled
    com.vayunmathur.communicate.data.whatsapp.mex.WhatsAppMexOps.usernameSet(
        context,
        username,
        pin,
        sessionId = sessionId)
}

/** MEX blocklist read (`xwa2_blocklist_get`). */
suspend fun CommunicateRepository.whatsAppBlocklistGet(
    context: Context,
    dhash: String? = null,
): com.vayunmathur.communicate.data.whatsapp.mex.MexResult = withContext(Dispatchers.IO) {
    if (!com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) return@withContext mexDisabled
    com.vayunmathur.communicate.data.whatsapp.mex.WhatsAppMexOps.blocklistGet(context, dhash)
}

/** MEX presence read (`xwa2_presence_data_platform_get_online_or_last_status`). */
suspend fun CommunicateRepository.whatsAppPresence(
    context: Context,
    lidJids: List<String>,
    lastActiveFilter: String? = null,
): com.vayunmathur.communicate.data.whatsapp.mex.MexResult = withContext(Dispatchers.IO) {
    if (!com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) return@withContext mexDisabled
    com.vayunmathur.communicate.data.whatsapp.mex.WhatsAppMexOps.getOnlineOrLastStatus(
        context,
        lidJids,
        lastActiveFilter)
}

/**
 * Refresh a peer's presence for an open thread and emit a [WhatsAppEvent.PresenceUpdate]
 * (Phase F 1e enrichment). Dev-gated + best-effort.
 */
suspend fun CommunicateRepository.whatsAppRefreshPresence(conversationId: String) = withContext(Dispatchers.IO) {
    WhatsAppClient.refreshPresence(conversationId)
}

/** MEX Signal-prekey publish (`xwa2_set_messaging_keys`); mints + persists one-time prekeys. */
suspend fun CommunicateRepository.whatsAppSetMessagingKeys(
    context: Context,
): com.vayunmathur.communicate.data.whatsapp.mex.MexResult = withContext(Dispatchers.IO) {
    if (!com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature.enabled) return@withContext mexDisabled
    com.vayunmathur.communicate.data.whatsapp.mex.WhatsAppMexOps.setMessagingKeys(context)
}

/**
 * Sync the device address book to WhatsApp (contact discovery + primary full sync) and persist
 * the returned LID/phone mappings. Dev-gated; requires READ_CONTACTS. Returns the sync summary.
 */
suspend fun CommunicateRepository.whatsAppSyncContacts(
    context: Context,
): com.vayunmathur.communicate.data.whatsapp.WhatsAppContactSync.SyncResult = withContext(Dispatchers.IO) {
    com.vayunmathur.communicate.data.whatsapp.WhatsAppContactSync.sync(context)
}

// ---- WhatsApp calling (Phase D/E), dev-gated pass-throughs to the call manager ----
// See CommunicateRepositoryWhatsAppCalls.kt.

/** Observable call state for the WhatsApp calling UI. */
val CommunicateRepository.whatsAppCallState: StateFlow<WhatsAppCallState>
    get() = WhatsAppCallManager.state

suspend fun CommunicateRepository.sendWhatsAppReadReceipt(
    jid: String,
    lastMessageId: String?,
    lastTimestamp: Long,
    senderJid: String? = null,
): Boolean = withContext(Dispatchers.IO) {
    WhatsAppClient.sendReadReceipt(jid, lastMessageId, lastTimestamp, senderJid)
}

/**
 * Mark a WhatsApp conversation read: clear the local unread badge and send a `read` receipt for
 * the latest inbound message (blue ticks on the sender's side). [alreadyReadId] is the last
 * message we already receipted, so the foreground poll doesn't re-send. Returns the id now
 * marked read (or [alreadyReadId] when there is nothing newer).
 */
suspend fun CommunicateRepository.markWhatsAppRead(
    context: Context,
    remoteId: String?,
    address: String,
    alreadyReadId: String?,
): String? = withContext(Dispatchers.IO) {
    val jid = remoteId?.takeIf { it.isNotBlank() } ?: toWhatsAppJid(context, address)
    val db = WhatsAppDatabase.getDatabase(context)
    runCatching {
        db.conversationDao().getConversation(jid)?.let {
            if (it.unreadCount != 0) db.conversationDao().upsert(it.copy(unreadCount = 0))
        }
    }
    val lastInbound = runCatching {
        db.cachedMessageDao().getForConversation(jid).lastOrNull { !it.outgoing }
    }.getOrNull() ?: return@withContext alreadyReadId
    if (lastInbound.messageId == alreadyReadId) return@withContext alreadyReadId
    // For groups the receipt needs the participant; 1:1 goes to the chat JID itself.
    val sender = if (jid.endsWith("@g.us")) lastInbound.senderJid.takeIf { it.isNotBlank() } else null
    runCatching {
        WhatsAppClient.sendReadReceipt(jid, lastInbound.messageId, lastInbound.timestamp / MS_PER_SECOND, sender)
    }
    lastInbound.messageId
}

/** numeric local part of a JID (strips :device and .agent suffixes). */
private fun CommunicateRepository.jidLocalPart(jid: String): String =
    jid.substringBefore("@").substringBefore(":").substringBefore(".")

private fun CommunicateRepository.jidToDisplayAddress(jid: String): String {
    if (jid.endsWith("@g.us")) return jid
    val phone = jidLocalPart(jid)
    return if (phone.isNotEmpty() && phone.all { it.isDigit() }) "+$phone" else phone
}

private fun CommunicateRepository.whatsAppDisplayName(
    context: Context,
    jid: String,
    sd: WhatsAppServiceData?): String? {
    if (jid.endsWith("@g.us")) return sd?.senderName // group display name not cached in v1
    val phone = jidLocalPart(jid)
    return findContactName(context, "+$phone") ?: sd?.senderName
}

/**
 * Build a 1:1 WhatsApp JID from a phone number / address. WhatsApp JIDs require the FULL
 * international number (country code, no '+'), so a nationally-typed number like "2134774209"
 * must be normalized to "12134774209" — otherwise usync returns 0 devices and the message goes
 * nowhere. Uses libphonenumber with the SIM/locale region to infer the country code.
 */
internal fun CommunicateRepository.toWhatsAppJid(context: Context, address: String): String {
    if (address.contains("@")) return address
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
    val normalized = (e164 ?: address).filter { it.isDigit() }
    return "$normalized@s.whatsapp.net"
}

/** Insert an outgoing WhatsApp message into the local cache so it shows in our own thread. */
internal suspend fun CommunicateRepository.cacheOutgoingWhatsApp(
    context: Context,
    jid: String,
    body: String,
    messageId: String) {
    runCatching {
        val db = WhatsAppDatabase.getDatabase(context)
        val now = System.currentTimeMillis()
        db.cachedMessageDao().upsert(
            WhatsAppCachedMessage(
                messageId = messageId,
                conversationJid = jid,
                body = body,
                timestamp = now,
                outgoing = true,
                // Sent (1) = single grey tick until a delivery/read receipt advances it.
                status = 1,
            ),
        )
        val existing = db.conversationDao().getConversation(jid)
        db.conversationDao().upsert(
            (existing ?: WhatsAppConversation(chatJid = jid)).copy(lastMessageTimestamp = now),
        )
    }
}
