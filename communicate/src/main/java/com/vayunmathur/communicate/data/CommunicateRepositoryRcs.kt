package com.vayunmathur.communicate.data

import android.content.Context
import android.telephony.TelephonyManager
import com.vayunmathur.communicate.data.rcs.RcsCachedMessage
import com.vayunmathur.communicate.data.rcs.RcsCapabilityExchange
import com.vayunmathur.communicate.data.rcs.RcsConversation
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.RcsFeature
import com.vayunmathur.communicate.data.rcs.RcsFileTransfer
import com.vayunmathur.communicate.data.rcs.RcsFileTransferHttp
import com.vayunmathur.communicate.data.rcs.RcsMsrp
import com.vayunmathur.communicate.data.rcs.RcsSessionManager
import com.vayunmathur.communicate.data.rcs.ImdnDisposition
import com.vayunmathur.communicate.data.rcs.RcsOutbox
import com.vayunmathur.communicate.data.rcs.RcsSipTransport
import com.vayunmathur.communicate.data.rcs.buildEditBody
import com.vayunmathur.communicate.data.rcs.buildGeopushBody
import com.vayunmathur.communicate.data.rcs.buildRevokeBody
import com.vayunmathur.communicate.data.rcs.cacheInboundRcsWithImdn
import com.vayunmathur.communicate.data.rcs.chunkLargeMessage
import com.vayunmathur.communicate.data.rcs.sendRcsDisplayReports
import com.vayunmathur.communicate.data.rcs.e2e.groupIdFor
import com.vayunmathur.communicate.data.rcs.e2e.localE164
import com.vayunmathur.library.util.AppMessages
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CommunicateRepository RCS single-registration line (mirrors
 * CommunicateRepositorySignal.kt).
 *
 * Threads/messages read from the local Room cache (populated by
 * RcsSyncService); sends go through [RcsSipTransport] with SMS fallback when
 * the contact is not capable or the transport is unavailable. Extension
 * functions on [CommunicateRepository]; behavior identical, call sites
 * unchanged.
 */

/** Result of an RCS send attempt, so the caller can fall back to SMS. */
enum class RcsSendResult {
    Sent,
    FallbackSms,
    Failed,
}

/** Fire-and-forget scope for background session establishment (never blocks sends). */
private val sendScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

internal suspend fun CommunicateRepository.loadRcsThreads(context: Context): List<SmsThread> {
    if (!RcsFeature.enabled) return emptyList()
    return runCatching {
        val db = RcsDatabase.getDatabase(context)
        db.cachedMessageDao().getLatestPerConversation().map { m ->
            val cid = m.conversationId
            val conv = db.conversationDao().getConversation(cid)
            val isGroup = conv?.isGroup ?: false
            val unread = conv?.unreadCount ?: 0
            val participants = parseParticipantsCsv(conv?.participants)
            val groupTitle = conv?.name?.takeIf { it.isNotBlank() }
            SmsThread(
                threadId = stableThreadId(cid),
                address = if (isGroup) cid else cid,
                displayName = if (isGroup) {
                    groupTitle ?: rcsDisplayName(context, cid)
                } else {
                    rcsDisplayName(context, cid)
                },
                snippet = m.body,
                timestampMillis = m.timestamp,
                unreadCount = unread,
                line = CommunicateLine.Rcs,
                remoteId = cid,
                isGroup = isGroup,
                avatarUrl = null,
                participants = participants,
                groupTitle = groupTitle,
            )
        }
    }.getOrDefault(emptyList())
}

internal suspend fun CommunicateRepository.loadRcsMessages(context: Context, conversationId: String): List<SmsMessage> =
    runCatching {
        val db = RcsDatabase.getDatabase(context)
        db.cachedMessageDao().getForConversation(conversationId).map { m ->
            SmsMessage(
                id = (m.messageId.hashCode().toLong() and 0xFFFFFFFFL),
                threadId = stableThreadId(conversationId),
                address = conversationId,
                body = m.body,
                timestampMillis = m.timestamp,
                outgoing = m.outgoing,
                read = true,
                line = CommunicateLine.Rcs,
                remoteId = m.messageId,
                attachments = if (m.ftUrl != null) {
                    listOf(CommunicateAttachment(m.ftUrl, m.ftMime ?: "application/octet-stream"))
                } else {
                    emptyList()
                },
                senderAddress = m.senderId.takeIf { it.isNotBlank() && !m.outgoing },
                status = m.status.let { s ->
                    MessageStatus.entries.getOrElse(s) { MessageStatus.None }
                },
            )
        }
    }.getOrDefault(emptyList())

/**
 * Send on the RCS line. Returns [RcsSendResult.FallbackSms] when the contact
 * is not RCS-capable or the transport is unavailable, so the caller can
 * re-send via SMS. Never throws.
 */
suspend fun CommunicateRepository.sendRcsMessage(
    context: Context,
    address: String,
    body: String,
    threadRemoteId: String? = null,
    attachments: List<CommunicateAttachment> = emptyList(),
    participants: List<String> = emptyList(),
    fromOutbox: Boolean = false,
): RcsSendResult = withContext(Dispatchers.IO) {
    if (!RcsFeature.enabled) return@withContext RcsSendResult.Failed
    val recipient = threadRemoteId?.takeIf { it.isNotBlank() } ?: toRcsRecipient(context, address)
    if (recipient.isBlank()) return@withContext RcsSendResult.Failed
    // Capability pre-check: unknown contacts are not capable → SMS path.
    val capable = runCatching {
        if (participants.isNotEmpty()) {
            RcsCapabilityExchange.filterCapable(context, participants).isNotEmpty()
        } else {
            RcsCapabilityExchange.isContactRcsCapable(context, recipient)
        }
    }.getOrDefault(false)
    if (!capable) return@withContext RcsSendResult.FallbackSms
    if (!RcsSipTransport.canSend()) return@withContext RcsSendResult.FallbackSms
    // Group thread (remoteId `rcs-group:…`): fan out 1:1 per member.
    if (recipient.startsWith("rcs-group:") && participants.isNotEmpty()) {
        return@withContext sendRcsGroup(context, recipient, body, attachments, participants, fromOutbox)
    }
    runCatching {
        // E2EE first: when the conversation has an MLS group, encrypt and send
        // the framed payload as an MLS content message. A conversation with a
        // group must never fall through to the plaintext paths below when
        // encryption or the encrypted send fails: that would silently
        // downgrade an encrypted chat to cleartext SIP (and, via the caller's
        // SMS fallback, to cleartext SMS). Fail instead so the user can retry.
        if (hasRcsE2eGroup(context, recipient)) {
            return@withContext if (sendRcsEncrypted(context, recipient, body)) {
                RcsSendResult.Sent
            } else {
                RcsSendResult.Failed
            }
        }
        // Prefer an established session (MSRP) when one exists; else pager-mode CPIM.
        if (sendRcsViaSession(context, recipient, body, attachments)) {
            return@withContext RcsSendResult.Sent
        }
        if (attachments.isNotEmpty()) {
            return@withContext sendRcsAttachments(context, recipient, body, attachments)
        }
        return@withContext sendRcsPager(context, recipient, body, fromOutbox)
    }.getOrElse {
        RcsSendResult.FallbackSms
    }
}

/**
 * Group thread (remoteId `rcs-group:…`): fan out 1:1 per member. UP has no
 * pager-mode group MESSAGE — groups are conference dialogs the carrier
 * focus hosts, or our own hosted focus (see hostGroupFocus). Until the
 * focus dialog is established, per-member 1:1 delivery keeps messages
 * flowing; once a focus session exists, hosted-focus relay takes over
 * inbound, and outbound still fans out (focus fan-out would double-send).
 */
private suspend fun CommunicateRepository.sendRcsGroup(
    context: Context,
    recipient: String,
    body: String,
    attachments: List<CommunicateAttachment>,
    participants: List<String>,
    fromOutbox: Boolean = false,
): RcsSendResult {
    var anyOk = false
    for (member in participants.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
        val memberResult = sendRcsMessage(
            context, member, body, member, attachments, emptyList(), fromOutbox = fromOutbox,
        )
        if (memberResult == RcsSendResult.Sent) anyOk = true
    }
    if (anyOk && body.isNotBlank()) {
        cacheOutgoingRcs(context, recipient, body, "local-${UUID.randomUUID()}")
    }
    return if (anyOk) RcsSendResult.Sent else RcsSendResult.FallbackSms
}

/** E2EE MLS send; true when sent (falls through when no group/encryption fails). */
private suspend fun CommunicateRepository.sendRcsEncrypted(
    context: Context,
    recipient: String,
    body: String,
): Boolean {
    val e2ePayload = encryptForE2E(context, recipient, body) ?: return false
    val (startLine, headers, content) = RcsSipTransport.buildChatMessage(
        fromUri = "sip:me@rcs",
        toUri = "sip:$recipient@rcs",
        callId = "${UUID.randomUUID()}@rcs-mls",
        body = android.util.Base64.encodeToString(
            e2ePayload,
            android.util.Base64.NO_WRAP,
        ),
    )
    // Re-wrap as the MLS content type (buildChatMessage defaults CPIM).
    val mlsHeaders = headers.replace(
        "Content-Type: message/cpim",
        "Content-Type: ${com.vayunmathur.communicate.data.rcs.e2e.RcsE2E.CT_MLS}",
    )
    val ok = RcsSipTransport.sendSipMessage(startLine, mlsHeaders, content)
    if (!ok) return false
    if (body.isNotBlank()) {
        cacheOutgoingRcs(context, recipient, body, "local-${UUID.randomUUID()}")
    }
    return true
}

/** Session (MSRP) send with background session kick; true when sent. */
private suspend fun CommunicateRepository.sendRcsViaSession(
    context: Context,
    recipient: String,
    body: String,
    attachments: List<CommunicateAttachment>,
): Boolean {
    // When no session exists yet, kick off session establishment in the
    // background (best-effort — the pager send below still goes out now;
    // later messages upgrade to MSRP once the dialog completes). Backoff
    // after dialog errors (§1.6) skips the fast re-INVITE.
    val session = RcsSessionManager.sessionFor(recipient)
    if (session?.msrpRemotePath != null && attachments.isEmpty()) {
        val cpim = RcsSipTransport.buildCpimBody(body).toByteArray(Charsets.UTF_8)
        if (RcsMsrp.send(session, context, cpim)) {
            if (body.isNotBlank()) {
                cacheOutgoingRcs(context, recipient, body, "local-${UUID.randomUUID()}")
            }
            return true
        }
    }
    if (session == null && shouldStartSession(recipient, attachments)) {
        val peer = recipient
        sendScope.launch {
            runCatching { RcsSessionManager.startSession("sip:$peer@rcs", peer) }
        }
    }
    return false
}

/** Attachment send (FT-over-HTTP or v1 envelope). */
private suspend fun CommunicateRepository.sendRcsAttachments(
    context: Context,
    recipient: String,
    body: String,
    attachments: List<CommunicateAttachment>,
): RcsSendResult {
    val repository = this
    // FT-over-HTTP when the content server is known, else the v1 envelope.
    val ftOk = if (RcsFileTransferHttp.contentServerUri != null) {
        var oneOk = false
        for (attachment in attachments) {
            if (RcsFileTransferHttp.sendFile(context, repository, recipient, attachment, body)) {
                oneOk = true
            }
        }
        oneOk
    } else {
        RcsFileTransfer.sendFiles(context, repository, recipient, body, attachments)
    }
    return if (ftOk) RcsSendResult.Sent else RcsSendResult.FallbackSms
}

/** Pager-mode send with large-message chunking. */
private suspend fun CommunicateRepository.sendRcsPager(
    context: Context,
    recipient: String,
    body: String,
    fromOutbox: Boolean = false,
): RcsSendResult {
    val (startLine, headers, content) = RcsSipTransport.buildChatMessage(
        fromUri = "sip:me@rcs",
        toUri = "sip:$recipient@rcs",
        body = body,
    )
    // Large Message: chunk oversized bodies with a shared Message-ID so
    // the far end reassembles; cache one row per send (snippet = full text).
    // Single-chunk 1:1 messages prefer the confirmed dialog (§1.4) when
    // one exists; multi-chunk stays pager-mode (one dialog CSeq per
    // chunk would serialize poorly and gain nothing).
    val messageId = "rcs-${UUID.randomUUID()}"
    val chunks = chunkLargeMessage(messageId, body)
    if (chunks.size == 1) {
        val inDialog = RcsSessionManager.sendInDialogMessage(recipient, content)
        if (inDialog) {
            if (body.isNotBlank()) {
                cacheOutgoingRcs(context, recipient, body, messageId)
            }
            return RcsSendResult.Sent
        }
    }
    var ok = true
    for ((index, chunk) in chunks.withIndex()) {
        val (line, head, payload) = if (chunks.size == 1) {
            Triple(startLine, headers, content)
        } else {
            RcsSipTransport.buildChatMessage(
                fromUri = "sip:me@rcs",
                toUri = "sip:$recipient@rcs",
                callId = "${UUID.randomUUID()}@rcs-lm$index",
                body = chunk,
            )
        }
        ok = RcsSipTransport.sendSipMessage(line, head, payload) && ok
    }
    if (!ok) {
        // Transport accepted the capability check but the SIP leg failed
        // (transient): park for deferred retry (§4.3) AND report
        // FallbackSms so the caller still sends SMS now. Duplicate risk
        // (SMS now + RCS later) beats message loss on a dev-gated line.
        // A retry that came FROM the outbox must not enqueue a second
        // row for the same message: the pump keeps the original row and
        // bumps its attempts, so a fresh row would send the message
        // twice once the transport recovers.
        if (!fromOutbox) RcsOutbox.enqueue(context, recipient, body)
        return RcsSendResult.FallbackSms
    }
    if (body.isNotBlank()) {
        cacheOutgoingRcs(context, recipient, body, messageId)
    }
    return RcsSendResult.Sent
}

/**
 * True when a fresh 1:1 SIP session should be started for [recipient]:
 * a bare user id (not a group/chat URI) with no text attachments.
 */
private fun shouldStartSession(recipient: String, attachments: List<CommunicateAttachment>): Boolean =
    attachments.isEmpty() &&
        !recipient.startsWith("rcs-group:") &&
        !recipient.contains("@") &&
        !recipient.contains(":") &&
        !RcsSessionManager.inDialogBackoff(recipient)

/**
 * Build an RCS recipient id from a phone number / address. Conference URIs
 * (groups) pass through; otherwise the number is normalized to E.164 via
 * libphonenumber, mirroring [toSignalRecipient].
 */
internal fun CommunicateRepository.toRcsRecipient(context: Context, address: String): String {
    if (address.isBlank()) return ""
    if (address.contains("@") || address.contains(":")) return address
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

private fun CommunicateRepository.rcsDisplayName(context: Context, conversationId: String): String? {
    if (conversationId.contains("@") || conversationId.contains(":")) return conversationId
    return findContactName(context, conversationId) ?: conversationId
}

/** Insert an outgoing RCS message into the local cache so it shows in our own thread. */
internal suspend fun CommunicateRepository.cacheOutgoingRcs(
    context: Context,
    conversationId: String,
    body: String,
    messageId: String,
) {
    runCatching {
        val db = RcsDatabase.getDatabase(context)
        val now = System.currentTimeMillis()
        db.cachedMessageDao().upsert(
            RcsCachedMessage(
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
            (existing ?: RcsConversation(remoteId = conversationId)).copy(lastMessageTimestamp = now),
        )
    }
}

/** Write an inbound RCS message into the local cache (sync service). */
internal suspend fun CommunicateRepository.cacheInboundRcs(
    context: Context,
    conversationId: String,
    body: String,
    senderId: String = "",
    messageId: String = "in-${UUID.randomUUID()}",
    ftUrl: String? = null,
    ftMime: String? = null,
) {
    runCatching {
        val db = RcsDatabase.getDatabase(context)
        val now = System.currentTimeMillis()
        // SIP/MSRP redelivery can hand us the same messageId twice; the
        // upsert is idempotent but an unconditional unread bump is not.
        val isNew = db.cachedMessageDao().get(messageId) == null
        db.cachedMessageDao().upsert(
            RcsCachedMessage(
                messageId = messageId,
                conversationId = conversationId,
                body = body,
                timestamp = now,
                outgoing = false,
                senderId = senderId,
                ftUrl = ftUrl,
                ftMime = ftMime,
            ),
        )
        val existing = db.conversationDao().getConversation(conversationId)
        db.conversationDao().upsert(
            (existing ?: RcsConversation(remoteId = conversationId)).copy(
                lastMessageTimestamp = now,
                unreadCount = (existing?.unreadCount ?: 0) + if (isNew) 1 else 0,
            ),
        )
    }
}

/** Cache an outgoing file-transfer row (our own FT send echo). */
internal suspend fun CommunicateRepository.cacheOutgoingRcsFile(
    context: Context,
    conversationId: String,
    body: String,
    messageId: String,
    ftUrl: String?,
    ftMime: String?,
) {
    runCatching {
        val db = RcsDatabase.getDatabase(context)
        val now = System.currentTimeMillis()
        db.cachedMessageDao().upsert(
            RcsCachedMessage(
                messageId = messageId,
                conversationId = conversationId,
                body = body,
                timestamp = now,
                outgoing = true,
                ftUrl = ftUrl,
                ftMime = ftMime,
                status = 1,
            ),
        )
        val existing = db.conversationDao().getConversation(conversationId)
        db.conversationDao().upsert(
            (existing ?: RcsConversation(remoteId = conversationId)).copy(lastMessageTimestamp = now),
        )
    }
}

/** Mark an RCS conversation read (clears the local unread badge). */
suspend fun CommunicateRepository.markRcsRead(
    context: Context,
    remoteId: String?,
    address: String,
): Unit = withContext(Dispatchers.IO) {
    if (!RcsFeature.enabled) return@withContext
    val cid = remoteId?.takeIf { it.isNotBlank() } ?: toRcsRecipient(context, address)
    runCatching {
        val db = RcsDatabase.getDatabase(context)
        db.conversationDao().getConversation(cid)?.let {
            if (it.unreadCount != 0) db.conversationDao().upsert(it.copy(unreadCount = 0))
        }
    }
    // Opening the thread displayed the messages — fire display IMDN (§4.2).
    sendRcsDisplayReports(context, cid)
}

/** Surface the SMS-fallback notice on the caller's behalf (never `Toast`). */
fun CommunicateRepository.notifyRcsFallback(context: Context) {
    AppMessages.show(context.getString(com.vayunmathur.communicate.R.string.rcs_fallback_sms))
}

/**
 * Send a Geolocation Push to [recipient] (UP Geolocation Push, geosms tag).
 * Returns true when the SIP leg accepted.
 */
suspend fun CommunicateRepository.sendRcsGeopush(
    context: Context,
    recipient: String,
    latitude: Double,
    longitude: Double,
    label: String? = null,
): Boolean = withContext(Dispatchers.IO) {
    if (!RcsFeature.enabled || !RcsSipTransport.canSend()) return@withContext false
    runCatching {
        val body = buildGeopushBody(latitude, longitude, label)
        val (startLine, headers, content) = RcsSipTransport.buildChatMessage(
            fromUri = "sip:me@rcs",
            toUri = "sip:$recipient@rcs",
            callId = "${UUID.randomUUID()}@rcs-geo",
            body = body,
        )
        val geoHeaders = headers.replace(
            "Content-Type: message/cpim",
            "Content-Type: application/vnd.gsma.rcs.geopush+xml",
        )
        if (!RcsSipTransport.sendSipMessage(startLine, geoHeaders, content)) {
            return@withContext false
        }
        cacheOutgoingRcs(context, recipient, label ?: "📍 Location", "local-${UUID.randomUUID()}")
        true
    }.getOrDefault(false)
}

/**
 * Revoke our outgoing message [messageId] in [conversationId]: sends the
 * revoke report and blanks the local row. Returns true when sent.
 */
suspend fun CommunicateRepository.revokeRcsMessage(
    context: Context,
    conversationId: String,
    messageId: String,
): Boolean = withContext(Dispatchers.IO) {
    if (!RcsFeature.enabled || !RcsSipTransport.canSend()) return@withContext false
    runCatching {
        val (startLine, headers, content) = RcsSipTransport.buildChatMessage(
            fromUri = "sip:me@rcs",
            toUri = "sip:$conversationId@rcs",
            callId = "${UUID.randomUUID()}@rcs-revoke",
            body = buildRevokeBody(messageId),
        )
        val ok = RcsSipTransport.sendSipMessage(startLine, headers, content)
        if (ok) {
            val db = RcsDatabase.getDatabase(context)
            db.cachedMessageDao().get(messageId)?.let {
                db.cachedMessageDao().upsert(it.copy(body = ""))
            }
        }
        ok
    }.getOrDefault(false)
}

/**
 * Edit our outgoing message [messageId] in [conversationId] to [newText]:
 * sends the replacement and updates the local row. Returns true when sent.
 */
suspend fun CommunicateRepository.editRcsMessage(
    context: Context,
    conversationId: String,
    messageId: String,
    newText: String,
): Boolean = withContext(Dispatchers.IO) {
    if (!RcsFeature.enabled || !RcsSipTransport.canSend() || newText.isBlank()) {
        return@withContext false
    }
    runCatching {
        val (startLine, headers, content) = RcsSipTransport.buildChatMessage(
            fromUri = "sip:me@rcs",
            toUri = "sip:$conversationId@rcs",
            callId = "${UUID.randomUUID()}@rcs-edit",
            body = buildEditBody(messageId, newText),
        )
        val ok = RcsSipTransport.sendSipMessage(startLine, headers, content)
        if (ok) {
            val db = RcsDatabase.getDatabase(context)
            db.cachedMessageDao().get(messageId)?.let {
                db.cachedMessageDao().upsert(it.copy(body = newText))
            }
        }
        ok
    }.getOrDefault(false)
}

/**
 * Apply an inbound delivery/display report: advance the cached outgoing
 * message's status ticks (Delivered=2, Read=3). Called from the sync service
 * alongside [RcsImdn.onReportReceived].
 */
internal suspend fun CommunicateRepository.applyRcsDeliveryReport(
    context: Context,
    messageId: String,
    disposition: ImdnDisposition,
) {
    if (!RcsFeature.enabled) return
    runCatching {
        val db = RcsDatabase.getDatabase(context)
        when (disposition) {
            ImdnDisposition.Delivered -> db.cachedMessageDao().markDelivered(messageId)
            ImdnDisposition.Displayed -> db.cachedMessageDao().markReadStatus(messageId)
            ImdnDisposition.Failed -> {
                db.cachedMessageDao().get(messageId)?.let {
                    db.cachedMessageDao().upsert(it.copy(status = 4))
                }
            }
        }
    }
}

/** True when [recipient]'s conversation has an MLS E2EE group. */
internal suspend fun CommunicateRepository.hasRcsE2eGroup(
    context: Context,
    recipient: String,
): Boolean =
    com.vayunmathur.communicate.data.rcs.RcsFeature.enabled &&
        com.vayunmathur.communicate.data.rcs.e2e.RcsE2E.groupIdFor(context, recipient) != null

/**
 * Encrypt [body] for [recipient]'s E2EE group when one exists. Returns the
 * framed MLS payload, or null when the conversation is plaintext (no group)
 * or encryption failed. Callers that know a group exists (see
 * [hasRcsE2eGroup]) must treat null as a failure, never as plaintext.
 */
internal suspend fun CommunicateRepository.encryptForE2E(
    context: Context,
    recipient: String,
    body: String,
): ByteArray? {
    if (!com.vayunmathur.communicate.data.rcs.RcsFeature.enabled || body.isBlank()) return null
    // Group threads address by remoteId; 1:1 threads by E.164 recipient.
    val conversationId = recipient
    val hasGroup = com.vayunmathur.communicate.data.rcs.e2e.RcsE2E.groupIdFor(context, conversationId) != null
    if (!hasGroup) return null
    val local = com.vayunmathur.communicate.data.rcs.e2e.RcsE2E.localE164(context) ?: return null
    return com.vayunmathur.communicate.data.rcs.e2e.RcsE2E.encryptTo(
        context, local, conversationId, body.toByteArray(Charsets.UTF_8),
    )
}
