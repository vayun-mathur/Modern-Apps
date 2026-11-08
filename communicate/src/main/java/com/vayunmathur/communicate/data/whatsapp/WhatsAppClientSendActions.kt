package com.vayunmathur.communicate.data.whatsapp

import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient.State
import com.vayunmathur.communicate.data.whatsapp.buildChatPresence
import com.vayunmathur.communicate.data.whatsapp.buildContactProto
import com.vayunmathur.communicate.data.whatsapp.buildDisappearingTimerProto
import com.vayunmathur.communicate.data.whatsapp.buildEditProto
import com.vayunmathur.communicate.data.whatsapp.buildLocationProto
import com.vayunmathur.communicate.data.whatsapp.buildPollCreationProto
import com.vayunmathur.communicate.data.whatsapp.buildPollVoteMessage
import com.vayunmathur.communicate.data.whatsapp.buildReactionProto
import com.vayunmathur.communicate.data.whatsapp.buildReadReceipt
import com.vayunmathur.communicate.data.whatsapp.buildRevokeProto
import com.vayunmathur.communicate.data.whatsapp.encodeNode
import com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket
import com.vayunmathur.communicate.data.whatsapp.generateMessageId
import com.vayunmathur.communicate.data.whatsapp.isRevokeFromMe
import com.vayunmathur.library.log.Log

/**
 * Mark messages as read with per-sender batching for group chats.
 * From Go HandleMatrixReadReceipt — groups messages by sender.
 */
suspend fun WhatsAppClient.markRead(
    conversationId: String,
    messageIds: List<String> = emptyList(),
    senderJids: Map<String, String> = emptyMap(),
) {
    val to = extractJid(conversationId) ?: return
    val ws = webSocket ?: return
    if (messageIds.isEmpty()) return

    // Filter out own messages by checking both JID and LID (Issue 4)
    val ownJid = authData?.wid ?: ""
    val ownLid = authData?.lid ?: ""
    val filteredIds = messageIds.filter { msgId ->
        val sender = senderJids[msgId] ?: ""
        sender != ownJid && (ownLid.isEmpty() || sender != ownLid)
    }
    if (filteredIds.isEmpty()) return

    val isGroup = to.contains("@g.us")
    if (isGroup && senderJids.isNotEmpty()) {
        sendGroupReadReceipts(ws, to, filteredIds, senderJids)
    } else {
        val node = WhatsAppProtocol.buildReadReceipt(chatJid = to, messageIds = filteredIds)
        ws.send(WhatsAppProtocol.encodeNode(node))
    }
}

/** Batch group read receipts by sender (Go HandleMatrixReadReceipt). */
private suspend fun WhatsAppClient.sendGroupReadReceipts(
    ws: com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket,
    to: String,
    filteredIds: List<String>,
    senderJids: Map<String, String>,
) {
    // Batch by sender for group chats (Go HandleMatrixReadReceipt)
    val bySender = mutableMapOf<String, MutableList<String>>()
    filteredIds.forEach { msgId ->
        val sender = senderJids[msgId] ?: ""
        bySender.getOrPut(sender) { mutableListOf() }.add(msgId)
    }
    bySender.forEach { (sender, ids) ->
        val node = WhatsAppProtocol.buildReadReceipt(
            chatJid = to,
            messageIds = ids,
            senderJid = sender.ifEmpty { null },
        )
        ws.send(WhatsAppProtocol.encodeNode(node))
    }
}

/**
 * Send a WhatsApp read receipt (<receipt type="read">) up to [lastMessageId] when the user
 * marks a thread read. No-op (returns true) when the account has read receipts disabled in
 * privacy settings — so we honor the user's choice rather than leaking read state. Integrator
 * broadcast contract. [lastTimestamp] is epoch ms (WhatsAppEvent convention) or s; both accepted.
 */
private const val MS_PER_SECOND = 1000L
private const val MS_THRESHOLD = 100_000_000_000L

suspend fun WhatsAppClient.sendReadReceipt(
    conversationId: String,
    lastMessageId: String?,
    lastTimestamp: Long,
    senderJid: String? = null,
): Boolean {
    if (stateMutable.value !is State.Connected) return false
    val ws = webSocket ?: return false
    val to = extractJid(conversationId) ?: return false
    if (lastMessageId.isNullOrEmpty()) return false
    // Message ids arrive DB-prefixed ("wa:RAWID"); WhatsApp only matches the raw id.
    val rawMessageId = extractMessageId(lastMessageId)
    if (!readReceiptsEnabled) {
        WhatsAppDiag.log(TAG, "read receipt suppressed (privacy off) for $to")
        return true
    }
    val tSec = when {
        lastTimestamp <= 0 -> System.currentTimeMillis() / MS_PER_SECOND
        lastTimestamp > MS_THRESHOLD -> lastTimestamp / MS_PER_SECOND // ms → s
        else -> lastTimestamp
    }
    val node = WhatsAppProtocol.buildReadReceipt(
        chatJid = to,
        messageIds = listOf(rawMessageId),
        senderJid = senderJid?.ifEmpty { null },
        timestamp = tSec,
    )
    val sent = ws.send(WhatsAppProtocol.encodeNode(node))
    WhatsAppDiag.log(TAG, "read receipt to=$to id=$rawMessageId sent=$sent")
    return sent
}

/**
 * Query the account privacy settings and cache whether read receipts are enabled. WhatsApp
 * returns <privacy><category name="readreceipts" value="all|none"/>…</privacy>; "none" means
 * the user turned read receipts off (and then cannot see others' either). Ref whatsmeow
 * privacysettings.go GetPrivacySettings. Failures leave the default (enabled) in place.
 */
internal suspend fun WhatsAppClient.refreshPrivacySettings() {
    val iq = WhatsAppProtocol.Node(
        tag = "iq",
        attrs = mapOf(
            "id" to generateMessageId(),
            "type" to "get",
            "xmlns" to "privacy",
            "to" to "s.whatsapp.net",
        ),
        content = listOf(WhatsAppProtocol.Node(tag = "privacy")),
    )
    val resp = sendIqAndWait(iq, timeoutMs = 10_000) ?: return
    val privacy = resp.getChildByTag("privacy") ?: return
    privacy.getChildren()
        .firstOrNull { it.tag == "category" && it.attrs["name"] == "readreceipts" }
        ?.let { readReceiptsEnabled = it.attrs["value"] != "none" }
    WhatsAppDiag.log(TAG, "privacy: readReceipts=${if (readReceiptsEnabled) "on" else "off"}")
}

/**
 * Send (or, with an empty [emoji], clear) a reaction on [messageId]. Routed through the same
 * Signal encryption + multi-device fan-out as a normal message — an unencrypted reaction node
 * is silently dropped by WhatsApp. The reaction key must point at the target message, so
 * [targetFromMe] / [targetSenderJid] describe the ORIGINAL message being reacted to.
 */
suspend fun WhatsAppClient.sendReaction(
    conversationId: String,
    messageId: String,
    emoji: String,
    targetFromMe: Boolean,
    targetSenderJid: String?,
): Boolean {
    if (stateMutable.value !is State.Connected) return false
    val ws = webSocket ?: return false
    val chatJid = extractJid(conversationId) ?: return false
    val rawTargetId = extractMessageId(messageId)
    val id = WhatsAppProtocol.generateMessageId(authData?.wid)

    val strippedEmoji = emoji.replace("\uFE0F", "")
    val proto = WhatsAppProtocol.buildReactionProto(
        chatJid = chatJid,
        targetMessageId = rawTargetId,
        emoji = strippedEmoji,
        targetFromMe = targetFromMe,
        targetSenderJid = targetSenderJid?.let { extractMessageId(it) }?.ifEmpty { null },
    )
    val node = if (chatJid.contains("@g.us")) {
        buildEncryptedGroupMessageNode(chatJid, id, proto, type = "reaction")
    } else {
        buildEncryptedMessageNode(chatJid, id, proto, "reaction")
    } ?: run { WhatsAppDiag.log(TAG, "reaction: build FAILED (no enc) for $chatJid"); return false }

    pendingMessageIDs.add(id)
    val sent = ws.send(WhatsAppProtocol.encodeNode(node))
    if (!sent) pendingMessageIDs.remove(id)
    WhatsAppDiag.log(
        TAG,
        "reaction to=$chatJid target=$rawTargetId emoji=${strippedEmoji.ifEmpty { "<remove>" }} sent=$sent")
    return sent
}

/**
 * Remove a reaction from a message by sending an empty emoji.
 * From Go HandleMatrixReactionRemove.
 */
suspend fun WhatsAppClient.removeReaction(
    conversationId: String,
    messageId: String,
    targetFromMe: Boolean,
    targetSenderJid: String?,
): Boolean = sendReaction(conversationId, messageId, "", targetFromMe, targetSenderJid)

/**
 * Send a typing indicator (chat presence) with media type differentiation.
 * From whatsmeow HandleMatrixTyping / SendChatPresence.
 * Supports: text typing, audio recording, media uploading.
 * (TypingType stays nested as WhatsAppClient.TypingType for API compatibility.)
 */

suspend fun WhatsAppClient.sendTyping(
    conversationId: String,
    isTyping: Boolean,
    typingType: WhatsAppClient.TypingType = WhatsAppClient.TypingType.TEXT,
) {
    if (stateMutable.value !is State.Connected) return
    val ws = webSocket ?: return
    val chatJid = extractJid(conversationId) ?: return

    // Go HandleMatrixTyping: UploadingMedia returns nil (not sent)
    if (typingType == WhatsAppClient.TypingType.UPLOADING_MEDIA) return

    val isAudio = typingType == WhatsAppClient.TypingType.RECORDING_AUDIO
    val node = WhatsAppProtocol.buildChatPresence(chatJid, isTyping, isAudio, authData?.wid ?: "")
    ws.send(WhatsAppProtocol.encodeNode(node))
}

/**
 * Edit a previously sent message. Builds a MESSAGE_EDIT ProtocolMessage proto and routes it
 * through the normal Signal encryption + multi-device fan-out (1:1 or group sender-key) with a
 * message-level `edit="1"` attribute. Previously this sent a plaintext `<enc>` that bypassed
 * Signal fan-out and was undeliverable.
 */
suspend fun WhatsAppClient.sendEdit(conversationId: String, targetMessageId: String, newBody: String): Boolean {
    if (stateMutable.value !is State.Connected) return false
    val ws = webSocket ?: return false
    val chatJid = extractJid(conversationId) ?: return false
    val id = WhatsAppProtocol.generateMessageId(authData?.wid)
    val rawTargetId = extractMessageId(targetMessageId)

    val proto = WhatsAppProtocol.buildEditProto(chatJid, rawTargetId, newBody)
    val editAttrs = mapOf("edit" to "1")
    val node = if (chatJid.contains("@g.us")) {
        buildEncryptedGroupMessageNode(chatJid, id, proto, type = "text", messageAttrs = editAttrs)
    } else {
        buildEncryptedMessageNode(chatJid, id, proto, "text", messageAttrs = editAttrs)
    } ?: run { WhatsAppDiag.log(TAG, "edit: build FAILED (no enc) for $chatJid"); return false }

    pendingMessageIDs.add(id)
    val sent = ws.send(WhatsAppProtocol.encodeNode(node))
    if (!sent) pendingMessageIDs.remove(id)
    WhatsAppDiag.log(TAG, "edit to=$chatJid target=$rawTargetId sent=$sent")
    return sent
}

/**
 * Revoke (delete-for-everyone) a previously sent message. Builds a REVOKE ProtocolMessage
 * proto and routes it through the normal Signal fan-out (1:1 or group sender-key) with the
 * message-level `edit` attribute ("7" own / "8" other). Previously sent a plaintext `<enc>`.
 */
suspend fun WhatsAppClient.sendRevoke(
    conversationId: String,
    targetMessageId: String,
    senderJid: String = ""): Boolean {
    if (stateMutable.value !is State.Connected) return false
    val ws = webSocket ?: return false
    val chatJid = extractJid(conversationId) ?: return false
    val id = WhatsAppProtocol.generateMessageId(authData?.wid)
    val ownJid = authData?.wid ?: ""
    val rawTargetId = extractMessageId(targetMessageId)

    val proto = WhatsAppProtocol.buildRevokeProto(chatJid, senderJid, rawTargetId, ownJid)
    val isFromMe = WhatsAppProtocol.isRevokeFromMe(senderJid, ownJid)
    val revokeAttrs = mapOf("edit" to if (isFromMe) "7" else "8")
    val node = if (chatJid.contains("@g.us")) {
        buildEncryptedGroupMessageNode(chatJid, id, proto, type = "text", messageAttrs = revokeAttrs)
    } else {
        buildEncryptedMessageNode(chatJid, id, proto, "text", messageAttrs = revokeAttrs)
    } ?: run { WhatsAppDiag.log(TAG, "revoke: build FAILED (no enc) for $chatJid"); return false }

    pendingMessageIDs.add(id)
    val sent = ws.send(WhatsAppProtocol.encodeNode(node))
    if (!sent) pendingMessageIDs.remove(id)
    WhatsAppDiag.log(TAG, "revoke to=$chatJid target=$rawTargetId sent=$sent")
    return sent
}

/**
 * Send a native WhatsApp poll (PollCreationMessage), routed through the normal multi-device
 * fan-out (1:1) or sender-key path (groups). A fresh 32-byte messageSecret is generated and
 * stored keyed by the message id so later votes can derive their encryption key. Mirrors
 * whatsmeow BuildPollCreation.
 */
suspend fun WhatsAppClient.sendPollCreation(
    conversationId: String,
    question: String,
    options: List<String>,
    selectableCount: Int = 0,
): String? {
    WhatsAppDiag.log(TAG, "poll: sendPollCreation entry conv=$conversationId opts=${options.size}")
    if (stateMutable.value !is State.Connected) { WhatsAppDiag.log(TAG, "poll: not connected"); return null }
    val ws = webSocket ?: run { WhatsAppDiag.log(TAG, "poll: no websocket"); return null }
    val to = extractJid(conversationId) ?: run { WhatsAppDiag.log(TAG, "poll: bad convId"); return null }
    val id = WhatsAppProtocol.generateMessageId(authData?.wid)

    val messageSecret = ByteArray(32).also { random.nextBytes(it) }
    val msg = WhatsAppProtocol.buildPollCreationProto(question, options, selectableCount, messageSecret)
    // Polls use stanza type "poll" (not "text") — the server drops a poll sent as text.
    // Ref whatsmeow send.go getTypeFromMessage.
    WhatsAppDiag.log(TAG, "poll: start to=$to options=${options.size} group=${to.contains("@g.us")}")
    val node = if (to.contains("@g.us")) {
        buildEncryptedGroupMessageNode(to, id, msg, type = "poll")
    } else {
        buildEncryptedMessageNode(to, id, msg, "poll")
    } ?: run {
        WhatsAppDiag.log(TAG, "poll: build node FAILED (encryption produced no recipients)")
        return null
    }

    pendingMessageIDs.add(id)
    val sent = ws.send(WhatsAppProtocol.encodeNode(node))
    WhatsAppDiag.log(TAG, "poll: stanza sent=$sent id=$id")
    if (sent) {
        storePollOptions(id, options)
        storePollSecret(id, messageSecret)
    } else {
        pendingMessageIDs.remove(id)
    }
    // Return the real message id so the caller can reconcile its optimistic pending row —
    // votes (and stored secret/options) are keyed by this id.
    return if (sent) id else null
}

/**
 * Shared media-send contract poll entrypoint (called by MessagesSessionManager). Returns the
 * real poll message id on success (null on failure) so the pending row can be reconciled.
 * Maps [allowMultiple] to selectableCount (whatsmeow PollCreationMessage semantics).
 */
suspend fun WhatsAppClient.sendPoll(
    conversationId: String,
    question: String,
    options: List<String>,
    allowMultiple: Boolean,
): String? = sendPollCreation(
    conversationId = conversationId,
    question = question,
    options = options,
    selectableCount = if (allowMultiple) options.size else 1,
)

/**
 * Cast (or change) a poll vote. Encrypts the selected option hashes with the poll's
 * messageSecret and sends a PollUpdateMessage via the normal fan-out (stanza type "poll").
 * [pollCreatorJid]/[pollFromMe] describe the ORIGINAL poll message (needed for key derivation
 * + the vote's pollCreationMessageKey). Ref whatsmeow BuildPollVote.
 */
suspend fun WhatsAppClient.sendPollVote(
    conversationId: String,
    pollMessageId: String,
    pollCreatorJid: String,
    pollFromMe: Boolean,
    selectedOptionNames: List<String>,
): Boolean {
    if (stateMutable.value !is State.Connected) return false
    val ws = webSocket ?: return false
    val chatJid = extractJid(conversationId) ?: return false
    val rawPollId = extractMessageId(pollMessageId)
    val secret = loadPollSecret(rawPollId)
        ?: run { WhatsAppDiag.log(TAG, "poll vote: no secret for $rawPollId"); return false }
    val ownJid = authData?.wid ?: ""
    val id = WhatsAppProtocol.generateMessageId(authData?.wid)

    val optionHashes = selectedOptionNames.map { option ->
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(option.toByteArray(Charsets.UTF_8))
    }
    val creator = if (pollFromMe) ownJid else pollCreatorJid.ifEmpty { chatJid }
    val proto = WhatsAppProtocol.buildPollVoteMessage(
        chatJid = chatJid,
        pollMessageId = rawPollId,
        pollCreatorJid = creator,
        pollFromMe = pollFromMe,
        voterJid = ownJid,
        optionHashes = optionHashes,
        pollSecret = secret,
    )
    val node = if (chatJid.contains("@g.us")) {
        buildEncryptedGroupMessageNode(chatJid, id, proto, type = "poll")
    } else {
        buildEncryptedMessageNode(chatJid, id, proto, "poll")
    } ?: return false
    pendingMessageIDs.add(id)
    val sent = ws.send(WhatsAppProtocol.encodeNode(node))
    if (!sent) pendingMessageIDs.remove(id)
    return sent
}

/**
 * Send a location message natively (LocationMessage proto), routed through the normal
 * multi-device Signal fan-out so it actually encrypts/delivers like a text message.
 * From whatsmeow from-matrix.go location handling.
 */
suspend fun WhatsAppClient.sendLocation(
    conversationId: String,
    latitude: Double,
    longitude: Double,
    name: String? = null,
    address: String? = null,
): Boolean {
    if (stateMutable.value !is State.Connected) return false
    val ws = webSocket ?: return false
    val to = extractJid(conversationId) ?: return false
    val id = WhatsAppProtocol.generateMessageId(authData?.wid)

    val msg = WhatsAppProtocol.buildLocationProto(latitude, longitude, name, address)
    val node = buildEncryptedMessageNode(to, id, msg, "text") ?: return false
    pendingMessageIDs.add(id)
    val sent = ws.send(WhatsAppProtocol.encodeNode(node))
    if (!sent) pendingMessageIDs.remove(id)
    return sent
}

/**
 * Send a contact/vCard message. Routed through the normal Signal encryption + multi-device
 * fan-out (1:1 or group sender-key) — previously emitted a plaintext `<enc>` that was dropped.
 * From Go wa-contact.go convertContactMessage.
 */
suspend fun WhatsAppClient.sendContact(
    conversationId: String,
    displayName: String,
    vcard: String,
): Boolean {
    if (stateMutable.value !is State.Connected) return false
    val ws = webSocket ?: return false
    val chatJid = extractJid(conversationId) ?: return false
    val id = WhatsAppProtocol.generateMessageId(authData?.wid)

    val proto = WhatsAppProtocol.buildContactProto(displayName, vcard)
    val node = if (chatJid.contains("@g.us")) {
        buildEncryptedGroupMessageNode(chatJid, id, proto, type = "text")
    } else {
        buildEncryptedMessageNode(chatJid, id, proto, "text")
    } ?: run { WhatsAppDiag.log(TAG, "contact: build FAILED (no enc) for $chatJid"); return false }

    pendingMessageIDs.add(id)
    val sent = ws.send(WhatsAppProtocol.encodeNode(node))
    if (!sent) pendingMessageIDs.remove(id)
    WhatsAppDiag.log(TAG, "contact to=$chatJid sent=$sent")
    return sent
}

/**
 * Set disappearing messages timer.
 * From Go HandleMatrixDisappearingTimer.
 * Allowed values: 0 (off), 86400 (24h), 604800 (7d), 7776000 (90d).
 *
 * 1:1 chats carry the setting as an EPHEMERAL_SETTING ProtocolMessage over the normal Signal
 * fan-out. Groups instead use a `w:g2` IQ (`<ephemeral expiration=..>` / `<not_ephemeral/>`)
 * so the whole group is updated server-side, matching whatsmeow SetDisappearingTimer.
 */
suspend fun WhatsAppClient.setDisappearingTimer(conversationId: String, timerSeconds: Long): Boolean {
    if (stateMutable.value !is State.Connected) return false
    val ws = webSocket ?: return false
    val chatJid = extractJid(conversationId) ?: return false

    val allowedValues = setOf(0L, 86400L, 604800L, 7776000L)
    if (timerSeconds !in allowedValues) {
        Log.status(TAG, "Invalid disappearing timer value: $timerSeconds")
        return false
    }

    val id = WhatsAppProtocol.generateMessageId(authData?.wid)
    if (chatJid.contains("@g.us")) {
        return setGroupDisappearingTimer(chatJid, id, timerSeconds)
    }
    return sendDisappearingTimerMessage(ws, chatJid, id, timerSeconds)
}

/** 1:1 disappearing timer via encrypted protocol message. */
private suspend fun WhatsAppClient.sendDisappearingTimerMessage(
    ws: WhatsAppSocket,
    chatJid: String,
    id: String,
    timerSeconds: Long,
): Boolean {
    val proto = WhatsAppProtocol.buildDisappearingTimerProto(timerSeconds)
    val node = buildEncryptedMessageNode(chatJid, id, proto, "text")
        ?: run { WhatsAppDiag.log(TAG, "disappearing: build FAILED (no enc) for $chatJid"); return false }
    pendingMessageIDs.add(id)
    val sent = ws.send(WhatsAppProtocol.encodeNode(node))
    if (!sent) pendingMessageIDs.remove(id)
    WhatsAppDiag.log(TAG, "disappearing to=$chatJid secs=$timerSeconds sent=$sent")
    return sent
}

/** Group disappearing timer via `w:g2` IQ. */
private suspend fun WhatsAppClient.setGroupDisappearingTimer(
    chatJid: String,
    id: String,
    timerSeconds: Long,
): Boolean {
    val child = if (timerSeconds == 0L) {
        WhatsAppProtocol.Node(tag = "not_ephemeral")
    } else {
        WhatsAppProtocol.Node(tag = "ephemeral", attrs = mapOf("expiration" to timerSeconds.toString()))
    }
    val iq = WhatsAppProtocol.Node(
        tag = "iq",
        attrs = mapOf("id" to id, "type" to "set", "xmlns" to "w:g2", "to" to chatJid),
        content = listOf(child),
    )
    val resp = sendIqAndWait(iq, timeoutMs = 10_000)
    val ok = resp != null && resp.attrs["type"] != "error"
    WhatsAppDiag.log(TAG, "disappearing(group) to=$chatJid secs=$timerSeconds ok=$ok")
    return ok
}
