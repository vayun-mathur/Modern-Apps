package com.vayunmathur.communicate.signal

import com.google.protobuf.ByteString
import com.vayunmathur.communicate.data.signal.SignalProtocol
import com.vayunmathur.communicate.data.signal.buildWsRequest
import com.vayunmathur.communicate.data.signal.buildWsResponseProto
import com.vayunmathur.communicate.data.signal.destAciFor
import com.vayunmathur.communicate.data.signal.encodeWebSocketRequest
import com.vayunmathur.communicate.data.signal.encodeWebSocketResponse
import com.vayunmathur.communicate.data.signal.encodeWsAck
import com.vayunmathur.communicate.data.signal.getRequestId
import com.vayunmathur.communicate.data.signal.groupConversationId
import com.vayunmathur.communicate.data.signal.groupIdentifierOf
import com.vayunmathur.communicate.data.signal.isQueueEmptySignal
import com.vayunmathur.communicate.data.signal.nextRequestId
import com.vayunmathur.communicate.data.signal.parseWebSocketMessage
import com.vayunmathur.communicate.data.signal.serverGuidBinaryFor
import com.vayunmathur.communicate.data.signal.serverGuidFor
import com.vayunmathur.communicate.data.signal.sourceAciFor
import com.vayunmathur.communicate.data.signal.timestampFor
import com.vayunmathur.communicate.data.signal.tryParseEnvelopeFromWsBytes
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketMessage
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketRequestMessage
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wave 1 (w6 slice 2/6): envelope + binary WebSocket framing + service-id/UUID helpers on
 * [SignalProtocol]. All pure JVM — envelopes are local protobuf builds, so every branch can be
 * asserted without a network or a database.
 */
@RunWith(RobolectricTestRunner::class)
class SignalProtocolWsTest {

    private val aci = "3f0e5a2c-1111-2222-3333-444455556666"

    private fun envelopeBytes(
        type: SignalServiceProtos.Envelope.Type = SignalServiceProtos.Envelope.Type.PREKEY_MESSAGE,
        sourceAci: String = aci,
        device: Int = 1,
        timestamp: Long = 1700000000000L,
        content: ByteArray = byteArrayOf(1, 2, 3),
    ): ByteArray {
        val b = SignalServiceProtos.Envelope.newBuilder()
            .setType(type)
            .setSourceServiceId(sourceAci)
            .setSourceDeviceId(device)
            .setClientTimestamp(timestamp)
            .setContent(ByteString.copyFrom(content))
        return b.build().toByteArray()
    }

    // ---- parseEnvelope ----

    @Test
    fun parseEnvelope_roundTripsAValidEnvelope() {
        val parsed = SignalProtocol.parseEnvelope(envelopeBytes())!!
        assertEquals(SignalServiceProtos.Envelope.Type.PREKEY_MESSAGE, parsed.type)
        assertEquals(aci, parsed.sourceServiceId)
    }

    @Test
    fun parseEnvelope_rejectsGarbage() {
        assertNull(SignalProtocol.parseEnvelope(byteArrayOf(0x7F, 0x3A, 0x55.toByte(), 0x11)))
    }

    // ---- request/message helpers ----

    private fun requestMessage(path: String, body: ByteArray, id: Long = 42L): WebSocketMessage =
        WebSocketMessage.newBuilder()
            .setType(WebSocketMessage.Type.REQUEST)
            .setRequest(
                WebSocketRequestMessage.newBuilder().setVerb("PUT").setPath(path).setId(id)
                    .setBody(ByteString.copyFrom(body)).build(),
            )
            .build()

    @Test
    fun parseEnvelopeFromRequest_returnsNullWithoutBody() {
        val req = WebSocketRequestMessage.newBuilder().setVerb("PUT").setPath("/v1/x").setId(1L).build()
        assertNull(SignalProtocol.parseEnvelopeFromRequest(req))
    }

    @Test
    fun parseEnvelopeFromRequest_decodesBody() {
        val req = WebSocketRequestMessage.newBuilder().setVerb("PUT").setPath("/v1/messages")
            .setId(1L).setBody(ByteString.copyFrom(envelopeBytes())).build()
        assertNotNull(SignalProtocol.parseEnvelopeFromRequest(req))
    }

    @Test
    fun parseEnvelopeFromWsMessage_needsARequest() {
        val responseOnly = WebSocketMessage.newBuilder().setType(WebSocketMessage.Type.RESPONSE).build()
        assertNull(SignalProtocol.parseEnvelopeFromWsMessage(responseOnly))
    }

    @Test
    fun parseEnvelopeFromWsMessage_decodesRequestBody() {
        val parsed = SignalProtocol.parseEnvelopeFromWsMessage(
            requestMessage("/api/v1/message", envelopeBytes()),
        )
        assertNotNull(parsed)
        assertEquals(aci, parsed!!.sourceServiceId)
    }

    // ---- toSignalEnvelope defaults ----

    @Test
    fun toSignalEnvelope_appliesDefaultsForMissingFields() {
        val minimal = SignalServiceProtos.Envelope.newBuilder()
            .setSourceServiceId(aci)
            .setClientTimestamp(99L)
            .build()
        val env = SignalProtocol.toSignalEnvelope(minimal)
        assertEquals(SignalServiceProtos.Envelope.Type.UNKNOWN, env.type)
        assertEquals(aci, env.sourceAci)
        assertEquals(1, env.sourceDevice)
        assertEquals(99L, env.timestamp)
        assertEquals(0, env.content.size)
        assertNull(env.serverGuid)
        assertNull(env.serverGuidBinary)
        assertEquals(0L, env.serverTimestamp)
        assertNull(env.destinationAci)
        assertFalse(env.story)
        assertTrue(env.urgent)
        assertFalse(env.isGroup)
    }

    @Test
    fun toSignalEnvelope_carriesStoryAndUrgentFlags() {
        val proto = SignalServiceProtos.Envelope.newBuilder()
            .setType(SignalServiceProtos.Envelope.Type.DOUBLE_RATCHET)
            .setSourceServiceId(aci)
            .setStory(true)
            .setUrgent(false)
            .build()
        val env = SignalProtocol.toSignalEnvelope(proto)
        assertTrue(env.story)
        assertFalse(env.urgent)
    }

    // ---- content classify/parse ----

    @Test
    fun classifyContent_routesEveryKnownBranch() {
        val ts = 1700000000123L
        val data = SignalServiceProtos.Content.newBuilder()
            .setDataMessage(SignalServiceProtos.DataMessage.newBuilder().setBody("hi").setTimestamp(ts))
            .build()
        val parsed = SignalProtocol.classifyContent(data)
        assertTrue(parsed is SignalProtocol.ParsedContent.Data)
        assertEquals("hi", (parsed as SignalProtocol.ParsedContent.Data).dataMessage.body)

        val receipt = SignalServiceProtos.Content.newBuilder()
            .setReceiptMessage(
                SignalServiceProtos.ReceiptMessage.newBuilder()
                    .setType(SignalServiceProtos.ReceiptMessage.Type.READ).addTimestamp(ts),
            ).build()
        assertTrue(SignalProtocol.classifyContent(receipt) is SignalProtocol.ParsedContent.Receipt)

        val typing = SignalServiceProtos.Content.newBuilder()
            .setTypingMessage(
                SignalServiceProtos.TypingMessage.newBuilder().setTimestamp(ts)
                    .setAction(SignalServiceProtos.TypingMessage.Action.STARTED),
            ).build()
        assertTrue(SignalProtocol.classifyContent(typing) is SignalProtocol.ParsedContent.Typing)

        val edit = SignalServiceProtos.Content.newBuilder()
            .setEditMessage(
                SignalServiceProtos.EditMessage.newBuilder().setTargetSentTimestamp(ts)
                    .setDataMessage(SignalServiceProtos.DataMessage.newBuilder().setBody("v2")),
            ).build()
        assertTrue(SignalProtocol.classifyContent(edit) is SignalProtocol.ParsedContent.Edit)

        val call = SignalServiceProtos.Content.newBuilder()
            .setCallMessage(SignalServiceProtos.CallMessage.newBuilder()).build()
        assertTrue(SignalProtocol.classifyContent(call) is SignalProtocol.ParsedContent.Call)

        val nullMsg = SignalServiceProtos.Content.newBuilder()
            .setNullMessage(SignalServiceProtos.NullMessage.newBuilder()).build()
        assertTrue(SignalProtocol.classifyContent(nullMsg) is SignalProtocol.ParsedContent.Null)

        val sync = SignalServiceProtos.Content.newBuilder()
            .setSyncMessage(SignalServiceProtos.SyncMessage.newBuilder()).build()
        assertTrue(SignalProtocol.classifyContent(sync) is SignalProtocol.ParsedContent.Sync)

        val story = SignalServiceProtos.Content.newBuilder()
            .setStoryMessage(SignalServiceProtos.StoryMessage.newBuilder()).build()
        assertTrue(SignalProtocol.classifyContent(story) is SignalProtocol.ParsedContent.Story)

        val err = SignalServiceProtos.Content.newBuilder()
            .setDecryptionErrorMessage(ByteString.copyFrom(byteArrayOf(9))).build()
        val dec = SignalProtocol.classifyContent(err)
        assertTrue(dec is SignalProtocol.ParsedContent.DecryptionError)
        assertTrue((dec as SignalProtocol.ParsedContent.DecryptionError).bytes.contentEquals(byteArrayOf(9)))

        val empty = SignalServiceProtos.Content.newBuilder().build()
        assertTrue(SignalProtocol.classifyContent(empty) is SignalProtocol.ParsedContent.Unknown)
    }

    @Test
    fun parseContent_rejectsEmptyAndGarbage() {
        assertNull(SignalProtocol.parseContent(ByteArray(0)))
        assertNull(SignalProtocol.parseContent(byteArrayOf(0x7F, 0x3A, 0x55.toByte())))
    }

    @Test
    fun parseAndClassifyContent_roundTrips() {
        val content = SignalProtocol.buildTypingContent(
            SignalServiceProtos.TypingMessage.Action.STOPPED,
            timestamp = 5L,
        )
        val parsed = SignalProtocol.parseAndClassifyContent(content.toByteArray())
        assertTrue(parsed is SignalProtocol.ParsedContent.Typing)
        assertNull(SignalProtocol.parseAndClassifyContent(ByteArray(0)))
    }

    // ---- builders ----

    @Test
    fun buildDataMessageContent_carriesBodyAndTimestamp() {
        val content = SignalProtocol.buildDataMessageContent("hello", timestamp = 123L)
        assertEquals("hello", content.dataMessage.body)
        assertEquals(123L, content.dataMessage.timestamp)
        assertFalse(content.dataMessage.hasGroupV2())
    }

    @Test
    fun buildDataMessageContent_withGroupAndRanges() {
        val masterKey = ByteArray(32) { it.toByte() }
        val change = byteArrayOf(7, 8)
        val range = SignalServiceProtos.BodyRange.newBuilder().setStart(0).setLength(2).build()
        val content = SignalProtocol.buildDataMessageContent(
            "g", timestamp = 1L, groupMasterKey = masterKey, groupRevision = 3,
            groupChange = change, bodyRanges = listOf(range),
        )
        assertTrue(content.dataMessage.groupV2.masterKey.toByteArray().contentEquals(masterKey))
        assertEquals(3, content.dataMessage.groupV2.revision)
        assertTrue(content.dataMessage.groupV2.groupChange.toByteArray().contentEquals(change))
        assertEquals(1, content.dataMessage.bodyRangesCount)
    }

    @Test
    fun buildReceiptContent_carriesTypeAndTimestamps() {
        val content = SignalProtocol.buildReceiptContent(
            SignalServiceProtos.ReceiptMessage.Type.DELIVERY,
            listOf(1L, 2L),
        )
        assertEquals(SignalServiceProtos.ReceiptMessage.Type.DELIVERY, content.receiptMessage.type)
        assertEquals(listOf(1L, 2L), content.receiptMessage.timestampList)
    }

    @Test
    fun buildTypingContent_withAndWithoutGroup() {
        val plain = SignalProtocol.buildTypingContent(
            SignalServiceProtos.TypingMessage.Action.STARTED, timestamp = 9L,
        )
        assertFalse(plain.typingMessage.hasGroupId())
        val grouped = SignalProtocol.buildTypingContent(
            SignalServiceProtos.TypingMessage.Action.STOPPED, timestamp = 9L,
            groupId = ByteArray(32) { 1 },
        )
        assertEquals(32, grouped.typingMessage.groupId.size())
    }

    @Test
    fun buildEditContent_carriesTargetAndReplacement() {
        val replacement = SignalServiceProtos.DataMessage.newBuilder().setBody("fixed").build()
        val content = SignalProtocol.buildEditContent(4242L, replacement)
        assertEquals(4242L, content.editMessage.targetSentTimestamp)
        assertEquals("fixed", content.editMessage.dataMessage.body)
    }

    @Test
    fun generateMessageId_isUniqueUuid() {
        val a = SignalProtocol.generateMessageId()
        val b = SignalProtocol.generateMessageId()
        assertTrue(a.isNotEmpty())
        assertTrue(b.isNotEmpty())
        assertTrue(a != b)
        java.util.UUID.fromString(a)
    }

    // ---- conversation ids ----

    @Test
    fun conversationIds_coverGroupStringAndFallback() {
        assertEquals("group:abc", SignalProtocol.toConversationId("", "abc"))
        assertEquals("unknown", SignalProtocol.toConversationId("", null as String?))
        assertEquals("unknown", SignalProtocol.toConversationId("", ""))
        assertEquals(aci, SignalProtocol.toConversationId(aci, null as String?))
        assertTrue(SignalProtocol.isGroupConversation("group:abc"))
        assertFalse(SignalProtocol.isGroupConversation(aci))
    }

    @Test
    fun bytesToAciString_handlesSixteenAndSeventeenBytes() {
        val uuid = java.util.UUID.fromString(aci)
        val buf = java.nio.ByteBuffer.allocate(16)
        buf.putLong(uuid.mostSignificantBits)
        buf.putLong(uuid.leastSignificantBits)
        val raw = buf.array()
        assertEquals(aci, SignalProtocol.run { raw.let { bytesToAciString(it) } })
        val prefixed = byteArrayOf(0x05) + raw
        assertEquals(aci, SignalProtocol.run { prefixed.let { bytesToAciString(it) } })
    }

    @Test
    fun bytesToAciString_fallsBackToUtf8OrTruncatedUuid() {
        assertEquals("bob", SignalProtocol.run { "bob".toByteArray().let { bytesToAciString(it) } })
        assertEquals("", SignalProtocol.run { ByteArray(0).let { bytesToAciString(it) } })
    }

    @Test
    fun bytesToUuidString_rejectsShortInput() {
        assertEquals("", SignalProtocol.run { ByteArray(4).let { bytesToUuidString(it) } })
    }

    // ---- ws framing helpers ----

    @Test
    fun wsRoundTrip_requestAndResponse() {
        val req = SignalProtocol.buildWsRequest("PUT", "/v1/keepalive", id = 7L)
        assertEquals("PUT", req.verb)
        val encoded = SignalProtocol.encodeWebSocketRequest(req)
        val decoded = SignalProtocol.parseWebSocketMessage(encoded)!!
        assertEquals(WebSocketMessage.Type.REQUEST, decoded.type)
        assertEquals(7L, decoded.request.id)

        val ack = SignalProtocol.encodeWsAck(7L)
        val ackMsg = SignalProtocol.parseWebSocketMessage(ack)!!
        assertEquals(WebSocketMessage.Type.RESPONSE, ackMsg.type)
        assertEquals(7L, ackMsg.response.id)
        assertEquals(200, ackMsg.response.status)
    }

    @Test
    fun buildWsResponseProto_carriesStatusAndMessage() {
        val resp = SignalProtocol.buildWsResponseProto(11L, 404, "nope")
        assertEquals(11L, resp.id)
        assertEquals(404, resp.status)
        assertEquals("nope", resp.message)
        assertEquals(11L, SignalProtocol.getRequestId(
            WebSocketMessage.newBuilder().setType(WebSocketMessage.Type.REQUEST)
                .setRequest(
                    WebSocketRequestMessage.newBuilder().setVerb("PUT").setPath("/x").setId(11L).build(),
                ).build(),
        ))
        assertNull(SignalProtocol.getRequestId(ByteArray(0)))
        assertNull(SignalProtocol.getRequestId(
            WebSocketMessage.newBuilder().setType(WebSocketMessage.Type.RESPONSE).build().toByteArray(),
        ))
    }

    @Test
    fun nextRequestId_isPositive() {
        assertTrue(SignalProtocol.nextRequestId() > 0)
    }

    @Test
    fun parseWebSocketMessage_rejectsGarbage() {
        assertNull(SignalProtocol.parseWebSocketMessage(byteArrayOf(0x7F, 0x3A, 0x55.toByte())))
    }

    // ---- queue/ignore + group id + service ids ----

    @Test
    fun isQueueEmptySignal_matchesOnlyQueuePaths() {
        fun ws(path: String): ByteArray = WebSocketMessage.newBuilder()
            .setType(WebSocketMessage.Type.REQUEST)
            .setRequest(
                WebSocketRequestMessage.newBuilder().setVerb("PUT").setPath(path).setId(1L)
                    .setBody(ByteString.copyFrom(envelopeBytes())).build(),
            ).build().toByteArray()
        assertTrue(SignalProtocol.isQueueEmptySignal(ws("/api/v1/queue/empty")))
        assertFalse(SignalProtocol.isQueueEmptySignal(ws("/api/v1/message")))
        assertFalse(SignalProtocol.isQueueEmptySignal(ByteArray(0)))
    }

    @Test
    fun tryParseEnvelopeFromWsBytes_filtersControlFrames() {
        fun ws(path: String, withBody: Boolean): ByteArray {
            val rb = WebSocketRequestMessage.newBuilder().setVerb("PUT").setPath(path).setId(1L)
            if (withBody) rb.setBody(ByteString.copyFrom(envelopeBytes()))
            return WebSocketMessage.newBuilder().setType(WebSocketMessage.Type.REQUEST)
                .setRequest(rb).build().toByteArray()
        }
        // Queue-empty is ignorable even with a body-shaped path.
        assertNull(SignalProtocol.tryParseEnvelopeFromWsBytes(ws("/api/v1/queue/empty", true)))
        // Keepalive is ignorable.
        assertNull(SignalProtocol.tryParseEnvelopeFromWsBytes(ws("/v1/keepalive", false)))
        // Bodyless non-message is ignorable.
        assertNull(SignalProtocol.tryParseEnvelopeFromWsBytes(ws("/v1/other", false)))
        // Real message parses.
        assertNotNull(SignalProtocol.tryParseEnvelopeFromWsBytes(ws("/api/v1/message", true)))
        // Garbage bytes give null.
        assertNull(SignalProtocol.tryParseEnvelopeFromWsBytes(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun groupConversationId_roundTrips() {
        val raw = ByteArray(32) { (it * 3).toByte() }
        val cid = SignalProtocol.groupConversationId(raw)
        assertTrue(cid.startsWith("group:"))
        assertTrue(SignalProtocol.groupIdentifierOf(cid)!!.contentEquals(raw))
        assertNull(SignalProtocol.groupIdentifierOf(aci))
    }

    @Test
    fun sourceAndDestAci_preferBinaryServiceIds() {
        val uuid = java.util.UUID.fromString(aci)
        val buf = java.nio.ByteBuffer.allocate(16)
        buf.putLong(uuid.mostSignificantBits)
        buf.putLong(uuid.leastSignificantBits)
        val raw = buf.array()
        val withBinary = SignalServiceProtos.Envelope.newBuilder()
            .setSourceServiceIdBinary(ByteString.copyFrom(raw))
            .setDestinationServiceIdBinary(ByteString.copyFrom(raw))
            .build()
        assertEquals(aci, SignalProtocol.sourceAciFor(withBinary))
        assertEquals(aci, SignalProtocol.destAciFor(withBinary))
        val withStrings = SignalServiceProtos.Envelope.newBuilder()
            .setSourceServiceId(aci)
            .setDestinationServiceId(aci)
            .build()
        assertEquals(aci, SignalProtocol.sourceAciFor(withStrings))
        assertEquals(aci, SignalProtocol.destAciFor(withStrings))
        val empty = SignalServiceProtos.Envelope.newBuilder().build()
        assertEquals("", SignalProtocol.sourceAciFor(empty))
        assertNull(SignalProtocol.destAciFor(empty))
    }

    @Test
    fun envelopeTimestampsAndGuids_readWhenPresent() {
        val guid = "9d65a1c0-1234-5678-9abc-def012345678"
        val proto = SignalServiceProtos.Envelope.newBuilder()
            .setClientTimestamp(12345L)
            .setServerGuid(guid)
            .setServerGuidBinary(ByteString.copyFrom(byteArrayOf(1, 2)))
            .build()
        assertEquals(12345L, SignalProtocol.timestampFor(proto))
        assertEquals(guid, SignalProtocol.serverGuidFor(proto))
        assertTrue(SignalProtocol.serverGuidBinaryFor(proto)!!.contentEquals(byteArrayOf(1, 2)))
        val bare = SignalServiceProtos.Envelope.newBuilder().build()
        assertEquals(0L, SignalProtocol.timestampFor(bare))
        assertNull(SignalProtocol.serverGuidFor(bare))
        assertNull(SignalProtocol.serverGuidBinaryFor(bare))
    }

    // ---- deprecated JSON frame helpers (kept for compat) ----

    @Test
    fun deprecatedJsonHelpers_coverTypeAndBody() {
        val frame = SignalProtocol.parseWsFrame(
            """{"type":"REQUEST","verb":"PUT","path":"/v1/x","id":"9","status":200,"body":"AQID"}""",
        )!!
        assertEquals("REQUEST", frame.type)
        assertEquals("PUT", frame.verb)
        assertEquals("/v1/x", frame.path)
        assertEquals("9", frame.id)
        assertEquals(200, frame.status)
        assertTrue(frame.body!!.contentEquals(byteArrayOf(1, 2, 3)))

        val noBody = SignalProtocol.parseWsFrame("""{"type":"RESPONSE"}""")!!
        assertNull(noBody.body)
        assertNull(noBody.verb)

        assertNull(SignalProtocol.parseWsFrame("not json"))
        val resp = SignalProtocol.buildWsResponse("abc", 201)
        assertTrue(resp.contains("abc"))
        val encoded = SignalProtocol.encodeWebSocketResponse(
            signal.proto.chat_websocket.SignalChatWebsocket.WebSocketResponseMessage.newBuilder()
                .setId(3L).setStatus(200).build(),
        )
        assertTrue(encoded.isNotEmpty())
    }

    @Test
    fun stripMessagePadding_handlesMalformedAndLeadingTerminator() {
        // Malformed (non-zero, non-terminator from the end) returns input unchanged.
        val malformed = byteArrayOf(1, 2, 3, 4)
        assertTrue(SignalProtocol.stripMessagePadding(malformed).contentEquals(malformed))
        // All-zero input has no terminator: returns empty.
        assertEquals(0, SignalProtocol.stripMessagePadding(ByteArray(4)).size)
    }
}
