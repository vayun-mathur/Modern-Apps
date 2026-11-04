package com.vayunmathur.communicate.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.vayunmathur.library.ui.ExternalIntents

fun CommunicateRepository.loadContacts(context: Context): List<CommunicateContact> {
    if (!context.hasPermission(Manifest.permission.READ_CONTACTS)) return emptyList()

    val projection = arrayOf(
        ContactsContract.CommonDataKinds.Phone._ID,
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
        ContactsContract.CommonDataKinds.Phone.NUMBER,
        ContactsContract.CommonDataKinds.Phone.TYPE,
        ContactsContract.CommonDataKinds.Phone.LABEL,
    )
    return runCatching {
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC",
        )?.use { cursor ->
            buildList {
                val id = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone._ID)
                val name = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                val number = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val type = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
                val label = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LABEL)
                val seenNumbers = mutableSetOf<String>()

                while (cursor.moveToNext()) {
                    val rawNumber = cursor.getString(number).orEmpty().trim()
                    if (rawNumber.isNotEmpty()) {
                        val normalized = rawNumber.filter { it.isDigit() || it == '+' }
                        if (seenNumbers.add(normalized.ifEmpty { rawNumber })) {
                            add(
                                CommunicateContact(
                                    id = cursor.getLong(id),
                                    name = cursor.getString(name).orEmpty().ifBlank { rawNumber },
                                    phoneNumber = rawNumber,
                                    label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                                        context.resources,
                                        cursor.getInt(type),
                                        cursor.getString(label),
                                    ).toString(),
                                )
                            )
                        }
                    }
                }
            }
        }.orEmpty()
    }.getOrDefault(emptyList())
}

fun CommunicateRepository.loadCallLogs(context: Context): List<CommunicateCallLogEntry> {
    if (!context.hasPermission(Manifest.permission.READ_CALL_LOG)) return emptyList()

    val projection = arrayOf(
        CallLog.Calls._ID,
        CallLog.Calls.CACHED_NAME,
        CallLog.Calls.NUMBER,
        CallLog.Calls.TYPE,
        CallLog.Calls.DATE,
        CallLog.Calls.DURATION,
        CallLog.Calls.PHONE_ACCOUNT_ID,
    )
    return runCatching {
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            null,
            null,
            "${CallLog.Calls.DATE} DESC",
        )?.use { cursor ->
            buildList {
                val id = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
                val name = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val number = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val type = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val date = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val duration = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val account = cursor.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)
                val activeSubs = SimManager.activeSims(context).map { it.subscriptionId }.toSet()

                while (cursor.moveToNext()) {
                    val phoneNumber = cursor.getString(number).orEmpty().ifBlank { "Unknown" }
                    // PHONE_ACCOUNT_ID is the SIM's subscription id on most devices; keep it
                    // only when it maps to an active SIM so we can label the row by SIM.
                    val subId = account.takeIf { it >= 0 }
                        ?.let { cursor.getString(it) }
                        ?.toIntOrNull()
                        ?.takeIf { it in activeSubs }
                    add(
                        CommunicateCallLogEntry(
                            id = cursor.getLong(id),
                            displayName = cursor.getString(name)?.takeIf { it.isNotBlank() },
                            phoneNumber = phoneNumber,
                            type = cursor.getInt(type).toCommunicateCallType(),
                            timestampMillis = cursor.getLong(date),
                            durationSeconds = cursor.getLong(duration),
                            subscriptionId = subId,
                        )
                    )
                }
            }
        }.orEmpty()
    }.getOrDefault(emptyList())
}

fun CommunicateRepository.loadSmsThreads(context: Context): List<SmsThread> {
    if (!context.hasPermission(Manifest.permission.READ_SMS)) return emptyList()
    val recipientsByThread = loadThreadRecipients(context)
    // Merge SMS + MMS messages so group MMS threads (which have no SMS rows) still appear.
    val byThread = (loadSmsMessages(context, threadId = null) + loadMmsMessages(context, threadId = null))
        .groupBy { it.threadId }
    return byThread.values.mapNotNull { messages ->
        val newest = messages.maxByOrNull { it.timestampMillis } ?: return@mapNotNull null
        val threadId = newest.threadId
        val participants = recipientsByThread[threadId]
            ?: messages.mapNotNull { it.senderAddress }.distinct().ifEmpty { listOf(newest.address) }
        val isGroup = participants.size > 1
        val primary = participants.firstOrNull()?.takeIf { it.isNotBlank() } ?: newest.address
        val displayName = if (isGroup) {
            participants.joinToString(", ") { findContactName(context, it) ?: it }
        } else {
            findContactName(context, primary)
        }
        SmsThread(
            threadId = threadId,
            address = primary,
            displayName = displayName,
            snippet = newest.body,
            timestampMillis = newest.timestampMillis,
            unreadCount = messages.count { !it.outgoing && !it.read },
            subscriptionId = newest.subscriptionId,
            isGroup = isGroup,
            participants = if (isGroup) participants else emptyList(),
        )
    }.sortedByDescending { it.timestampMillis }
}

suspend fun CommunicateRepository.markSimThreadRead(
    context: Context,
    threadId: Long): Boolean = withContext(Dispatchers.IO) {
    val values = android.content.ContentValues().apply {
        put(Telephony.Sms.READ, 1)
        put(Telephony.Sms.SEEN, 1)
    }
    // SMS and MMS are separate tables sharing the thread id; an unread thread may be either.
    listOf(Telephony.Sms.CONTENT_URI, Telephony.Mms.CONTENT_URI).sumOf { uri ->
        runCatching {
            context.contentResolver.update(
                uri, values,
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
                arrayOf(threadId.toString()),
            )
        }.getOrDefault(0)
    } > 0
}

internal fun CommunicateRepository.loadThreadRecipients(context: Context): Map<Long, List<String>> = runCatching {
    // recipient_ids is a space-separated list of ids into the canonical-addresses table.
    val canonical = HashMap<String, String>()
    context.contentResolver.query(
        Uri.parse("content://mms-sms/canonical-addresses"),
        arrayOf("_id", "address"),
        null, null, null,
    )?.use { c ->
        val idIdx = c.getColumnIndexOrThrow("_id")
        val addrIdx = c.getColumnIndexOrThrow("address")
        while (c.moveToNext()) canonical[c.getString(idIdx)] = c.getString(addrIdx).orEmpty()
    }
    val result = HashMap<Long, List<String>>()
    context.contentResolver.query(
        Telephony.Threads.CONTENT_URI.buildUpon().appendQueryParameter("simple", "true").build(),
        arrayOf(Telephony.Threads._ID, Telephony.Threads.RECIPIENT_IDS),
        null, null, null,
    )?.use { c ->
        val idIdx = c.getColumnIndexOrThrow(Telephony.Threads._ID)
        val ridIdx = c.getColumnIndexOrThrow(Telephony.Threads.RECIPIENT_IDS)
        while (c.moveToNext()) {
            val tid = c.getLong(idIdx)
            val addrs = c.getString(ridIdx).orEmpty().split(" ")
                .mapNotNull { rid -> canonical[rid]?.takeIf { it.isNotBlank() } }
            if (addrs.isNotEmpty()) result[tid] = addrs
        }
    }
    result
}.getOrDefault(emptyMap())

fun CommunicateRepository.loadMmsMessages(context: Context, threadId: Long?): List<SmsMessage> {
    if (!context.hasPermission(Manifest.permission.READ_SMS)) return emptyList()
    val selection = threadId?.let { "${Telephony.Mms.THREAD_ID} = ?" }
    val args = threadId?.let { arrayOf(it.toString()) }
    return runCatching {
        context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(
                Telephony.Mms._ID,
                Telephony.Mms.THREAD_ID,
                Telephony.Mms.DATE,
                Telephony.Mms.MESSAGE_BOX,
                Telephony.Mms.READ,
                Telephony.Mms.SUBSCRIPTION_ID,
            ),
            selection, args,
            "${Telephony.Mms.DATE} ASC",
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(Telephony.Mms._ID)
            val threadIdx = c.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID)
            val dateIdx = c.getColumnIndexOrThrow(Telephony.Mms.DATE)
            val boxIdx = c.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX)
            val readIdx = c.getColumnIndexOrThrow(Telephony.Mms.READ)
            val subIdx = c.getColumnIndex(Telephony.Mms.SUBSCRIPTION_ID)
            buildList {
                while (c.moveToNext()) {
                    val mmsId = c.getLong(idIdx)
                    val box = c.getInt(boxIdx)
                    val outgoing = box == Telephony.Mms.MESSAGE_BOX_SENT ||
                        box == Telephony.Mms.MESSAGE_BOX_OUTBOX
                    val (text, attachments) = loadMmsParts(context, mmsId)
                    val sender = if (!outgoing) loadMmsSender(context, mmsId) else null
                    add(
                        SmsMessage(
                            // Keep MMS ids clear of SMS ids (both are provider row ids).
                            id = mmsId or 0x2_0000_0000L,
                            threadId = c.getLong(threadIdx),
                            address = sender.orEmpty(),
                            body = text,
                            // MMS DATE is in seconds, unlike SMS (ms).
                            timestampMillis = c.getLong(dateIdx) * MS_PER_SECOND,
                            outgoing = outgoing,
                            read = c.getInt(readIdx) != 0,
                            attachments = attachments,
                            subscriptionId = subIdx.takeIf { it >= 0 }?.let { c.getInt(it) }?.takeIf { it >= 0 },
                            senderAddress = sender,
                            status = if (outgoing) MessageStatus.Sent else MessageStatus.None,
                        )
                    )
                }
            }
        }.orEmpty()
    }.getOrDefault(emptyList())
}

internal fun CommunicateRepository.loadMmsParts(
    context: Context,
    mmsId: Long): Pair<String, List<CommunicateAttachment>> = runCatching {
    val text = StringBuilder()
    val attachments = ArrayList<CommunicateAttachment>()
    runCatching {
        context.contentResolver.query(
            Uri.parse("content://mms/part"),
            arrayOf("_id", "ct", "text", "_data"),
            "mid = ?", arrayOf(mmsId.toString()), null,
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow("_id")
            val ctIdx = c.getColumnIndexOrThrow("ct")
            val textIdx = c.getColumnIndexOrThrow("text")
            val dataIdx = c.getColumnIndexOrThrow("_data")
            while (c.moveToNext()) {
                val ct = c.getString(ctIdx).orEmpty()
                when {
                    ct == "text/plain" -> {
                        val hasData = c.getString(dataIdx) != null
                        if (hasData) {
                            // Body stored as a file part; read via the part content uri.
                            text.append(readMmsTextPart(context, c.getLong(idIdx)))
                        } else {
                            text.append(c.getString(textIdx).orEmpty())
                        }
                    }
                    ct.startsWith("image/") || ct.startsWith("video/") -> {
                        attachments.add(
                            CommunicateAttachment(
                                contentUri = "content://mms/part/${c.getLong(idIdx)}",
                                mimeType = ct,
                            ),
                        )
                    }
                    // application/smil and others are ignored.
                }
            }
        }
    }
    return text.toString() to attachments
}.getOrDefault("" to emptyList())

internal fun CommunicateRepository.readMmsTextPart(context: Context, partId: Long): String = runCatching {
    context.contentResolver.openInputStream(Uri.parse("content://mms/part/$partId"))?.use {
        it.readBytes().toString(Charsets.UTF_8)
    }.orEmpty()
}.getOrDefault("")

internal fun CommunicateRepository.loadMmsSender(context: Context, mmsId: Long): String? = runCatching {
    context.contentResolver.query(
        Uri.parse("content://mms/$mmsId/addr"),
        arrayOf("address", "type"),
        "type = 137", null, null,
    )?.use { c ->
        val addrIdx = c.getColumnIndexOrThrow("address")
        if (c.moveToFirst()) c.getString(addrIdx)?.takeIf { it.isNotBlank() && it != "insert-address-token" } else null
    }
}.getOrNull()

fun CommunicateRepository.loadSmsMessages(context: Context, threadId: Long?): List<SmsMessage> {
    if (!context.hasPermission(Manifest.permission.READ_SMS)) return emptyList()

    val projection = arrayOf(
        Telephony.Sms._ID,
        Telephony.Sms.THREAD_ID,
        Telephony.Sms.ADDRESS,
        Telephony.Sms.BODY,
        Telephony.Sms.DATE,
        Telephony.Sms.TYPE,
        Telephony.Sms.READ,
        Telephony.Sms.STATUS,
        Telephony.Sms.SUBSCRIPTION_ID,
    )
    val selection = threadId?.let { "${Telephony.Sms.THREAD_ID} = ?" }
    val args = threadId?.let { arrayOf(it.toString()) }
    return runCatching {
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            selection,
            args,
            "${Telephony.Sms.DATE} ASC",
        )?.use { cursor ->
            buildList {
                val id = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val thread = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                val address = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val body = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val date = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val type = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                val read = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
                val status = cursor.getColumnIndex(Telephony.Sms.STATUS)
                val sub = cursor.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)

                while (cursor.moveToNext()) {
                    val msgType = cursor.getInt(type)
                    val outgoing = msgType == Telephony.Sms.MESSAGE_TYPE_SENT ||
                        msgType == Telephony.Sms.MESSAGE_TYPE_OUTBOX
                    val statusVal = status.takeIf { it >= 0 }?.let { cursor.getInt(it) } ?: -1
                    add(
                        SmsMessage(
                            id = cursor.getLong(id),
                            threadId = cursor.getLong(thread),
                            address = cursor.getString(address).orEmpty(),
                            body = cursor.getString(body).orEmpty(),
                            timestampMillis = cursor.getLong(date),
                            outgoing = outgoing,
                            read = cursor.getInt(read) != 0,
                            subscriptionId = sub.takeIf { it >= 0 }?.let { cursor.getInt(it) }?.takeIf { it >= 0 },
                            status = smsStatus(msgType, outgoing, statusVal),
                        )
                    )
                }
            }
        }.orEmpty()
    }.getOrDefault(emptyList())
}

internal fun CommunicateRepository.smsStatus(msgType: Int, outgoing: Boolean, status: Int): MessageStatus = when {
    msgType == Telephony.Sms.MESSAGE_TYPE_FAILED -> MessageStatus.Failed
    !outgoing -> MessageStatus.None
    status == Telephony.Sms.STATUS_COMPLETE -> MessageStatus.Delivered
    status == Telephony.Sms.STATUS_FAILED -> MessageStatus.Failed
    else -> MessageStatus.Sent // STATUS_PENDING / STATUS_NONE → single tick
}

private const val MS_PER_SECOND = 1000L
private const val MAX_DASHES = 4

fun CommunicateRepository.isUnknownContact(context: Context, number: String): Boolean {
    if (number.isBlank()) return false
    // Service ids (ACI/PNI UUIDs, JIDs) are not dialable and cannot be saved as a phone number.
    if (!number.any { it.isDigit() }) return false
    if (number.contains('@') || number.count { it == '-' } >= MAX_DASHES) return false
    if (!context.hasPermission(Manifest.permission.READ_CONTACTS)) return false
    return findContactName(context, number) == null
}

fun CommunicateRepository.findContactName(context: Context, number: String): String? {
    if (!context.hasPermission(Manifest.permission.READ_CONTACTS) || number.isBlank()) return null
    return runCatching {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number),
        )
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME_PRIMARY),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()
}

internal fun CommunicateRepository.phoneAccountHandleForSub(
    context: Context,
    subscriptionId: Int): android.telecom.PhoneAccountHandle? {
    if (subscriptionId < 0) return null
    if (!context.hasPermission(Manifest.permission.READ_PHONE_STATE)) return null
    val tm = context.getSystemService(TelecomManager::class.java) ?: return null
    return runCatching {
        tm.callCapablePhoneAccounts.firstOrNull { it.id == subscriptionId.toString() }
    }.getOrNull()
}

fun CommunicateRepository.openSmsComposer(context: Context, number: String? = null, body: String? = null) {
    val uri = if (number.isNullOrBlank()) "smsto:".toUri() else "smsto:$number".toUri()
    val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
        if (!body.isNullOrBlank()) putExtra("sms_body", body)
    }
    ExternalIntents.launch(context, intent)
}

/**
 * The carrier voicemail pilot for [choice]'s line, or null when it cannot be
 * read — long-press 1 target (issue #899).
 *
 * SIM lines resolve through their own subscription's [TelephonyManager] so a
 * dual-SIM device dials the right mailbox; devices that can't hand out a
 * per-subscription manager (or builds hiding the API) fall back to the
 * default manager. Google Voice publishes no pilot here and returns null.
 * Needs READ_PHONE_STATE.
 */
fun CommunicateRepository.voicemailNumber(context: Context, choice: LineChoice?): String? {
    val subId = (choice as? LineChoice.Sim)?.subscriptionId
    if (subId == null && choice is LineChoice.GoogleVoice) return null
    if (!context.hasPermission(Manifest.permission.READ_PHONE_STATE)) return null
    val base = runCatching { context.getSystemService(TelephonyManager::class.java) }.getOrNull()
        ?: return null
    val scoped = if (subId != null && subId >= 0) {
        runCatching { base.createForSubscriptionId(subId) }.getOrNull() ?: base
    } else {
        base
    }
    return runCatching { scoped.voiceMailNumber }.getOrNull()?.takeIf { it.isNotBlank() }
}

fun CommunicateRepository.getOrCreateSmsThreadId(context: Context, address: String): Long? = runCatching {
    Telephony.Threads.getOrCreateThreadId(context, address)
}.getOrNull()

fun CommunicateRepository.getOrCreateSmsGroupThreadId(context: Context, recipients: List<String>): Long? = runCatching {
    val set = recipients.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    if (set.isEmpty()) return null
    Telephony.Threads.getOrCreateThreadId(context, set)
}.getOrNull()
