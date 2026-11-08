package com.vayunmathur.communicate.data.whatsapp

import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient.State
import com.vayunmathur.communicate.data.whatsapp.buildConversationMessage
import com.vayunmathur.communicate.data.whatsapp.buildFanOutMessageNode
import com.vayunmathur.communicate.data.whatsapp.buildMediaProto
import com.vayunmathur.communicate.data.whatsapp.buildTextProto
import com.vayunmathur.communicate.data.whatsapp.deviceSentPlaintext
import com.vayunmathur.communicate.data.whatsapp.encodeNode
import com.vayunmathur.communicate.data.whatsapp.encryptMedia
import com.vayunmathur.communicate.data.whatsapp.generateMessageId
import com.vayunmathur.communicate.data.whatsapp.padMessage
import com.vayunmathur.communicate.data.whatsapp.senderKeyDistributionPlaintext
import android.util.Base64
import com.vayunmathur.library.log.Log
import com.vayunmathur.communicate.data.whatsapp.e2e.WhatsAppE2E
import com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

// Recently sent 1:1 messages (id -> padded plaintext + own-device DSM), kept so we can
// re-encrypt and resend to a specific device when the peer asks for a retry
// (<receipt type="retry">). Without this, a message the recipient can't decrypt is acked
// locally (send "succeeds") but is never actually delivered.
private const val LOG_URL_PREFIX_LENGTH = 40

internal data class SentDM(
    val to: String,
    val type: String,
    val msgPlaintextPadded: ByteArray,
    val dsmPlaintextPadded: ByteArray?,
)

/**
 * Send a 1:1/group text message. Returns the WhatsApp message id on success (so callers can cache
 * the outgoing echo under the same id that delivery/read receipts reference), or null on failure.
 */
suspend fun WhatsAppClient.sendMessage(conversationId: String, body: String): String? {
    if (stateMutable.value !is State.Connected) { WhatsAppDiag.log(TAG, "send: not connected"); return null }
    val ws = webSocket ?: return null

    val to = extractJid(conversationId) ?: run { WhatsAppDiag.log(
        TAG,
        "send: bad convId $conversationId"); return null }
    val id = WhatsAppProtocol.generateMessageId(authData?.wid)
    WhatsAppDiag.log(TAG, "send: building message to $to")

    val node = buildEncryptedTextNode(
        to,
        id,
        body) ?: run { WhatsAppDiag.log(TAG, "send: build FAILED (no enc)"); return null }
    pendingMessageIDs.add(id)
    val sent = ws.send(WhatsAppProtocol.encodeNode(node))
    if (!sent) pendingMessageIDs.remove(id)
    WhatsAppDiag.log(TAG, "send: stanza sent=$sent id=$id")
    return if (sent) id else null
}

/**
 * Send a text message with optional rich content: @mentions, a quoted reply, and/or a URL
 * link-preview. When none are provided this is equivalent to [sendMessage] (a plain
 * `conversation` proto). Rich content is carried in an ExtendedTextMessage with ContextInfo so
 * the peer renders mentions/quote/preview. Routed through the normal Signal fan-out (1:1) or
 * group sender-key path. Ref whatsmeow ExtendedTextMessage / ContextInfo.
 */
suspend fun WhatsAppClient.sendMessageAdvanced(
    conversationId: String,
    body: String,
    mentionedJids: List<String> = emptyList(),
    quoted: WhatsAppProtocol.QuotedContext? = null,
    linkPreview: WhatsAppProtocol.LinkPreview? = null,
): String? {
    if (stateMutable.value !is State.Connected) { WhatsAppDiag.log(TAG, "sendAdv: not connected"); return null }
    val ws = webSocket ?: return null
    val to = extractJid(conversationId) ?: return null
    val id = WhatsAppProtocol.generateMessageId(authData?.wid)

    val proto = WhatsAppProtocol.buildTextProto(body, mentionedJids, quoted, linkPreview)
    val node = if (to.contains("@g.us")) {
        buildEncryptedGroupMessageNode(to, id, proto, type = "text")
    } else {
        buildEncryptedMessageNode(to, id, proto, "text")
    } ?: run { WhatsAppDiag.log(TAG, "sendAdv: build FAILED (no enc) for $to"); return null }

    pendingMessageIDs.add(id)
    val sent = ws.send(WhatsAppProtocol.encodeNode(node))
    if (!sent) pendingMessageIDs.remove(id)
    WhatsAppDiag.log(TAG, "sendAdv: sent=$sent id=$id mentions=${mentionedJids.size}" +
        "quoted=${quoted != null} preview=${linkPreview != null}")
    return if (sent) id else null
}

// ---------------------------------------------------------------------------------------------
// Phase 10a — call signaling spike (media/VoIP engine is native-only, out of scope this pass).
// ---------------------------------------------------------------------------------------------

/**
 * Send a WhatsApp call offer over the Noise socket (signaling only). GO/NO-GO: does the server
 * accept the offer and the target ring? Encrypts a random call key to every peer/own device via
 * the same Signal fan-out used for messages, then wraps it in a `<call><offer>` stanza.
 */
suspend fun WhatsAppClient.sendCallOffer(conversationId: String, video: Boolean = false): Boolean {
    if (stateMutable.value !is State.Connected) { WhatsAppDiag.log(TAG, "call: not connected"); return false }
    val ws = webSocket ?: return false
    val auth = authData ?: return false
    val crypto = ensureE2E(auth) ?: return false
    val to = extractJid(conversationId) ?: return false
    return sendCallOfferTo(ws, auth, crypto, to, video)
}

/** Encrypt a call key for the peer's (and our) devices and send the offer stanza. */
private suspend fun WhatsAppClient.sendCallOfferTo(
    ws: WhatsAppSocket,
    auth: WhatsAppAuthData,
    crypto: WhatsAppE2E,
    to: String,
    video: Boolean,
): Boolean {
    val ownUser = auth.wid.substringBefore("@").substringBefore(":").substringBefore(".")
    val callId = WhatsAppProtocol.generateMessageId(auth.wid)
    val stanzaId = WhatsAppProtocol.generateMessageId(auth.wid)
    val callKey = ByteArray(32).also { random.nextBytes(it) }
    val padded = WhatsAppProtocol.padMessage(callKey)
    val devices = (getUserDevices(listOf(to)).ifEmpty { listOf(to) } +
        getUserDevices(listOf("$ownUser@s.whatsapp.net"))).distinct()
    val (encs, _) = encryptForDevices(crypto, devices, ownUser, auth.wid, padded, null)
    if (encs.isEmpty()) { WhatsAppDiag.log(TAG, "call: no devices to encrypt to"); return false }
    val node = com.vayunmathur.communicate.data.whatsapp.call.WhatsAppCallSignaling.buildOffer(
        to,
        callId,
        auth.wid,
        video,
        encs,
        stanzaId)
    val sent = ws.send(WhatsAppProtocol.encodeNode(node))
    WhatsAppDiag.log(TAG, "call: offer sent=$sent callId=$callId to=$to devices=${encs.size}")
    return sent
}

/** Reject an inbound call. */
suspend fun WhatsAppClient.rejectCall(from: String, callId: String, callCreator: String): Boolean {
    val ws = webSocket ?: return false
    return ws.send(
        WhatsAppProtocol.encodeNode(
            com.vayunmathur.communicate.data.whatsapp.call.WhatsAppCallSignaling.buildReject(
                from,
                callId,
                callCreator,
                generateMessageId()),
        ),
    )
}

/** Place a WhatsApp call (audio or video) via the call manager. */
fun WhatsAppClient.placeCall(conversationId: String, video: Boolean = false) {
    val to = extractJid(conversationId) ?: return
    com.vayunmathur.communicate.data.whatsapp.call.WhatsAppCallManager.placeCall(to, video)
}

/**
 * Build a Signal-encrypted text message node for a recipient, fanning out to all of the
 * recipient's devices and our own other devices (via usync), establishing sessions as needed.
 * Group recipients (@g.us) use the sender-key (skmsg) path. Returns null on failure.
 */
internal suspend fun WhatsAppClient.buildEncryptedTextNode(
    to: String,
    id: String,
    body: String): WhatsAppProtocol.Node? {
    if (to.contains("@g.us")) {
        return buildEncryptedGroupTextNode(to, id, body)
    }
    return buildEncryptedMessageNode(to, id, WhatsAppProtocol.buildConversationMessage(body), "text")
}

/** Encrypt+fan-out an arbitrary Message proto to a 1:1 recipient (and our own devices). */
internal suspend fun WhatsAppClient.buildEncryptedMessageNode(
    to: String,
    id: String,
    msg: WhatsAppE2EProto.Message,
    type: String,
    extraEncAttrs: Map<String, String> = emptyMap(),
    messageAttrs: Map<String, String> = emptyMap(),
): WhatsAppProtocol.Node? {
    val auth = authData ?: return null
    val crypto = ensureE2E(auth) ?: return null
    val msgPlaintext = WhatsAppProtocol.padMessage(msg.toByteArray())
    val dsmPlaintext = WhatsAppProtocol.padMessage(WhatsAppProtocol.deviceSentPlaintext(to, msg))
    val ownUser = auth.wid.substringBefore("@").substringBefore(":").substringBefore(".")
    // Cache so we can re-encrypt+resend to a single device on a retry receipt.
    recentSentDMs[id] = SentDM(to, type, msgPlaintext, dsmPlaintext)

    val recipientDevices = getUserDevices(listOf(to)).ifEmpty { listOf(to) }
    val ownDevices = getUserDevices(listOf("$ownUser@s.whatsapp.net"))
    val allDevices = (recipientDevices + ownDevices).distinct()
    // Log the actual device JIDs so device tests reveal whether the recipient is addressed by
    // phone (…@s.whatsapp.net) or LID (…@lid). A phone-addressed <message to=..> carrying LID
    // participant <to jid=…@lid> without addressing_mode="lid" can be dropped by the server.
    WhatsAppDiag.log(TAG, "send: fanout to ${allDevices.size} device(s) [${allDevices.joinToString()}] (msg to=$to)")

    val (encs, includeIdentity) = encryptForDevices(
        crypto, allDevices, ownUser, auth.wid, msgPlaintext, dsmPlaintext,
    )
    WhatsAppDiag.log(TAG, "send: encrypted for ${encs.size} device(s) includeIdentity=$includeIdentity")
    if (encs.isEmpty()) {
        Log.error(TAG, "No devices could be encrypted for $to")
        return null
    }
    val deviceIdentity = if (includeIdentity) accountDeviceIdentity() else null
    return WhatsAppProtocol.buildFanOutMessageNode(
        to = to,
        id = id,
        type = type,
        participantEncs = encs,
        includeDeviceIdentity = includeIdentity,
        deviceIdentity = deviceIdentity,
        extraEncAttrs = extraEncAttrs,
        messageAttrs = messageAttrs,
    )
}

/**
 * Build a group text message. Delegates to [buildEncryptedGroupMessageNode] with a plain
 * conversation proto.
 */
internal suspend fun WhatsAppClient.buildEncryptedGroupTextNode(
    groupJid: String,
    id: String,
    body: String): WhatsAppProtocol.Node? =
    buildEncryptedGroupMessageNode(groupJid, id, WhatsAppProtocol.buildConversationMessage(body))

/**
 * Build an arbitrary group message: sender-key encrypt the content (skmsg) and 1:1 fan out the
 * SenderKeyDistributionMessage to every member device. Mirrors whatsmeow send.go sendGroup.
 *
 * The skmsg wire is produced by the Rust `communicate_signal` sender-key implementation
 * ([RustWhatsAppCrypto.encryptGroup]/`createSenderKey`), NOT libsignal-client. Its SKDM +
 * skmsg round-trip is self-loopback-verified on-device via
 * [WhatsAppDiag.verifyGroupSenderKeyRoundTrip] (encrypt→decrypt through the same Rust path).
 * Live peer interop still cannot be validated because the test number is banned.
 */
internal suspend fun WhatsAppClient.buildEncryptedGroupMessageNode(
    groupJid: String,
    id: String,
    msg: WhatsAppE2EProto.Message,
    type: String = "text",
    extraEncAttrs: Map<String, String> = emptyMap(),
    messageAttrs: Map<String, String> = emptyMap(),
): WhatsAppProtocol.Node? {
    val auth = authData ?: return null
    val crypto = ensureE2E(auth) ?: return null
    val contentPadded = WhatsAppProtocol.padMessage(msg.toByteArray())

    val skmsgCiphertext = try {
        crypto.encryptGroup(groupJid, contentPadded)
    } catch (expected: Exception) {
        Log.error(TAG, "Group sender-key encrypt failed for $groupJid", expected)
        return null
    }
    val skdmBytes = crypto.createSenderKeyDistribution(groupJid)
    val skdmPlaintext = WhatsAppProtocol.padMessage(
        WhatsAppProtocol.senderKeyDistributionPlaintext(groupJid, skdmBytes)
    )

    // Meta AI / bots participate in a group via a special "hosted"/"bot" server and are NOT
    // part of the group's end-to-end sender-key encryption — you literally cannot send them
    // the group sender key, and including them aborts the whole send. The reference
    // (whatsmeow prepareMessageNode) deletes hosted/hosted.lid devices for group sends for
    // exactly this reason; the bot instead receives @mentions over its own message-secret
    // channel. Keep every real user (phone + LID); drop only bot/hosted servers.
    val participants = queryGroupParticipants(groupJid).filter { jid ->
        val server = jid.substringAfter("@", "")
        server != "bot" && server != "hosted" && server != "hosted.lid"
    }
    if (participants.isEmpty()) {
        Log.status(TAG, "No user participants resolved for group $groupJid; cannot fan out SKDM")
        return null
    }
    val devices = getUserDevices(participants).ifEmpty { participants }
    val ownUser = auth.wid.substringBefore("@").substringBefore(":").substringBefore(".")
    val (encs, includeIdentity) = encryptForDevices(
        crypto, devices, ownUser, auth.wid, skdmPlaintext, null,
    )
    if (encs.isEmpty()) {
        Log.error(TAG, "No group devices could be encrypted for $groupJid")
        return null
    }
    val skMsg = WhatsAppProtocol.Node(
        tag = "enc",
        attrs = mapOf("v" to "2", "type" to "skmsg"),
        data = skmsgCiphertext,
    )
    val deviceIdentity = if (includeIdentity) accountDeviceIdentity() else null
    return WhatsAppProtocol.buildFanOutMessageNode(
        to = groupJid,
        id = id,
        type = type,
        participantEncs = encs,
        includeDeviceIdentity = includeIdentity,
        deviceIdentity = deviceIdentity,
        extraEnc = skMsg,
        extraEncAttrs = extraEncAttrs,
        messageAttrs = messageAttrs,
    )
}

// The self-signed ADV device identity sent in the device-identity node for pkmsg sends.
// UNVERIFIED: stored at pair time; null until paired with the new pairing flow.
internal fun WhatsAppClient.accountDeviceIdentity(): ByteArray? {
    val b64 = authData?.accountSignedDeviceIdentity?.takeIf { it.isNotEmpty() } ?: return null
    return try { Base64.decode(b64, Base64.NO_WRAP) } catch (ignored: Exception) { null }
}

internal data class MediaUploadResult(
    val url: String,
    val directPath: String,
)

internal suspend fun WhatsAppClient.uploadMedia(
    encryptedData: ByteArray,
    mediaType: String,
    token: String,
): MediaUploadResult = withContext(Dispatchers.IO) {
    val conn = mediaConn() ?: throw IllegalStateException("media_conn unavailable (no upload host/auth)")
    val (host, auth) = conn
    // whatsmeow upload.go: mmsType "image"/"video"/"audio"/"document"; stickers use the image bucket.
    val mmsType = if (mediaType == "sticker") "image" else mediaType
    val encAuth = java.net.URLEncoder.encode(auth, "UTF-8")
    val encToken = java.net.URLEncoder.encode(token, "UTF-8")
    val uploadUrl = "https://$host/mms/$mmsType/$token?auth=$encAuth&token=$encToken"
    // WhatsApp's rupload endpoint expects POST (whatsmeow upload.go rawUpload). A PUT here
    // returns 404, which is why media send was failing. No Content-Type is sent, matching
    // the previous okhttp `toRequestBody(null)`.
    val response = NetworkClient.execute(
        uploadUrl,
        "POST",
        mapOf(
            "Origin" to "https://web.whatsapp.com",
            "Referer" to "https://web.whatsapp.com/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        ),
        encryptedData,
        connectTimeoutMs = MEDIA_CONNECT_TIMEOUT_MS,
        readTimeoutMs = MEDIA_READ_TIMEOUT_MS,
    )
    if (!response.isSuccess) {
        throw IllegalStateException("Media upload failed: HTTP ${response.status}")
    }
    if (response.bytes.isEmpty()) throw IllegalStateException("Empty upload response")
    val json = JSONObject(response.text)
    MediaUploadResult(
        url = json.getString("url"),
        directPath = json.getString("direct_path"),
    )
}

suspend fun WhatsAppClient.sendMedia(
    conversationId: String,
    bytes: ByteArray,
    mimeType: String,
    fileName: String?
): Boolean {
    if (stateMutable.value !is State.Connected) return false
    val ws = webSocket ?: return false
    val to = extractJid(conversationId) ?: return false
    val id = WhatsAppProtocol.generateMessageId(authData?.wid)

    val mediaType = mediaTypeFor(mimeType)
    val mediaKeyStr = mediaKeyFor(mediaType)

    pendingMessageIDs.add(id)
    if (bytes.size > MAX_FILE_SIZE) {
        Log.error(TAG, "File too large: ${bytes.size} bytes (max $MAX_FILE_SIZE)")
        pendingMessageIDs.remove(id)
        return false
    }
    return try {
        sendMediaPayload(ws, to, id, bytes, mimeType, fileName, mediaType, mediaKeyStr)
    } catch (expected: Exception) {
        WhatsAppDiag.log(TAG, "media: FAILED ${expected.message}")
        Log.error(TAG, "Failed to send media", expected)
        pendingMessageIDs.remove(id)
        false
    }
}

/** Media type string for a MIME type. */
private fun mediaTypeFor(mimeType: String): String = when {
    mimeType == "image/webp" -> "sticker"
    mimeType.startsWith("image/") -> "image"
    mimeType.startsWith("video/") -> "video"
    mimeType.startsWith("audio/") -> "audio"
    else -> "document"
}

/** Media key string for a media type. */
private fun mediaKeyFor(mediaType: String): String = when (mediaType) {
    "sticker" -> WhatsAppProtocol.MEDIA_KEY_STICKER
    "image" -> WhatsAppProtocol.MEDIA_KEY_IMAGE
    "video" -> WhatsAppProtocol.MEDIA_KEY_VIDEO
    "audio" -> WhatsAppProtocol.MEDIA_KEY_AUDIO
    else -> WhatsAppProtocol.MEDIA_KEY_DOCUMENT
}

/** Encrypt, upload, build, and send the media stanza. */
private suspend fun WhatsAppClient.sendMediaPayload(
    ws: com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket,
    to: String,
    id: String,
    bytes: ByteArray,
    mimeType: String,
    fileName: String?,
    mediaType: String,
    mediaKeyStr: String,
): Boolean {
    WhatsAppDiag.log(TAG, "media: start type=$mediaType size=${bytes.size} to=$to")
    val enc = WhatsAppProtocol.encryptMedia(bytes, mediaKeyStr)
    val token = Base64.encodeToString(enc.fileEncSha256, Base64.URL_SAFE or Base64.NO_WRAP)
    WhatsAppDiag.log(TAG, "media: encrypted, uploading…")
    val upload = uploadMedia(enc.encryptedData, mediaType, token)
    WhatsAppDiag.log(TAG, "media: uploaded url=${upload.url.take(LOG_URL_PREFIX_LENGTH)}")
    val proto = WhatsAppProtocol.buildMediaProto(
        upload.url, upload.directPath,
        enc.mediaKey, enc.fileSha256, enc.fileEncSha256, enc.fileLength,
        mimeType, fileName, mediaType
    )
    // Media must be Signal-encrypted + fanned out like any other message (an
    // unencrypted node is dropped) and carry stanza type "media" + a "mediatype"
    // enc attr. Ref whatsmeow send.go getTypeFromMessage / prepareMessageNode.
    val encAttrs = mapOf("mediatype" to mediaType)
    val node = if (to.contains("@g.us")) {
        buildEncryptedGroupMessageNode(to, id, proto, type = "media", extraEncAttrs = encAttrs)
    } else {
        buildEncryptedMessageNode(to, id, proto, "media", extraEncAttrs = encAttrs)
    } ?: run {
        WhatsAppDiag.log(TAG, "media: build node FAILED (encryption produced no recipients)")
        pendingMessageIDs.remove(id); return false
    }
    val sent = ws.send(WhatsAppProtocol.encodeNode(node))
    WhatsAppDiag.log(TAG, "media: stanza sent=$sent id=$id")
    if (!sent) pendingMessageIDs.remove(id)
    return sent
}
