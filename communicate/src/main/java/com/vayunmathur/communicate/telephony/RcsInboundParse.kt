package com.vayunmathur.communicate.telephony

import android.telephony.ims.SipMessage
import com.vayunmathur.library.log.Log
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.applyRcsDeliveryReport
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.RcsFeature
import com.vayunmathur.communicate.data.rcs.RcsFileTransferHttp
import com.vayunmathur.communicate.data.rcs.RcsImdn
import com.vayunmathur.communicate.data.rcs.RcsSessionManager
import com.vayunmathur.communicate.data.rcs.e2e.RcsE2E
import com.vayunmathur.communicate.data.rcs.e2e.RcsKeyDirectory
import com.vayunmathur.communicate.data.rcs.e2e.RcsPeerKeys
import com.vayunmathur.communicate.data.rcs.e2e.RcsPendingGroups
import com.vayunmathur.communicate.data.rcs.e2e.RustMlsCrypto
import com.vayunmathur.communicate.data.rcs.extractImdnMessageId
import com.vayunmathur.communicate.data.rcs.parseChunkHeader
import com.vayunmathur.communicate.data.rcs.parseEditBody
import com.vayunmathur.communicate.data.rcs.parseGeopushBody
import com.vayunmathur.communicate.data.rcs.parseImdnBody
import com.vayunmathur.communicate.data.rcs.parseIsComposingBody
import com.vayunmathur.communicate.data.rcs.parseRevokeBody
import kotlinx.coroutines.launch

/**
 * Inbound RCS parse pipeline for [RcsSyncService], split for file length.
 * Extension functions sharing the service's scope: envelope repair, plaintext
 * routing (IMDN/typing/revoke/edit/geopush/chunks/FT), E2EE routing, and the
 * large-message reassembly buffers.
 */

internal enum class InboundKind { Text, Receipt, Typing }

/** Raw envelope: repaired headers + sender + content type + body + ids. */
internal data class Envelope(
    val from: String,
    val contentType: String?,
    val raw: String,
    val callId: String,
    val viaBranch: String,
)

internal data class InboundRcs(
    val conversationId: String,
    val body: String,
    val senderId: String,
    val messageId: String,
    val kind: InboundKind = InboundKind.Text,
    val ftUrl: String? = null,
    val ftMime: String? = null,
    val imdnMessageId: String? = null,
)

/** Large-message reassembly buffers: conversation → messageId → part → text. */
private val largeChunks =
    java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, MutableMap<Int, String>>>()

/** Empty-body receipt result for control stanzas. */
private fun receiptResult(from: String, callId: String): InboundRcs = InboundRcs(
    conversationId = from,
    body = "",
    senderId = from,
    messageId = "in-$callId",
    kind = InboundKind.Receipt,
)

/** Empty-body typing result for is-composing stanzas. */
private fun typingResult(from: String, callId: String): InboundRcs = InboundRcs(
    conversationId = from,
    body = "",
    senderId = from,
    messageId = "in-$callId",
    kind = InboundKind.Typing,
)

internal fun RcsSyncService.parseEnvelope(message: SipMessage): Envelope? {
    return runCatching {
        // Modem quirk (TestRcsApp RegistrationControllerImpl.repairHeaderSection):
        // some modems emit "ia:" instead of "Via:".
        var headers = message.getHeaderSection()
        if (headers.startsWith("ia:")) {
            headers = "V$headers"
            Log.status(RcsSyncService.TAG, "Repaired malformed Via header")
        }
        val from = headerValue(headers, "From:")?.substringAfter("<")?.substringBefore(">")
            ?.substringAfter("sip:")?.substringBefore("@")
            ?.takeIf { it.isNotBlank() } ?: return null
        val contentType = headerValue(headers, "Content-Type:")
        val raw = message.getContent().toString(Charsets.UTF_8)
        if (raw.isBlank()) return null
        Envelope(
            from = from,
            contentType = contentType,
            raw = raw,
            callId = message.getCallIdParameter() ?: raw.hashCode().toString(),
            viaBranch = message.getViaBranchParameter(),
        )
    }.getOrNull()
}

internal fun RcsSyncService.parseInbound(envelope: Envelope): InboundRcs? {
    return runCatching {
        val from = envelope.from
        val contentType = envelope.contentType
        val raw = envelope.raw
        // IMDN reports update delivery ticks AND route to the tracker.
        if (contentType?.contains("imdn", ignoreCase = true) == true ||
            parseImdnBody(raw) != null
        ) {
            parseImdnBody(raw)?.let { (id, disposition) ->
                serviceScope.launch {
                    CommunicateRepository.applyRcsDeliveryReport(this@parseInbound, id, disposition)
                }
            }
            RcsImdn.onReportReceived(raw)
            return receiptResult(from, envelope.callId)
        }
        if (contentType?.contains("im-composing", ignoreCase = true) == true ||
            parseIsComposingBody(raw) != null
        ) {
            RcsSessionManager.onSipRequest("MESSAGE", envelope.callId, from, contentType, raw)
            return typingResult(from, envelope.callId)
        }
        parseTextInbound(from, envelope, raw)
    }.getOrNull()
}

/** Text-body inbound: revoke/edit/body routing. */
private fun RcsSyncService.parseTextInbound(from: String, envelope: Envelope, raw: String): InboundRcs? {
    val body = extractTextBody(envelope.raw.toByteArray(Charsets.UTF_8)) ?: return null
    if (body.isBlank()) return null
    // Revoke: blank the referenced row, no inbox row.
    parseRevokeBody(body)?.let { revokedId ->
        serviceScope.launch {
            runCatching {
                val db = RcsDatabase.getDatabase(this@parseTextInbound)
                db.cachedMessageDao().get(revokedId)?.let {
                    db.cachedMessageDao().upsert(it.copy(body = ""))
                }
            }
        }
        return receiptResult(from, envelope.callId)
    }
    // Edit: swap the referenced row body, no new inbox row.
    parseEditBody(body)?.let { (originalId, newText) ->
        serviceScope.launch {
            runCatching {
                val db = RcsDatabase.getDatabase(this@parseTextInbound)
                db.cachedMessageDao().get(originalId)?.let {
                    db.cachedMessageDao().upsert(it.copy(body = newText))
                }
            }
        }
        return receiptResult(from, envelope.callId)
    }
    return parseBodyContent(from, envelope, raw, body)
}

/** Geolocation, large-message chunks, and file-transfer bodies. */
private fun RcsSyncService.parseBodyContent(
    from: String,
    envelope: Envelope,
    raw: String,
    body: String,
): InboundRcs {
    // Geolocation Push → location row.
    parseGeopushBody(body)?.let { (lat, lon, label) ->
        return InboundRcs(
            conversationId = from,
            body = label ?: "📍 $lat, $lon",
            senderId = from,
            messageId = "in-${envelope.callId}-${envelope.viaBranch}",
            kind = InboundKind.Text,
            ftUrl = "geo:$lat,$lon",
            ftMime = "application/vnd.gsma.rcs.geopush+xml",
            imdnMessageId = extractImdnMessageId(raw),
        )
    }
    // Large Message chunks: buffer per Message-ID; emit only when complete.
    parseChunkHeader(body)?.let { (chunkId, part, total) ->
        val complete = bufferLargeChunk(from, chunkId, part, total, body)
        if (complete == null) {
            return receiptResult(from, envelope.callId)
        }
        return InboundRcs(
            conversationId = from,
            body = complete,
            senderId = from,
            messageId = "in-$chunkId",
            kind = InboundKind.Text,
            imdnMessageId = extractImdnMessageId(raw),
        )
    }
    // FT-over-HTTP descriptors expose URL + mime for the renderer.
    val ft = RcsFileTransferHttp.parseFtBody(body)
    return InboundRcs(
        conversationId = from,
        body = ft?.let { body.substringAfter("\r\n").ifBlank { "[file]" } } ?: body,
        senderId = from,
        messageId = "in-${envelope.callId}-${envelope.viaBranch}",
        kind = InboundKind.Text,
        ftUrl = ft?.url,
        ftMime = ft?.mime,
        imdnMessageId = extractImdnMessageId(raw),
    )
}

/**
 * Route E2EE payloads: key-package publications (cache for group creation),
 * Welcome envelopes (join + persist), MLS commits/application (decrypt;
 * application text becomes an inbox row). Returns an [InboundRcs] when the
 * payload produces a visible row, null when fully absorbed (key packages,
 * commits, typing-like control).
 */
internal suspend fun RcsSyncService.handleE2EEInbound(envelope: Envelope): InboundRcs? {
    if (!RcsFeature.enabled || !RustMlsCrypto.isAvailable) return null
    val from = envelope.from
    val contentType = envelope.contentType
    val raw = envelope.raw
    val body = extractTextBody(raw.toByteArray(Charsets.UTF_8)) ?: raw
    // Key-request → auto-publish our key package (one round trip setup).
    if (isKeyRequest(contentType, body)) {
        RcsE2E.localE164(this)?.let { local ->
            RcsKeyDirectory.publishTo(this, local, from)
        }
        return InboundRcs(from, "", from, "in-kreq-${System.currentTimeMillis()}", InboundKind.Typing)
    }
    // Key package publication → stash, satisfy pending groups, and create
    // the 1:1 group if none exists yet.
    if (isKeyPackage(contentType, body)) {
        handleKeyPackage(from, body)
        return InboundRcs(from, "", from, "in-kp-${System.currentTimeMillis()}", InboundKind.Typing)
    }
    // Welcome envelope → join the group, then announce with a notice row.
    if (isWelcome(contentType, body)) {
        return handleWelcome(from, contentType, body)
    }
    // MLS data (commit or application) → decrypt against the conversation group.
    return handleMlsData(from, contentType, body)
}

/** True when the stanza is a key-request. */
private fun isKeyRequest(contentType: String?, body: String): Boolean =
    contentType?.contains(RcsE2E.CT_KEY_REQUEST, ignoreCase = true) == true ||
        body.contains(RcsE2E.CT_KEY_REQUEST, ignoreCase = true)

/** True when the stanza carries a key package publication. */
private fun isKeyPackage(contentType: String?, body: String): Boolean =
    contentType?.contains(RcsE2E.CT_KEY_PACKAGE, ignoreCase = true) == true ||
        body.contains(RcsE2E.CT_KEY_PACKAGE, ignoreCase = true)

/** True when the stanza is a group welcome. */
private fun isWelcome(contentType: String?, body: String): Boolean =
    contentType?.contains(RcsE2E.CT_WELCOME, ignoreCase = true) == true ||
        body.contains(RcsE2E.CT_WELCOME, ignoreCase = true) == true

/** Stash a key package, satisfy pending groups, auto-setup 1:1. */
private suspend fun RcsSyncService.handleKeyPackage(from: String, body: String) {
    RcsKeyDirectory.parsePublished(body)?.let { kp ->
        // Identity-change check (§5.4): verified peer with a new package
        // clears verification (re-key warning path — UI surfaces it).
        runCatching { RcsE2E.checkPeerIdentityChange(this, from, kp) }
        RcsPeerKeys.store(from, kp)
        // Pending encrypted groups: create each ready one now.
        for ((pendingId, packages) in RcsPendingGroups.readyFor(from)) {
            RcsE2E.localE164(this)?.let { local ->
                if (RcsE2E.setupEncryptedGroup(this, local, pendingId, packages)) {
                    RcsPendingGroups.remove(pendingId)
                }
            }
        }
        // Auto-setup: first key package for a 1:1 conversation with no
        // group creates it and sends Welcome. Closed-loop peers opt in
        // by running this code; no group forms with non-participants.
        val conversationId = from
        if (RcsE2E.groupIdFor(this, conversationId) == null &&
            !RcsPendingGroups.isPending(conversationId)
        ) {
            RcsE2E.localE164(this)?.let { local ->
                RcsE2E.setupGroupWithPeer(this, local, conversationId, from, kp)
            }
        }
    }
}

/** Join a welcomed group; notice row, Receipt-absorb for typed welcomes, else null. */
private suspend fun RcsSyncService.handleWelcome(from: String, contentType: String?, body: String): InboundRcs? {
    // Only a stanza typed as a Welcome on the wire is definitely a Welcome:
    // absorbing its failures keeps undecryptable key material out of the
    // plaintext cache (visible row + notification + focus relay + IMDN
    // report). A body-only token match may be pasted plaintext, so it still
    // falls through (null) on failure.
    val isTypedWelcome = contentType?.contains(RcsE2E.CT_WELCOME, ignoreCase = true) == true
    val welcomeBytes = decodePayloadBody(body)
        ?: return if (isTypedWelcome) receiptResult(from, "welcome-undecodable") else null
    val conversationId = from
    val groupId = RcsE2E.joinEncryptedGroup(this, conversationId, welcomeBytes)
    if (groupId != null) {
        // Rotate: publish a fresh key package so future adds work.
        RcsE2E.localE164(this)?.let { local ->
            RcsE2E.freshKeyPackage(this, local)
        }
        return InboundRcs(
            conversationId, "🔒 Encrypted chat started", from,
            "in-welcome-${System.currentTimeMillis()}",
        )
    }
    return if (isTypedWelcome) receiptResult(from, "welcome-unjoinable") else null
}

/** Decrypt MLS data; visible row for app messages, null otherwise. */
private suspend fun RcsSyncService.handleMlsData(
    from: String,
    contentType: String?,
    body: String,
): InboundRcs? {
    val isMls = contentType?.contains(RcsE2E.CT_MLS, ignoreCase = true) == true ||
        contentType?.contains(RcsE2E.CT_COMMIT, ignoreCase = true) == true ||
        RcsE2E.isMlsPayload(body)
    if (!isMls) return null
    // From here the stanza is MLS, so every failure must be absorbed (a
    // non-text result), never null: null makes handleInbound fall through
    // to the plaintext path, which would cache the base64 ciphertext as a
    // visible "message", notify for it, and send a delivery report.
    val payload = decodePayloadBody(body) ?: return receiptResult(from, "mls-undecodable")
    val (plaintext, isApp) = RcsE2E.decryptFor(this, from, payload)
        ?: return receiptResult(from, "mls-undecryptable")
    if (!isApp) return receiptResult(from, "mls-commit")
    val text = plaintext.toString(Charsets.UTF_8)
    if (text.isBlank()) return receiptResult(from, "mls-blank")
    val callId = text.hashCode().toString()
    return InboundRcs(
        conversationId = from,
        body = text,
        senderId = from,
        messageId = "in-mls-$callId-${System.currentTimeMillis()}",
        ftUrl = null,
        ftMime = null,
        imdnMessageId = extractImdnMessageId(text),
    )
}

/**
 * Buffer one Large Message chunk; return the joined text once all [total]
 * parts for ([conversationId], [chunkId]) arrived, else null.
 */
internal fun bufferLargeChunk(
    conversationId: String,
    chunkId: String,
    part: Int,
    total: Int,
    body: String,
): String? {
    if (total <= 0 || part <= 0 || part > total) return null
    val text = body.substringAfter("\r\n\r\n", "").ifBlank {
        body.substringAfter("\n\n", "")
    }.trim()
    val convo = largeChunks.getOrPut(conversationId) { java.util.concurrent.ConcurrentHashMap() }
    val parts = convo.getOrPut(chunkId) { java.util.concurrent.ConcurrentHashMap() }
    parts[part] = text
    if (parts.size != total) return null
    val complete = (1..total).map { parts[it] ?: return null }.joinToString("")
    convo.remove(chunkId)
    if (convo.isEmpty()) largeChunks.remove(conversationId)
    return complete.ifBlank { null }
}

/** Base64-decode a CPIM-wrapped binary payload body. */
internal fun decodePayloadBody(body: String): ByteArray? {
    // NOTE: kept as a separate val (not `...}.takeIf { ... }` on the if-chain):
    // a trailing call binds to the last else-branch only, silently skipping the
    // blank guard for the CRLF branch.
    val afterGap = if (body.contains("\r\n\r\n")) {
        body.substringAfter("\r\n\r\n", "")
    } else if (body.contains("\n\n")) {
        body.substringAfter("\n\n", "")
    } else {
        body
    }
    val b64 = afterGap.trim().takeIf { it.isNotEmpty() } ?: return null
    return runCatching {
        android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}

/**
 * Extract display text from a message body. Pager-mode RCS arrives as
 * `message/cpim` (TestRcsApp CpimUtils); plain `text/plain` passes through.
 */
internal fun extractTextBody(content: ByteArray): String? {
    val raw = content.toString(Charsets.UTF_8)
    if (raw.isBlank()) return null
    if (raw.contains("message/cpim", ignoreCase = true)) {
        // CPIM: headers, blank line, payload. The payload after the last
        // double-CRLF is the text (cpim content with Content-Type text/plain).
        val sections = raw.split("\r\n\r\n")
        if (sections.size >= 2) {
            return sections.last().trim().takeIf { it.isNotEmpty() }
        }
        return null
    }
    return raw
}
