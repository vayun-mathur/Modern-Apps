package com.vayunmathur.communicate.data

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import androidx.core.net.toUri
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceClient
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceWebSender
import com.vayunmathur.communicate.data.googlevoice.buildSendSmsBody
import com.vayunmathur.communicate.data.signal.SignalClient
import com.vayunmathur.communicate.data.signal.placeCall
import com.vayunmathur.communicate.data.signal.placeGroupCall
import com.vayunmathur.communicate.data.signal.sendMedia
import com.vayunmathur.communicate.data.signal.sendMessage
import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient
import com.vayunmathur.communicate.data.whatsapp.sendMedia
import com.vayunmathur.communicate.data.whatsapp.sendMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MS_PER_SECOND = 1000L
// MMS provider literals (PduHeaders): M-Send.req type, TO address type, UTF-8 charset.
private const val MMS_MESSAGE_TYPE_SEND_REQ = 128
private const val MMS_ADDR_TYPE_TO = 151
private const val MMS_CHARSET_UTF8 = 106

suspend fun CommunicateRepository.loadSmsThreadsMerged(context: Context): List<SmsThread> {
    val sim = loadSmsThreads(context)
    val gv = loadGoogleVoiceThreads(context)
    val wa = loadWhatsAppThreads(context)
    val signal = loadSignalThreads(context)
    val rcs = loadRcsThreads(context)
    return (sim + gv + wa + signal + rcs).sortedByDescending { it.timestampMillis }
}

/** Route by line: SIM threads read the provider; GV threads hit `api2thread/get`; WA/Signal read Room. */
suspend fun CommunicateRepository.loadSmsMessagesMerged(context: Context, thread: SmsThread): List<SmsMessage> =
    when (thread.line) {
        CommunicateLine.Sim -> (loadSmsMessages(context, thread.threadId) +
            loadMmsMessages(context, thread.threadId))
            .sortedBy { it.timestampMillis }
        CommunicateLine.GoogleVoice -> {
            val remoteId = thread.remoteId ?: return emptyList()
            runCatching {
                GoogleVoiceClient.get(context).getThread(remoteId)
                    .map { it.toSmsMessage(thread.threadId, context) }
            }.getOrDefault(emptyList())
        }
        CommunicateLine.WhatsApp -> {
            // New conversations have no remoteId yet — derive the JID from the address so the
            // outgoing echo (cached under the same normalized JID) shows immediately.
            val jid = thread.remoteId?.takeIf { it.isNotBlank() } ?: toWhatsAppJid(context, thread.address)
            loadWhatsAppMessages(context, jid)
        }
        CommunicateLine.Signal -> {
            // New Signal conversations have no remoteId yet — derive recipient from address.
            val recipient = thread.remoteId?.takeIf { it.isNotBlank() } ?: toSignalRecipient(context, thread.address)
            loadSignalMessages(context, recipient)
        }
        CommunicateLine.Rcs -> {
            val recipient = thread.remoteId?.takeIf { it.isNotBlank() } ?: toRcsRecipient(context, thread.address)
            loadRcsMessages(context, recipient)
        }
    }

suspend fun CommunicateRepository.loadCallLogsMerged(context: Context): List<CommunicateCallLogEntry> {
    val device = loadCallLogs(context)
    val gv = loadGoogleVoiceCalls(context)
    return (device + gv).sortedByDescending { it.timestampMillis }
}

suspend fun CommunicateRepository.sendMessage(
    context: Context,
    choice: LineChoice,
    address: String,
    body: String,
    threadRemoteId: String? = null,
    attachments: List<CommunicateAttachment> = emptyList(),
    participants: List<String> = emptyList(),
): Boolean = with(CommunicateRepository) { when (choice) {
    is LineChoice.Sim -> withContext(Dispatchers.IO) {
        // Group (multi-recipient) or media messages go out as MMS so all replies land in one
        // thread; plain 1:1 text stays SMS.
        val recipients = participants.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(address) }
        val isGroup = recipients.size > 1
        if (attachments.isEmpty() && !isGroup) {
            sendSimSms(context, choice.subscriptionId, address, body)
        } else {
            sendSimMms(context, choice.subscriptionId, recipients, body, attachments)
        }
    }
    LineChoice.GoogleVoice -> sendGoogleVoiceMessage(context, address, body, threadRemoteId, attachments)
    LineChoice.WhatsApp -> withContext(Dispatchers.IO) {
        sendWhatsAppMessage(context, address, body, threadRemoteId, attachments)
    }
    LineChoice.Signal -> withContext(Dispatchers.IO) {
        sendSignalMessage(context, address, body, threadRemoteId, attachments)
    }
    LineChoice.Rcs -> withContext(Dispatchers.IO) {
        sendRcsWithSmsFallback(context, address, body, threadRemoteId, attachments, participants)
    }
} }

/** Google Voice send via bot-defense token + prepared API body. */
private suspend fun CommunicateRepository.sendGoogleVoiceMessage(
    context: Context,
    address: String,
    body: String,
    threadRemoteId: String?,
    attachments: List<CommunicateAttachment>,
): Boolean = runCatching {
    // The bot-defense token is minted invisibly in an offscreen WebView; the app then
    // builds and sends the sendsms API call itself using that token. For MMS, let the
    // real web composer upload media and build the media-bearing body, then replay it.
    val activity = context as? android.app.Activity ?: return@runCatching false
    val sendBody = if (attachments.isEmpty()) {
        val token = GoogleVoiceWebSender.mintToken(activity, address, body) ?: return@runCatching false
        com.vayunmathur.communicate.data.googlevoice.GoogleVoiceParser
            .buildSendSmsBody(address, body, threadRemoteId, botToken = token)
    } else {
        GoogleVoiceWebSender.mintPreparedBody(activity, address, body, attachments) ?: return@runCatching false
    }
    GoogleVoiceClient.get(context).sendPreparedSms(sendBody)
    true
}.getOrDefault(false)

/** WhatsApp send (text or media + caption), echoing to the local cache. */
private suspend fun CommunicateRepository.sendWhatsAppMessage(
    context: Context,
    address: String,
    body: String,
    threadRemoteId: String?,
    attachments: List<CommunicateAttachment>,
): Boolean = runCatching {
    // For WhatsApp the conversation is addressed by JID: use the thread's remoteId when
    // replying to an existing chat, else derive a 1:1 JID from the phone number.
    val jid = threadRemoteId ?: toWhatsAppJid(context, address)
    val sentId = with(WhatsAppClient) {
        if (attachments.isEmpty()) {
            sendMessage(jid, body)
        } else {
            // Attachments are sent here rather than left to a separate call: previously this branch
            // returned success while sending nothing, so picking a photo silently did nothing.
            val mediaOk = sendAttachments(context, attachments) { bytes, mime, name ->
                sendMedia(jid, bytes, mime, name)
            }
            val captionId = if (body.isNotBlank()) sendMessage(jid, body) else ""
            if (mediaOk) captionId else null
        }
    }
    // Echo the outgoing message into the local cache so it shows in our own thread
    // (a primary-only line gets no server echo of its own sends). Cache under the real
    // WA message id so delivery/read receipts can advance its status ticks.
    if (sentId != null && body.isNotBlank()) {
        cacheOutgoingWhatsApp(context, jid, body, sentId.ifBlank { "local-${java.util.UUID.randomUUID()}" })
    }
    sentId != null
}.getOrDefault(false)

/** Signal send (text or media + caption), echoing to the local cache. */
private suspend fun CommunicateRepository.sendSignalMessage(
    context: Context,
    address: String,
    body: String,
    threadRemoteId: String?,
    attachments: List<CommunicateAttachment>,
): Boolean {
    val signal = SignalClient.get(context)
    return runCatching {
        val recipient = threadRemoteId ?: toSignalRecipient(context, address)
        val sentId = with(signal) {
            if (attachments.isEmpty()) {
                if (body.isBlank()) null else this@with.sendMessage(recipient, body)
            } else {
                // As with WhatsApp: send the attachments here, rather than reporting success and
                // dropping them.
                val mediaOk = sendAttachments(context, attachments) { bytes, mime, name ->
                    sendMedia(recipient, bytes, mime, name) != null
                }
                val captionId = if (body.isNotBlank()) {
                    this@with.sendMessage(recipient, body)
                } else {
                    ""
                }
                if (mediaOk) captionId else null
            }
        }
        if (sentId != null && body.isNotBlank()) {
            cacheOutgoingSignal(context, recipient, body, sentId.ifBlank { "local-${java.util.UUID.randomUUID()}" })
        }
        sentId != null
    }.getOrDefault(false)
}

/** RCS send with SMS fallback when the contact/transport is not RCS-capable. */
private suspend fun CommunicateRepository.sendRcsWithSmsFallback(
    context: Context,
    address: String,
    body: String,
    threadRemoteId: String?,
    attachments: List<CommunicateAttachment>,
    participants: List<String>,
): Boolean {
    when (sendRcsMessage(context, address, body, threadRemoteId, attachments, participants)) {
        RcsSendResult.Sent -> return true
        RcsSendResult.Failed -> return false
        RcsSendResult.FallbackSms -> {
            // Not RCS-capable or transport unavailable: fall back to SMS on the
            // default subscription and tell the user (never silently downgrade).
            notifyRcsFallback(context)
            val subId = SimManager.activeSims(context).firstOrNull()?.subscriptionId
                ?: SimManager.defaultSmsSubscriptionId()
            val recipients = participants.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(address) }
            return if (recipients.size > 1 || attachments.isNotEmpty()) {
                sendSimMms(context, subId, recipients, body, attachments)
            } else {
                sendSimSms(context, subId, address, body)
            }
        }
    }
}

internal fun CommunicateRepository.sendSimSms(
    context: Context,
    subscriptionId: Int,
    address: String,
    body: String): Boolean {
    if (address.isBlank() || body.isBlank()) return false
    if (!context.hasPermission(Manifest.permission.SEND_SMS)) {
        openSmsComposer(context, address, body)
        return true
    }
    return runCatching {
        val base = context.getSystemService(android.telephony.SmsManager::class.java)
        val sms = if (subscriptionId >= 0) base.createForSubscriptionId(subscriptionId) else base
        val parts = sms.divideMessage(body)
        if (parts.size > 1) {
            sms.sendMultipartTextMessage(address, null, parts, null, null)
        } else {
            sms.sendTextMessage(address, null, body, null, null)
        }
        // Record in the provider Sent box so it shows in the thread (we're the default SMS app).
        runCatching {
            val values = android.content.ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                if (subscriptionId >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
            }
        context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
        }
        true
    }.getOrDefault(false)
}

internal fun CommunicateRepository.smsManagerFor(context: Context, subscriptionId: Int): android.telephony.SmsManager {
    val base = context.getSystemService(android.telephony.SmsManager::class.java)
    return if (subscriptionId >= 0) base.createForSubscriptionId(subscriptionId) else base
}

internal fun CommunicateRepository.sendSimMms(
    context: Context,
    subscriptionId: Int,
    recipients: List<String>,
    body: String,
    attachments: List<CommunicateAttachment>,
): Boolean {
    if (recipients.isEmpty()) return false
    if (!context.hasPermission(Manifest.permission.SEND_SMS)) return false
    return runCatching {
        val txnId = "T${System.currentTimeMillis()}"
        val mediaParts = attachments.mapNotNull { att ->
            runCatching {
                val bytes = context.contentResolver.openInputStream(att.contentUri.toUri())
                    ?.use { it.readBytes() } ?: return@mapNotNull null
                com.vayunmathur.communicate.telephony.MmsPdu.Part(att.mimeType, bytes)
            }.getOrNull()
        }
        val pdu = com.vayunmathur.communicate.telephony.MmsPdu.composeSendReq(
            txnId, recipients, body.takeIf { it.isNotBlank() }, mediaParts,
        )
        val dir = java.io.File(context.cacheDir, "mms").apply { mkdirs() }
        val file = java.io.File(dir, "$txnId.pdu").apply { writeBytes(pdu) }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.mmsfileprovider", file,
        )
        smsManagerFor(context, subscriptionId).sendMultimediaMessage(context, uri, null, null, null)
        persistOutgoingMms(context, subscriptionId, recipients, body)
        true
    }.getOrDefault(false)
}

internal fun CommunicateRepository.persistOutgoingMms(
    context: Context,
    subscriptionId: Int,
    recipients: List<String>,
    body: String,
) {
    runCatching {
        val threadId = getOrCreateSmsGroupThreadId(context, recipients)
        val values = android.content.ContentValues().apply {
            if (threadId != null) put(Telephony.Mms.THREAD_ID, threadId)
            put(Telephony.Mms.DATE, System.currentTimeMillis() / MS_PER_SECOND)
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_SENT)
            put(Telephony.Mms.READ, 1)
            put(Telephony.Mms.SEEN, 1)
            put(Telephony.Mms.MESSAGE_TYPE, MMS_MESSAGE_TYPE_SEND_REQ) // M-Send.req
            if (subscriptionId >= 0) put(Telephony.Mms.SUBSCRIPTION_ID, subscriptionId)
        }
        val mmsUri = context.contentResolver.insert(Telephony.Mms.CONTENT_URI, values) ?: return
        val mmsId = mmsUri.lastPathSegment ?: return
        // Text part.
        if (body.isNotBlank()) {
            val partValues = android.content.ContentValues().apply {
                put("mid", mmsId)
                put("ct", "text/plain")
                put("text", body)
            }
            context.contentResolver.insert("content://mms/$mmsId/part".toUri(), partValues)
        }
        // Address rows: TO (151) per recipient.
        for (r in recipients) {
            val addrValues = android.content.ContentValues().apply {
                put("address", r)
                put("type", MMS_ADDR_TYPE_TO)
                put("charset", MMS_CHARSET_UTF8)
            }
            context.contentResolver.insert("content://mms/$mmsId/addr".toUri(), addrValues)
        }
    }
}

fun CommunicateRepository.stableThreadId(remoteId: String): Long =
    (remoteId.hashCode().toLong() and HASH_MASK) or THREAD_ID_FLAG

private const val HASH_MASK = 0xFFFFFFFFL
private const val THREAD_ID_FLAG = 0x1_0000_0000L
