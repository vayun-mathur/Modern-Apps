package com.vayunmathur.communicate.data.whatsapp

import com.vayunmathur.library.log.Log
import com.vayunmathur.communicate.data.whatsapp.e2e.WhatsAppE2E
import com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket

/**
 * Retry-receipt handling (split from WhatsAppClientSync.kt for file length).
 * Behavior identical, call sites unchanged.
 */

internal suspend fun WhatsAppClient.sendRetryReceipt(node: WhatsAppProtocol.Node) {
    val auth = authData ?: return
    val crypto = ensureE2E(auth) ?: return
    val ws = webSocket ?: return
    val msgId = node.attrs["id"] ?: return
    val count = undecryptableTracker.merge("retry:$msgId", 1) { a, b -> a + b } ?: 1
    if (count > MAX_RETRY_RECEIPTS) {
        Log.status(TAG, "Not sending more retry receipts for $msgId")
        return
    }
    val keysNode = if (count > 1) {
        try { crypto.buildRetryReceiptKeysNode(accountDeviceIdentity()) } catch (expected: Exception) {
            Log.status(TAG, "Failed to build retry keys node", expected); null
        }
    } else null
    val receipt = WhatsAppProtocol.buildRetryReceipt(node, auth.registrationId, count, keysNode)
    ws.send(WhatsAppProtocol.encodeNode(receipt))
    Log.dev(TAG, "Sent retry receipt #$count for $msgId")
}

/**
 * Handle an inbound <receipt type="retry">: the peer failed to decrypt a message we sent and
 * is asking us to re-encrypt and resend it. Rebuild the session from the fresh keys included
 * in the receipt (present from the 2nd retry), then re-encrypt the cached plaintext for just
 * the requesting device and resend with the same message id. Ref whatsmeow retry.go
 * handleRetryReceipt. 1:1 only — group skmsg resends are not cached.
 */
internal suspend fun WhatsAppClient.handleRetryReceipt(node: WhatsAppProtocol.Node) {
    val auth = authData ?: return
    val crypto = ensureE2E(auth) ?: return
    val ws = webSocket ?: return
    val msgId = node.attrs["id"] ?: return
    val deviceJid = retryDeviceJid(node, msgId) ?: return
    val cached = recentSentDMs[msgId]
    if (cached == null) {
        WhatsAppDiag.log(TAG, "retry: no cached message for $msgId; cannot resend")
    }
    val ready = cached != null && checkResendBudget(msgId, deviceJid)
    val sessionReady = ready && rebuildRetrySessionFor(crypto, node, deviceJid, msgId)
    if (sessionReady) {
        val resendCount = retryResendCounts["$msgId|$deviceJid"] ?: 1
        resendRetryMessage(auth, crypto, ws, msgId, deviceJid, cached!!, resendCount)
    }
}

/** Rebuild + verify the session; false when unusable. */
private suspend fun WhatsAppClient.rebuildRetrySessionFor(
    crypto: WhatsAppE2E,
    node: WhatsAppProtocol.Node,
    deviceJid: String,
    msgId: String,
): Boolean {
    rebuildRetrySession(crypto, node, deviceJid, deviceJid.substringBefore("@").substringAfter(":", "0"))
    if (!ensureSession(deviceJid)) {
        WhatsAppDiag.log(TAG, "retry: no session for $deviceJid; cannot resend $msgId")
        return false
    }
    return true
}

/** The target device JID, or null with a log when the retry is not actionable. */
private fun WhatsAppClient.retryDeviceJid(node: WhatsAppProtocol.Node, msgId: String): String? {
    val from = node.attrs["from"] ?: return null
    // The specific device that couldn't decrypt: participant if present, else the chat peer.
    val deviceJid = node.attrs["participant"] ?: from
    if (deviceJid.contains("@g.us")) {
        WhatsAppDiag.log(TAG, "retry: ignoring group retry for $msgId (not cached)")
        return null
    }
    return deviceJid
}

/** Increment the resend counter; false when the budget is exhausted. */
private fun WhatsAppClient.checkResendBudget(msgId: String, deviceJid: String): Boolean {
    val resendKey = "$msgId|$deviceJid"
    val resendCount = retryResendCounts.merge(resendKey, 1) { a, b -> a + b } ?: 1
    if (resendCount > MAX_RETRY_RECEIPTS) {
        WhatsAppDiag.log(TAG, "retry: giving up on $msgId for $deviceJid after $resendCount attempts")
        return false
    }
    return true
}

/** Rebuild the session from receipt keys, when the peer attached them. */
private suspend fun WhatsAppClient.rebuildRetrySession(
    crypto: WhatsAppE2E,
    node: WhatsAppProtocol.Node,
    deviceJid: String,
    deviceNumRaw: String,
) {
    if (node.getChildByTag("keys") == null) return
    try {
        // parsePreKeyBundleNode reads <registration> + <keys> from the node it is given.
        val bundle = crypto.parsePreKeyBundleNode(deviceNumRaw.toIntOrNull() ?: 0, node)
        if (bundle != null) {
            crypto.deleteSession(deviceJid)
            crypto.processPreKeyBundle(deviceJid, bundle)
            WhatsAppDiag.log(TAG, "retry: rebuilt session for $deviceJid from receipt keys")
        }
    } catch (expected: Exception) {
        WhatsAppDiag.log(TAG, "retry: failed to process receipt keys for $deviceJid: ${expected.message}")
    }
}

/** Re-encrypt the cached plaintext for the requesting device and resend. */
private suspend fun WhatsAppClient.resendRetryMessage(
    auth: WhatsAppAuthData,
    crypto: WhatsAppE2E,
    ws: WhatsAppSocket,
    msgId: String,
    deviceJid: String,
    cached: SentDM,
    resendCount: Int,
) {
    val ownUser = auth.wid.substringBefore("@").substringBefore(":").substringBefore(".")
    val devUser = deviceJid.substringBefore("@").substringBefore(":").substringBefore(".")
    val plaintext = if (devUser == ownUser && cached.dsmPlaintextPadded != null) {
        cached.dsmPlaintextPadded
    } else {
        cached.msgPlaintextPadded
    }
    val enc = try {
        crypto.encryptDM(deviceJid, plaintext)
    } catch (expected: Exception) {
        WhatsAppDiag.log(TAG, "retry: re-encrypt failed for $deviceJid: ${expected.message}")
        return
    }
    val includeIdentity = enc.type == "pkmsg"
    val resend = WhatsAppProtocol.buildFanOutMessageNode(
        to = cached.to,
        id = msgId,
        type = cached.type,
        participantEncs = listOf(WhatsAppProtocol.ParticipantEnc(deviceJid, enc.type, enc.data)),
        includeDeviceIdentity = includeIdentity,
        deviceIdentity = if (includeIdentity) accountDeviceIdentity() else null,
    )
    val sent = ws.send(WhatsAppProtocol.encodeNode(resend))
    WhatsAppDiag.log(TAG, "retry: resent $msgId to $deviceJid (attempt $resendCount type=${enc.type}) sent=$sent")
}
