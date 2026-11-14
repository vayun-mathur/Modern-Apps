package com.vayunmathur.communicate.data.signal

import android.util.Base64 as AndroidBase64
import com.vayunmathur.library.log.Log
import com.vayunmathur.communicate.data.signal.e2e.remoteRegistrationId
import com.vayunmathur.communicate.data.signal.e2e.senderRegistrationId
import com.vayunmathur.communicate.data.signal.transport.SignalPayload
import org.signal.libsignal.protocol.message.DecryptionErrorMessage
import org.signal.libsignal.protocol.message.PlaintextContent
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketMessage

/**
 * SignalClient inbound envelope pipeline (split from SignalClient.kt for file length).
 *
 * Extension functions on [SignalClient]; behavior identical, call sites unchanged.
 */

// -- Inbound ----

internal suspend fun SignalClient.handleInboundFrame(raw: ByteArray) {
    val wsMessage = SignalProtocol.parseWebSocketMessage(raw)
    if (wsMessage == null) {
        Log.status(TAG, "unparseable ws frame len=${raw.size}")
        return
    }
    if (wsMessage.type != WebSocketMessage.Type.REQUEST || !wsMessage.hasRequest()) return
    val req = wsMessage.request
    val ackId = if (req.hasId()) req.id else null

    // Control frames carry nothing to persist, so they can be acked straight away.
    val isControlFrame = SignalProtocol.isQueueEmptySignal(raw) ||
        (req.hasPath() && req.path.contains("keepalive")) ||
        !req.hasBody()
    if (isControlFrame) {
        ackEnvelope(ackId)
        return
    }

    val handled = try {
        processEnvelope(wsMessage)
    } catch (expected: kotlinx.coroutines.CancellationException) {
        throw expected
    } catch (expected: Throwable) {
        // Redelivery cannot fix a deterministic failure, and there is no attempt counter, so
        // acking is the lesser evil: not acking would spin on the same envelope forever.
        Log.error(TAG, "failed to process envelope, acking anyway to avoid a redelivery loop", expected)
        true
    }
    // An ack deletes the message from the server's queue, so it is deferred until the envelope has
    // been decrypted, validated and handed to the event pipeline. Note this is hand-off, not a
    // durable write: SignalEventProcessor persists asynchronously and swallows its own failures.
    if (handled) {
        ackEnvelope(ackId)
    } else {
        Log.status(TAG, "not acking envelope; leaving it queued for redelivery")
    }
}

private suspend fun SignalClient.ackEnvelope(requestId: Long?) {
    if (requestId == null) return
    val ack = SignalProtocol.buildWsResponseProto(requestId, 200)
    try { socket?.send(SignalProtocol.encodeWebSocketResponse(ack)) } catch (_: Exception) {}
}

/**
 * Returns whether the envelope may be acked. False means "leave it on the server queue", which is
 * only appropriate when we could not even attempt to handle it — a malformed or undecryptable
 * message returns true, since redelivering it would only spin.
 */
private suspend fun SignalClient.processEnvelope(wsMessage: WebSocketMessage): Boolean {
    val envelopeProto = SignalProtocol.parseEnvelopeFromWsMessage(wsMessage) ?: return true
    val env = SignalProtocol.toSignalEnvelope(envelopeProto)

    // Server delivery receipt (plaintext, no content) -> emit ReadReceipt as delivery
    if (env.type == SignalServiceProtos.Envelope.Type.SERVER_DELIVERY_RECEIPT) {
        emitServerReceipt(env)
        return true
    }

    if (env.content.isEmpty()) return true
    return processDecryptedEnvelope(env)
}

/** Decrypt, validate, and dispatch an envelope with content. */
private suspend fun SignalClient.processDecryptedEnvelope(env: SignalProtocol.SignalEnvelope): Boolean {
    val paddedPlaintext = decryptEnvelope(env) ?: return true
    // Signal pads the plaintext before encrypting, for every envelope type.
    val plaintext = SignalProtocol.stripMessagePadding(paddedPlaintext)

    val content = SignalProtocol.parseContent(plaintext)
    if (content == null) {
        Log.status(TAG, "parseContent failed for ${env.sourceAci}")
        return true
    }
    if (env.type == SignalServiceProtos.Envelope.Type.PLAINTEXT_CONTENT &&
        !SignalProtocol.isValidPlaintextContent(content)
    ) {
        Log.status(TAG, "dropping PLAINTEXT_CONTENT carrying more than a DecryptionErrorMessage from ${env.sourceAci}")
        return true
    }
    val parsed = SignalProtocol.classifyContent(content)
    captureSenderKeys(env, parsed, content)
    val masterKeyFromData = masterKeyFrom(parsed)
    // A verified PNI signature merges the sender's ACI with the PNI we knew them by, which is what
    // keeps their reply in the conversation we sent to instead of opening a second one.
    if (content.hasPniSignatureMessage()) {
        linkPniToAci(env.sourceAci, content.pniSignatureMessage)
    }
    val conversationId = if (masterKeyFromData != null) {
        SignalProtocol.toConversationId(env.sourceAci, masterKeyFromData)
    } else {
        // Anchored on the contact rather than the raw service id, so ACI- and PNI-addressed traffic
        // share one thread.
        conversationIdFor(env.sourceAci)
    }
    val senderDisplayName = displayNameFor(env.sourceAci)
    val senderAci = env.sourceAci
    // Remember the group's identity so a reply can be sent. Without this the conversation is created with
    // no master key or members and every reply is refused or silently dropped.
    if (masterKeyFromData != null) {
        rememberInboundGroup(conversationId, masterKeyFromData, env.sourceAci)
    }
    val senderDevice = env.sourceDevice
    val serverGuid = env.serverGuid ?: SignalProtocol.generateMessageId()
    val timestamp = env.timestamp

    dispatchParsedContent(
        parsed,
        InboundDispatch(
            env,
            conversationId,
            senderAci,
            senderDevice,
            senderDisplayName,
            timestamp,
            serverGuid,
            masterKeyFromData,
        ),
    )
    return true
}

/**
 * Capture the sender's keys before dispatching: the profile key (what lets us send sealed-sender
 * back to them, independent of message kind) and any sender-key distribution (what lets their
 * later group messages decrypt, including when it arrives on its own Content with no DataMessage).
 */
private suspend fun SignalClient.captureSenderKeys(
    env: SignalProtocol.SignalEnvelope,
    parsed: SignalProtocol.ParsedContent,
    content: SignalServiceProtos.Content,
) {
    profileKeyFrom(parsed)?.let { key ->
        db?.let { SignalSealedSender.rememberProfileKey(it, env.sourceAci, key) }
    }
    if (!content.hasSenderKeyDistributionMessage()) return
    try {
        e2e?.processSenderKeyDistribution(
            env.sourceAci,
            env.sourceDevice,
            content.senderKeyDistributionMessage.toByteArray(),
        )
        Log.status(TAG, "stored a sender key from ${env.sourceAci}:${env.sourceDevice}")
    } catch (expected: Throwable) {
        // Losing this means later group messages from this sender cannot be decrypted.
        Log.status(TAG, "failed to store sender key distribution from ${env.sourceAci}", expected)
    }
}

/**
 * Tell [env]'s sender that we could not decrypt, so they rebuild the session.
 *
 * This is the protocol's recovery path: a `DecryptionErrorMessage` in a plaintext envelope makes the
 * sender archive their session and fetch a fresh pre-key bundle. Without it a session that has drifted
 * — for instance because our Kyber pre-key was replaced — stays broken forever, with every retry
 * failing identically.
 *
 * Sent unencrypted by design; `PLAINTEXT_CONTENT` is the one envelope type that may be, and it may
 * carry nothing but this.
 */
private suspend fun SignalClient.sendRetryReceipt(env: SignalProtocol.SignalEnvelope) {
    val e = e2e ?: return
    val sender = env.sourceAci.takeIf { it.isNotEmpty() } ?: return
    if (env.content.isEmpty()) return
    try {
        val errorMessage = DecryptionErrorMessage.forOriginalMessage(
            env.content,
            env.type.number,
            env.timestamp,
            env.sourceDevice,
        )
        val body = PlaintextContent(errorMessage).serialize()
        // The server rejects a send whose destinationRegistrationId does not match the recipient's, so
        // this must be their real one. A pre-key message carries it; otherwise fall back to the session.
        // Deliberately read before anything touches the session, and the session is not archived here —
        // the receipt asks *them* to archive theirs.
        val registrationId = e.senderRegistrationId(env.content)
            ?: e.remoteRegistrationId(sender, env.sourceDevice)
        if (registrationId == null) {
            Log.status(TAG, "no registration id for $sender:${env.sourceDevice}, cannot send a retry receipt")
            return
        }
        val message = SignalPayload.OutgoingPushMessage(
            type = SignalServiceProtos.Envelope.Type.PLAINTEXT_CONTENT.number,
            destinationDeviceId = env.sourceDevice,
            destinationRegistrationId = registrationId,
            content = body,
        )
        val json = SignalPayload.buildPutMessagesBody(
            destinationAci = sender,
            messages = listOf(message),
            timestamp = System.currentTimeMillis(),
            urgent = false,
        )
    when (val outcome = putMessages(sender, json, accessKey = null)) {
            is SignalClient.SendOutcome.Success ->
                Log.status(TAG, "sent a retry receipt to $sender:${env.sourceDevice}")
            else -> Log.status(TAG, "could not send a retry receipt to $sender: $outcome")
        }
    } catch (expected: Throwable) {
        Log.status(TAG, "could not build a retry receipt for $sender", expected)
    }
}

/**
 * Inbound attachment pointers, previously dropped entirely. The CDN URL is derived from
 * `cdnKey`/`cdnNumber`; the key and digest travel with the pointer and are what
 * [SignalClient.downloadMedia] needs to decrypt and verify.
 */
/** Original messageId for a reaction/edit/delete/poll target timestamp, else the timestamp itself. */
private suspend fun SignalClient.messageIdForTimestamp(conversationId: String, targetTs: Long): String {
    val cached = db?.cachedMessageDao()?.getForConversation(conversationId)
    return cached?.firstOrNull { it.timestamp == targetTs }?.messageId ?: targetTs.toString()
}

private fun SignalClient.attachmentsFrom(dm: SignalServiceProtos.DataMessage): List<SignalAttachment> =
    dm.attachmentsList.mapNotNull { pointer ->
        val cdnKey = pointer.cdnKey?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val mime = pointer.contentType?.takeIf { it.isNotEmpty() } ?: "application/octet-stream"
        SignalAttachment(
            url = attachmentUrl(cdnKey, pointer.cdnNumber),
            mimeType = mime,
            attachmentType = when {
                mime.startsWith("image/") -> "image"
                mime.startsWith("video/") -> "video"
                mime.startsWith("audio/") -> "audio"
                else -> "file"
            },
            fileName = pointer.fileName?.takeIf { it.isNotEmpty() },
            width = pointer.width,
            height = pointer.height,
        )
    }

private fun SignalClient.attachmentUrl(cdnKey: String, cdnNumber: Int): String {
    val host = if (cdnNumber == 0) "cdn.signal.org" else "cdn$cdnNumber.signal.org"
    return "https://$host/attachments/$cdnKey"
}

private suspend fun SignalClient.emitReadSync(timestamp: Long, fallbackConversationId: String) {
    val cached = try { db?.cachedMessageDao()?.getByTimestamp(timestamp) } catch (_: Exception) { null }
    eventsMutable.emit(
        SignalEvent.ReadReceipt(
            conversationId = cached?.conversationId ?: fallbackConversationId,
            messageId = cached?.messageId ?: timestamp.toString(),
            timestampMs = timestamp,
            timestamp = timestamp,
            isDelivery = false,
        ),
    )
}

private suspend fun SignalClient.emitDecryptionError(env: SignalProtocol.SignalEnvelope, message: String?) {
    val cid = SignalProtocol.toConversationId(env.sourceAci, null as ByteArray?)
    eventsMutable.emit(
        SignalEvent.DecryptionError(
            conversationId = cid,
            senderAci = env.sourceAci,
            senderDeviceId = env.sourceDevice,
            timestamp = env.timestamp,
            errorMessage = message,
        ),
    )
}

/**
 * Sealed-sender trust roots. These are build constants in the official client
 * (`UNIDENTIFIED_SENDER_TRUST_ROOTS`), a list so the server can rotate; a certificate is accepted
 * if it validates against any of them.
 */
private fun SignalClient.unidentifiedSenderTrustRoots(): List<org.signal.libsignal.protocol.ecc.ECPublicKey> =
    TRUST_ROOTS_B64.map {
        org.signal.libsignal.protocol.ecc.ECPublicKey(AndroidBase64.decode(it, AndroidBase64.NO_WRAP))
    }

/** The group conversation id a DataMessage belongs to, or empty when it is not a group message. */
private fun SignalClient.groupIdFor(dm: SignalServiceProtos.DataMessage): String {
    return if (dm.hasGroupV2() && dm.groupV2.hasMasterKey()) {
        SignalProtocol.toConversationId("", dm.groupV2.masterKey.toByteArray())
    } else {
        ""
    }
}



/** Emit a server delivery receipt as a ReadReceipt. */
private suspend fun SignalClient.emitServerReceipt(env: SignalProtocol.SignalEnvelope) {
    val cid = env.sourceAci.ifEmpty { env.destinationAci ?: "unknown" }
    val ts = if (env.timestamp != 0L) env.timestamp else env.serverTimestamp
    eventsMutable.emit(SignalEvent.ReadReceipt(
        conversationId = cid,
        messageId = env.serverGuid,
        timestampMs = ts,
        timestamp = ts,
        isDelivery = true))
}

/**
 * Decrypt the envelope content; null when queued (no store) or acked (decrypt failure).
 * A failure must never fall back to the raw envelope bytes: those are attacker
 * controlled, so treating them as a Content would let anyone forge a message from any ACI.
 */
private suspend fun SignalClient.decryptEnvelope(env: SignalProtocol.SignalEnvelope): ByteArray? {
    val e = e2e
    if (e == null) {
        // Defensive: start() refuses to connect without a store, so this should be unreachable.
        // Keep the envelope queued rather than acking something we never tried to decrypt.
        Log.status(TAG, "no protocol store, leaving envelope from ${env.sourceAci} queued")
        return null
    }
    return try {
        // Which identity the sender addressed matters: a message to our PNI must be decrypted with the
        // PNI identity and its own pre-keys, not the ACI ones.
        Log.status(
            TAG,
            "envelope type=${env.type} from=${env.sourceAci}:${env.sourceDevice} " +
                "destination=${env.destinationAci} ourAci=${authData?.aci} ourPni=${authData?.pni}",
        )
        when (env.type) {
            SignalServiceProtos.Envelope.Type.UNIDENTIFIED_SENDER ->
                e.sealedSenderDecrypt(env.content, unidentifiedSenderTrustRoots(), env.serverTimestamp)
            SignalServiceProtos.Envelope.Type.PREKEY_MESSAGE ->
                e.decryptDM(env.sourceAci, env.sourceDevice, true, env.content)
            SignalServiceProtos.Envelope.Type.DOUBLE_RATCHET ->
                e.decryptDM(env.sourceAci, env.sourceDevice, false, env.content)
            // The one unencrypted envelope type, and it may only carry a DecryptionErrorMessage
            // (enforced after parsing, below).
            SignalServiceProtos.Envelope.Type.PLAINTEXT_CONTENT -> env.content
            else -> throw IllegalArgumentException("unknown envelope type ${env.type}")
        }
    } catch (expected: Throwable) {
        Log.status(TAG, "decrypt failed for ${env.sourceAci}:${env.sourceDevice}", expected)
        emitDecryptionError(env, expected.message)
        // Ask the sender to rebuild the session; otherwise this fails identically forever.
        if (env.type != SignalServiceProtos.Envelope.Type.PLAINTEXT_CONTENT) sendRetryReceipt(env)
        // Redelivery cannot fix a decrypt failure, so ack rather than spin on it.
        null
    }
}

/** Group master key from Data/Edit content, or null. */
private fun masterKeyFrom(parsed: SignalProtocol.ParsedContent): ByteArray? = when (parsed) {
    is SignalProtocol.ParsedContent.Data -> {
        val group = parsed.dataMessage.takeIf { it.hasGroupV2() }?.groupV2
        group?.takeIf { it.hasMasterKey() }?.masterKey?.toByteArray()
    }
    is SignalProtocol.ParsedContent.Edit -> {
        val data = parsed.editMessage.takeIf { it.hasDataMessage() }?.dataMessage
        val group = data?.takeIf { it.hasGroupV2() }?.groupV2
        group?.takeIf { it.hasMasterKey() }?.masterKey?.toByteArray()
    }
    else -> null
}

/** Shared inbound dispatch state for one envelope. */
private class InboundDispatch(
    val env: SignalProtocol.SignalEnvelope,
    val conversationId: String,
    val senderAci: String,
    val senderDevice: Int,
    val senderDisplayName: String,
    val timestamp: Long,
    val serverGuid: String,
    val masterKeyFromData: ByteArray?,
)

/** Handle one parsed content branch. */
private suspend fun SignalClient.dispatchParsedContent(
    parsed: SignalProtocol.ParsedContent,
    dispatch: InboundDispatch,
) {
    when (parsed) {
        is SignalProtocol.ParsedContent.Data -> handleDataContent(parsed, dispatch)
        is SignalProtocol.ParsedContent.Receipt -> handleReceiptContent(parsed, dispatch)
        is SignalProtocol.ParsedContent.Typing -> handleTypingContent(parsed, dispatch)
        is SignalProtocol.ParsedContent.Edit -> handleEditContent(parsed, dispatch)
        is SignalProtocol.ParsedContent.Call -> handleCallContent(parsed, dispatch)
        is SignalProtocol.ParsedContent.Sync -> handleSyncContent(parsed, dispatch)
        else -> {
            Log.status(TAG, "unhandled Content type ${parsed::class.simpleName} from ${dispatch.senderAci}")
        }
    }
}
/** Handle Data content. */
private suspend fun SignalClient.handleDataContent(
    parsed: SignalProtocol.ParsedContent.Data,
    dispatch: InboundDispatch,
) {
    val dm = parsed.dataMessage
    when {
        dm.hasReaction() -> handleReaction(dm, dispatch)
        dm.hasDelete() -> handleDelete(dm, dispatch)
        dm.hasPollCreate() -> handlePollCreate(dm, dispatch)
        dm.hasPollVote() -> handlePollVote(dm, dispatch)
        dm.hasPollTerminate() -> handlePollTerminate(dm, dispatch)
        else -> handleTextData(dm, dispatch)
    }
}

/** Resolve a target message id by timestamp, falling back to the timestamp itself. */
private suspend fun SignalClient.targetMessageId(conversationId: String, targetTs: Long): String = try {
    messageIdForTimestamp(conversationId, targetTs)
} catch (_: Exception) {
    targetTs.toString()
}

private suspend fun SignalClient.handleReaction(
    dm: SignalServiceProtos.DataMessage,
    dispatch: InboundDispatch,
) {
    val r = dm.reaction
    val targetId = targetMessageId(dispatch.conversationId, r.targetSentTimestamp)
    if (r.remove) {
    eventsMutable.emit(SignalEvent.ReactionRemoved(
        conversationId = dispatch.conversationId,
        messageId = targetId,
        senderId = dispatch.senderAci))
    } else {
    eventsMutable.emit(SignalEvent.ReactionReceived(
        conversationId = dispatch.conversationId,
        messageId = targetId,
        senderId = dispatch.senderAci,
        emoji = r.emoji))
    }
}

private suspend fun SignalClient.handleDelete(
    dm: SignalServiceProtos.DataMessage,
    dispatch: InboundDispatch,
) {
    val targetId = targetMessageId(dispatch.conversationId, dm.delete.targetSentTimestamp)
    eventsMutable.emit(SignalEvent.MessageDeleted(
    messageId = targetId,
    conversationId = dispatch.conversationId,
    timestamp = dispatch.timestamp))
}

private suspend fun SignalClient.handlePollCreate(
    dm: SignalServiceProtos.DataMessage,
    dispatch: InboundDispatch,
) {
    val pc = dm.pollCreate
    val sd = SignalServiceData(
    pollQuestion = pc.question,
    pollOptions = pc.optionsList.map { SignalPollOptionData(it) },
    senderId = dispatch.senderAci)
    eventsMutable.emit(SignalEvent.IncomingMessage(
    conversationId = dispatch.conversationId,
    messageId = dispatch.serverGuid,
    body = pc.question,
    peerName = dispatch.senderDisplayName,
    peerPhone = null,
    timestamp = dispatch.timestamp,
    senderId = dispatch.senderAci,
    serviceData = sd.serialize(),
    pollQuestion = pc.question,
    pollOptions = pc.optionsList))
}

private suspend fun SignalClient.handlePollVote(
    dm: SignalServiceProtos.DataMessage,
    dispatch: InboundDispatch,
) {
    val pv = dm.pollVote
    val pollId = targetMessageId(dispatch.conversationId, pv.targetSentTimestamp)
    val pollCached = try { db?.cachedMessageDao()?.get(pollId) } catch (_: Exception) { null }
    val pollOptions = pollCached?.serviceData?.let { raw ->
    SignalServiceData.parse(raw)?.pollOptions?.map { o -> o.name }
    } ?: emptyList()
    val selected = pv.optionIndexesList.mapNotNull { idx ->
    pollOptions.getOrNull(idx)
    }.ifEmpty { pv.optionIndexesList.map { it.toString() } }
    eventsMutable.emit(SignalEvent.PollVote(
    conversationId = dispatch.conversationId,
    pollMessageId = pollId,
    voterId = dispatch.senderAci,
    optionNames = selected))
}

private suspend fun SignalClient.handlePollTerminate(
    dm: SignalServiceProtos.DataMessage,
    dispatch: InboundDispatch,
) {
    // Treat as generic incoming for now; live will handle poll closure UI via serviceData flag.
    eventsMutable.emit(SignalEvent.IncomingMessage(
    conversationId = dispatch.conversationId,
    messageId = dispatch.serverGuid,
    body = dm.body,
    peerName = dispatch.senderDisplayName,
    peerPhone = null,
    timestamp = dispatch.timestamp,
    senderId = dispatch.senderAci,
    serviceData = SignalServiceData(senderId = dispatch.senderAci).serialize()))
}

private suspend fun SignalClient.handleTextData(
    dm: SignalServiceProtos.DataMessage,
    dispatch: InboundDispatch,
) {
    val body = dm.body
    if (body.isBlank() && dm.attachmentsCount == 0 && !dm.hasGroupV2()) return
    // senderKeyDistributionMessage is handled above, before content dispatch.
    val sd = SignalServiceData(
    senderId = dispatch.senderAci,
    senderName = dispatch.senderDisplayName,
    isGroup = dispatch.masterKeyFromData != null)
    eventsMutable.emit(
    SignalEvent.IncomingMessage(
        conversationId = dispatch.conversationId,
        messageId = dispatch.serverGuid,
        body = body,
        peerName = dispatch.senderDisplayName,
        peerPhone = null,
        timestamp = dispatch.timestamp,
        senderId = dispatch.senderAci,
        attachments = attachmentsFrom(dm),
        serviceData = sd.serialize(),
    ),
    )
}

/** Handle Receipt content. */
private suspend fun SignalClient.handleReceiptContent(
    parsed: SignalProtocol.ParsedContent.Receipt,
    dispatch: InboundDispatch,
) {
    val rm = parsed.receiptMessage
    val isDelivery = rm.type == SignalServiceProtos.ReceiptMessage.Type.DELIVERY
    Log.status(
        TAG,
        "receipt from ${dispatch.env.sourceAci}: type=${rm.type} timestamps=${rm.timestampList}",
    )
    for (tsVal in rm.timestampList) {
        // Resolved by timestamp rather than by conversation: the outgoing message may have been
        // filed under a different id (phone number vs service id) than this receipt resolves to,
        // and the timestamp is the message's protocol-level identity either way.
        val cached = try {
            db?.cachedMessageDao()?.getOutgoingByTimestamp(tsVal)
        } catch (_: Exception) { null }
        if (cached == null) {
            Log.status(TAG, "receipt for unknown outgoing message at $tsVal")
        }
        eventsMutable.emit(
            SignalEvent.ReadReceipt(
                conversationId = cached?.conversationId ?: dispatch.conversationId,
                messageId = cached?.messageId ?: tsVal.toString(),
                timestampMs = tsVal,
                timestamp = tsVal,
                isDelivery = isDelivery,
            ),
        )
    }
}

/** Handle Typing content. */
private suspend fun SignalClient.handleTypingContent(
    parsed: SignalProtocol.ParsedContent.Typing,
    dispatch: InboundDispatch,
) {
    val tm = parsed.typingMessage
    val isTyping = tm.action == SignalServiceProtos.TypingMessage.Action.STARTED
    val cid = if (tm.hasGroupId()) {
        // groupId is the 32-byte GroupIdentifier, which is exactly what the conversation id
        // encodes, so it maps across directly.
        SignalProtocol.toConversationId("", SignalGroups.run { tm.groupId.toByteArray().toHex() })
    } else dispatch.conversationId
    eventsMutable.emit(SignalEvent.TypingIndicator(
        conversationId = cid,
        senderId = dispatch.senderAci,
        isTyping = isTyping))
}

/** Handle Edit content. */
private suspend fun SignalClient.handleEditContent(
    parsed: SignalProtocol.ParsedContent.Edit,
    dispatch: InboundDispatch,
) {
    val em = parsed.editMessage
    val targetTs = em.targetSentTimestamp
    val newBody = em.dataMessage.body
    val targetId = try {
        messageIdForTimestamp(dispatch.conversationId, targetTs)
    } catch (_: Exception) { targetTs.toString() }
    eventsMutable.emit(SignalEvent.MessageEdited(
        conversationId = dispatch.conversationId,
        messageId = targetId,
        newBody = newBody,
        timestamp = dispatch.timestamp))
}

/** Handle Call content. */
private suspend fun SignalClient.handleCallContent(
    parsed: SignalProtocol.ParsedContent.Call,
    dispatch: InboundDispatch,
) {
    handleCallMessage(parsed.callMessage, dispatch.senderAci, dispatch.senderDevice, dispatch.env, dispatch.timestamp)
}

/** Handle Sync content. */
private suspend fun SignalClient.handleSyncContent(
    parsed: SignalProtocol.ParsedContent.Sync,
    dispatch: InboundDispatch,
) {
    val sm = parsed.syncMessage
    // Read/viewed syncs from our own other devices refer to inbound messages by sent timestamp,
    // so resolve on that rather than scoping to a conversation id that may not match.
    for (r in sm.readList) {
        emitReadSync(r.timestamp, dispatch.conversationId)
    }
    for (v in sm.viewedList) {
        emitReadSync(v.timestamp, dispatch.conversationId)
    }
}
