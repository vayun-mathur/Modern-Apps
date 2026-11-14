package com.vayunmathur.communicate.data.signal

import com.vayunmathur.library.log.Log
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketMessage
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketRequestMessage
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketResponseMessage
import java.util.UUID

/**
 * Frame encode/decode and Envelope/Content handling for the Signal primary client.
 *
 * Real Signal wire (grounded in C:\Users\Vayun\signal-ref):
 *  - libsignal/rust/net/src/proto/chat_websocket.proto (WebSocketMessage, uint64 id)
 *  - lib/libsignal-service/src/main/protowire/SignalService.proto
 *    (Envelope, Content, DataMessage, ReceiptMessage, TypingMessage, etc.)
 *  - libsignal/rust/protocol/src/proto/{wire,sealed_sender}.proto (PQXDH wire)
 *
 * Inbound: WebSocketMessage (binary protobuf) -> WebSocketRequestMessage.body contains
 *          Envelope bytes (Envelope.content is a serialized CiphertextMessage; libsignal owns its
 *          version framing, so nothing is stripped from it here).
 * Outbound: Build Content -> pad -> encrypt via SignalE2E -> PUT /v1/messages/{aci}.
 */
object SignalProtocol {
    internal const val TAG = "SignalProtocol"

    private const val PADDING_BLOCK_SIZE = 80
    private const val TERMINATOR = 0x80.toByte()
    private const val PADDING_ZERO = 0x00.toByte()
    private const val UUID_SIZE = 16
    private const val UUID_PREFIXED_SIZE = 17
    private const val LONG_BYTES = 8
    private const val BYTE_MASK = 0xFFL
    private const val BYTE_BITS = 8

    /** A decrypted Signal service envelope: sender ACI/device, timestamp and plaintext content bytes. */
    data class SignalEnvelope(
        val type: SignalServiceProtos.Envelope.Type,
        val sourceAci: String,
        val sourceDevice: Int,
        val timestamp: Long,
        val content: ByteArray,
        val serverGuid: String? = null,
        val serverGuidBinary: ByteArray? = null,
        val serverTimestamp: Long = 0L,
        val destinationAci: String? = null,
        val isGroup: Boolean = false,
        val groupId: ByteArray? = null,
        val story: Boolean = false,
        val urgent: Boolean = true,
        val rawEnvelope: SignalServiceProtos.Envelope? = null,
    )

    /** The decoded payload of a Signal envelope, split by Signal protocol message kind. */
    sealed interface ParsedContent {
        /** A normal data message (text, attachments, group traffic). */
        data class Data(
            val dataMessage: SignalServiceProtos.DataMessage,
            val raw: SignalServiceProtos.Content,
        ) : ParsedContent
        /** A delivery/read receipt. */
        data class Receipt(val receiptMessage: SignalServiceProtos.ReceiptMessage) : ParsedContent
        /** A typing indicator. */
        data class Typing(val typingMessage: SignalServiceProtos.TypingMessage) : ParsedContent
        /** An edit of a previously sent message. */
        data class Edit(val editMessage: SignalServiceProtos.EditMessage) : ParsedContent
        /** A call signalling message. */
        data class Call(val callMessage: SignalServiceProtos.CallMessage) : ParsedContent
        /** A null/padding message, used to keep sealed-sender traffic sizes uniform. */
        data class Null(val nullMessage: SignalServiceProtos.NullMessage) : ParsedContent
        /** A sync message from our own other device. */
        data class Sync(val syncMessage: SignalServiceProtos.SyncMessage) : ParsedContent
        /** A story message. */
        data class Story(val storyMessage: SignalServiceProtos.StoryMessage) : ParsedContent
        /** Content that failed to decrypt; carries the raw bytes for diagnostics. */
        data class DecryptionError(val bytes: ByteArray) : ParsedContent
        /** A content kind this client does not model yet; carries the raw proto. */
        data class Unknown(val raw: SignalServiceProtos.Content) : ParsedContent
    }

    fun parseEnvelope(bytes: ByteArray): SignalServiceProtos.Envelope? = try {
        SignalServiceProtos.Envelope.parseFrom(bytes)
    } catch (e: InvalidProtocolBufferException) {
        Log.status(TAG, "parseEnvelope failed: ${e.message}")
        null
    }

    fun parseEnvelopeFromRequest(request: WebSocketRequestMessage): SignalServiceProtos.Envelope? {
        if (!request.hasBody()) return null
        return parseEnvelope(request.body.toByteArray())
    }

    fun parseEnvelopeFromWsMessage(wsMessage: WebSocketMessage): SignalServiceProtos.Envelope? {
        if (!wsMessage.hasRequest()) return null
        return parseEnvelopeFromRequest(wsMessage.request)
    }

    fun toSignalEnvelope(envelope: SignalServiceProtos.Envelope): SignalEnvelope {
        return SignalEnvelope(
            type = if (envelope.hasType()) envelope.type else SignalServiceProtos.Envelope.Type.UNKNOWN,
            sourceAci = sourceAciFor(envelope),
            sourceDevice = if (envelope.hasSourceDeviceId()) envelope.sourceDeviceId else 1,
            timestamp = timestampFor(envelope),
            content = if (envelope.hasContent()) envelope.content.toByteArray() else ByteArray(0),
            serverGuid = serverGuidFor(envelope),
            serverGuidBinary = serverGuidBinaryFor(envelope),
            serverTimestamp = if (envelope.hasServerTimestamp()) envelope.serverTimestamp else 0L,
            destinationAci = destAciFor(envelope),
            story = if (envelope.hasStory()) envelope.story else false,
            urgent = if (envelope.hasUrgent()) envelope.urgent else true,
            rawEnvelope = envelope,
        )
    }

    /**
     * Pad to a multiple of [PADDING_BLOCK_SIZE] with a trailing `0x80` terminator followed by zeroes.
     * Applied to the serialized Content before encryption. Port of the official client's
     * `PushTransportDetails.getPaddedMessageBody`.
     */
    fun padMessageBody(body: ByteArray): ByteArray {
        // The +1/-1 leaves the cipher room for its own single padding byte; without it the cipher
        // adds a full extra block.
        val padded = ByteArray(paddedLength(body.size + 1) - 1)
        body.copyInto(padded)
        padded[body.size] = TERMINATOR
        return padded
    }

    /**
     * Strip [padMessageBody]'s terminator and trailing zeroes from a decrypted plaintext. Returns the
     * input unchanged when the padding is malformed, matching the official client rather than
     * guessing at a length.
     */
    fun stripMessagePadding(padded: ByteArray): ByteArray {
        var paddingStart = 0
        for (i in padded.indices.reversed()) {
            if (padded[i] == TERMINATOR) {
                paddingStart = i
                break
            }
            if (padded[i] != PADDING_ZERO) {
                Log.status(TAG, "malformed padding, leaving message unstripped")
                return padded
            }
        }
        return padded.copyOfRange(0, paddingStart)
    }

    private fun paddedLength(length: Int): Int {
        val withTerminator = length + 1
        val blocks = (withTerminator + PADDING_BLOCK_SIZE - 1) / PADDING_BLOCK_SIZE
        return blocks * PADDING_BLOCK_SIZE
    }

    fun parseContent(plaintext: ByteArray): SignalServiceProtos.Content? = try {
        if (plaintext.isEmpty()) return null
        SignalServiceProtos.Content.parseFrom(plaintext)
    } catch (e: InvalidProtocolBufferException) {
        Log.status(TAG, "parseContent failed: ${e.message}")
        null
    }

    /**
     * `PLAINTEXT_CONTENT` is the one unencrypted envelope type and may only carry a
     * `DecryptionErrorMessage`. Official clients drop anything else, so accepting a plaintext
     * DataMessage would be a spoofing hole rather than a compatibility feature.
     */
    fun isValidPlaintextContent(content: SignalServiceProtos.Content): Boolean =
        content.hasDecryptionErrorMessage() &&
            !content.hasDataMessage() &&
            !content.hasSyncMessage() &&
            !content.hasCallMessage() &&
            !content.hasNullMessage() &&
            !content.hasReceiptMessage() &&
            !content.hasTypingMessage() &&
            !content.hasStoryMessage() &&
            !content.hasEditMessage()

    fun classifyContent(content: SignalServiceProtos.Content): ParsedContent {
        return when {
            content.hasDataMessage() -> ParsedContent.Data(content.dataMessage, content)
            content.hasReceiptMessage() -> ParsedContent.Receipt(content.receiptMessage)
            content.hasTypingMessage() -> ParsedContent.Typing(content.typingMessage)
            content.hasEditMessage() -> ParsedContent.Edit(content.editMessage)
            content.hasCallMessage() -> ParsedContent.Call(content.callMessage)
            content.hasNullMessage() -> ParsedContent.Null(content.nullMessage)
            content.hasSyncMessage() -> ParsedContent.Sync(content.syncMessage)
            content.hasStoryMessage() -> ParsedContent.Story(content.storyMessage)
            content.hasDecryptionErrorMessage() -> {
                ParsedContent.DecryptionError(content.decryptionErrorMessage.toByteArray())
            }
            else -> ParsedContent.Unknown(content)
        }
    }

    fun parseAndClassifyContent(plaintext: ByteArray): ParsedContent? {
        val content = parseContent(plaintext) ?: return null
        return classifyContent(content)
    }

    fun buildDataMessageContent(
        body: String,
        timestamp: Long = System.currentTimeMillis(),
        groupMasterKey: ByteArray? = null,
        groupRevision: Int? = null,
        groupChange: ByteArray? = null,
        bodyRanges: List<SignalServiceProtos.BodyRange> = emptyList(),
    ): SignalServiceProtos.Content {
        val dmBuilder = SignalServiceProtos.DataMessage.newBuilder().setBody(body).setTimestamp(timestamp)
        if (groupMasterKey != null) {
            val g = SignalServiceProtos.GroupContextV2.newBuilder().setMasterKey(ByteString.copyFrom(groupMasterKey))
            if (groupRevision != null) g.setRevision(groupRevision)
            if (groupChange != null) g.setGroupChange(ByteString.copyFrom(groupChange))
            dmBuilder.setGroupV2(g)
        }
        if (bodyRanges.isNotEmpty()) dmBuilder.addAllBodyRanges(bodyRanges)
        return SignalServiceProtos.Content.newBuilder().setDataMessage(dmBuilder).build()
    }

    fun buildReceiptContent(
        type: SignalServiceProtos.ReceiptMessage.Type,
        timestamps: List<Long>,
    ): SignalServiceProtos.Content {
        val receipt = SignalServiceProtos.ReceiptMessage.newBuilder().setType(type)
        timestamps.forEach { receipt.addTimestamp(it) }
        return SignalServiceProtos.Content.newBuilder().setReceiptMessage(receipt).build()
    }

    fun buildTypingContent(
        action: SignalServiceProtos.TypingMessage.Action,
        timestamp: Long = System.currentTimeMillis(),
        groupId: ByteArray? = null,
    ): SignalServiceProtos.Content {
        val typing = SignalServiceProtos.TypingMessage.newBuilder().setTimestamp(timestamp).setAction(action)
        if (groupId != null) typing.setGroupId(ByteString.copyFrom(groupId))
        return SignalServiceProtos.Content.newBuilder().setTypingMessage(typing).build()
    }

    fun buildEditContent(
        targetSentTimestamp: Long,
        newDataMessage: SignalServiceProtos.DataMessage,
    ): SignalServiceProtos.Content {
        val edit = SignalServiceProtos.EditMessage.newBuilder()
            .setTargetSentTimestamp(targetSentTimestamp)
            .setDataMessage(newDataMessage)
        return SignalServiceProtos.Content.newBuilder().setEditMessage(edit).build()
    }

    fun generateMessageId(): String = UUID.randomUUID().toString()

    /**
     * Conversation id for a message. Groups are keyed by their derived public identifier — never by the
     * master key, which is secret material that must not end up in a database key or a log line.
     */
    fun toConversationId(sourceAci: String, groupMasterKey: ByteArray?): String {
        return when {
            groupMasterKey != null -> {
                val id = runCatching { SignalGroups.groupIdFromMasterKey(groupMasterKey) }.getOrElse { expected ->
                    Log.status(TAG, "could not derive a group identifier: ${expected.message}")
                    return if (sourceAci.isNotEmpty()) sourceAci else "unknown"
                }
                "$GROUP_PREFIX$id"
            }
            sourceAci.isNotEmpty() -> sourceAci
            else -> "unknown"
        }
    }

    fun toConversationId(sourceAci: String, groupId: String?): String = when {
        !groupId.isNullOrEmpty() -> "$GROUP_PREFIX$groupId"
        sourceAci.isNotEmpty() -> sourceAci
        else -> "unknown"
    }

    const val GROUP_PREFIX = "group:"

    fun isGroupConversation(conversationId: String): Boolean = conversationId.startsWith(GROUP_PREFIX)

    /** The conversation id for a raw 32-byte group identifier, the inverse of what calling hands back. */
    internal fun bytesToAciString(bytes: ByteArray): String {
        if (bytes.size == UUID_SIZE) return bytesToUuidString(bytes)
        if (bytes.size == UUID_PREFIXED_SIZE) {
            return bytesToUuidString(bytes.copyOfRange(1, UUID_PREFIXED_SIZE))
        }
        return try {
            String(bytes, Charsets.UTF_8)
                .takeIf { it.isNotBlank() }
                ?: bytesToUuidString(bytes.take(UUID_SIZE).toByteArray())
        } catch (_: Exception) { "" }
    }

    internal fun bytesToUuidString(bytes: ByteArray): String {
        if (bytes.size < UUID_SIZE) return ""
        val b = if (bytes.size > UUID_SIZE) bytes.copyOfRange(0, UUID_SIZE) else bytes
        return try {
            UUID(bytesToLong(b, 0), bytesToLong(b, LONG_BYTES)).toString()
        } catch (_: Exception) { "" }
    }

    /** Big-endian 8 bytes at [offset] as a Long. */
    private fun bytesToLong(b: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until LONG_BYTES) {
            v = (v shl BYTE_BITS) or (b[offset + i].toLong() and BYTE_MASK)
        }
        return v
    }

    /** Legacy text WebSocket frame; superseded by the binary WebSocket message helpers. */
    @Deprecated("Use binary WebSocketMessage helpers")
    data class WsFrame(
        val type: String,
        val verb: String?,
        val path: String?,
        val id: String?,
        val status: Int?,
        val body: ByteArray?,
        val raw: String,
    )

    @Deprecated("Use parseWebSocketMessage(bytes)")
    fun parseWsFrame(text: String): WsFrame? {
        val obj = runCatching { org.json.JSONObject(text) }.getOrElse { expected ->
            Log.status(TAG, "parseWsFrame failed: ${expected.message}")
            return null
        }
        val type = obj.optString("type", "")
        val bodyB64 = obj.optString("body", "")
        val body = if (bodyB64.isNotEmpty()) {
            runCatching {
                android.util.Base64.decode(bodyB64, android.util.Base64.NO_WRAP)
            }.getOrNull()
        } else {
            null
        }
        return WsFrame(
            type = type,
            verb = if (obj.has("verb")) obj.optString("verb") else null,
            path = if (obj.has("path")) obj.optString("path") else null,
            id = if (obj.has("id")) obj.optString("id") else null,
            status = if (obj.has("status")) obj.optInt("status") else null,
            body = body,
            raw = text,
        )
    }

    @Deprecated("Use encodeWsAck(id: Long)")
    fun buildWsResponse(id: String, status: Int = 200): String {
        val o = org.json.JSONObject()
        o.put("type", "RESPONSE")
        o.put("id", id)
        o.put("status", status)
        return o.toString()
    }
}
