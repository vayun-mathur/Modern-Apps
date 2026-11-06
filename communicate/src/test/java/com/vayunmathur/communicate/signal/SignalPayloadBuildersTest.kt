package com.vayunmathur.communicate.signal

import com.google.protobuf.ByteString
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.communicate.data.signal.transport.SignalPayload
import com.vayunmathur.communicate.data.signal.transport.applyDataOptionals
import com.vayunmathur.communicate.data.signal.transport.applyGroupV2
import com.vayunmathur.communicate.data.signal.transport.buildWebSocketMessageForRequest
import com.vayunmathur.communicate.data.signal.transport.buildWebSocketMessageForResponse
import com.vayunmathur.communicate.data.signal.transport.buildWebSocketRequestMessage
import com.vayunmathur.communicate.data.signal.transport.buildWebSocketResponseMessage
import com.vayunmathur.communicate.data.signal.transport.encodeWebSocketRequest
import com.vayunmathur.communicate.data.signal.transport.encodeWebSocketResponse
import com.vayunmathur.communicate.data.signal.transport.nextRequestId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketMessage
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wave 1 (w6 slice 2/6): [SignalPayload] builders — PUT bodies, Content/DataMessage variants,
 * WebSocket framing, account attributes, shared contacts. Pure JVM, no network.
 */
@RunWith(RobolectricTestRunner::class)
class SignalPayloadBuildersTest {

    // ---- PUT body / request ----

    @Test
    fun putBody_defaultsOnlineFalseUrgentTrue() {
        val body = SignalPayload.buildPutMessagesBody("aci", emptyList(), timestamp = 10L)
        val root = Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
        assertEquals("aci", root["destination"]?.jsonPrimitive?.content)
        assertEquals("false", root["online"]?.jsonPrimitive?.content)
        assertEquals("true", root["urgent"]?.jsonPrimitive?.content)
        assertEquals(0, root["messages"]?.jsonArray?.size)
    }

    @Test
    fun putMessagesRequest_carriesVerbPathAndHeaders() {
        val req = SignalPayload.buildPutMessagesRequest("aci-9", byteArrayOf(1), story = true, id = 5L)
        assertEquals("PUT", req.verb)
        assertEquals("/v1/messages/aci-9?story=true", req.path)
        assertEquals(5L, req.id)
        assertTrue(req.headersList.isNotEmpty())
    }

    @Test
    fun keepaliveRequest_targetsKeepalivePath() {
        val req = SignalPayload.buildKeepaliveRequest(id = 3L)
        assertEquals("/v1/keepalive", req.path)
        assertEquals(3L, req.id)
    }

    // ---- DataMessage variants ----

    @Test
    fun dataMessage_derivesProtocolVersionFromContent() {
        val plain = SignalPayload.buildDataMessage("hi", timestamp = 1L)
        assertEquals(0, plain.requiredProtocolVersion)

        val reaction = SignalPayload.buildReaction("👍", false, targetSentTimestamp = 1L)
        val withReaction = SignalPayload.buildDataMessage("x", timestamp = 1L, reaction = reaction)
        assertEquals(4, withReaction.requiredProtocolVersion)

        val poll = SignalPayload.buildPollCreate("q?", listOf("a"))
        val withPoll = SignalPayload.buildDataMessage("q?", timestamp = 1L, pollCreate = poll)
        assertEquals(8, withPoll.requiredProtocolVersion)

        // Explicit override wins.
        val forced = SignalPayload.buildDataMessage("x", timestamp = 1L, requiredProtocolVersion = 2)
        assertEquals(2, forced.requiredProtocolVersion)
    }

    @Test
    fun dataMessage_appliesGroupV2AndOptionals() {
        val masterKey = ByteArray(32) { 9 }
        val dm = SignalPayload.buildDataMessage(
            "body", timestamp = 2L,
            groupV2MasterKey = masterKey, groupV2Revision = 4, groupV2Change = byteArrayOf(1),
            bodyRanges = listOf(SignalPayload.buildBodyRange(0, 2)),
            expireTimer = 60,
            profileKey = ByteArray(32) { 3 },
        )
        assertTrue(dm.groupV2.masterKey.toByteArray().contentEquals(masterKey))
        assertEquals(4, dm.groupV2.revision)
        assertEquals(1, dm.bodyRangesCount)
        assertEquals(60, dm.expireTimer)
        assertEquals(32, dm.profileKey.size())
    }

    @Test
    fun sharedContact_refusesEmptyCard() {
        assertNull(SignalPayload.buildSharedContact("", phoneNumbers = emptyList()))
        // Blank numbers are skipped, but a non-empty phone list means "has numbers",
        // so a blank-named card with a (blank) number entry still builds with no numbers.
        val blankNumberCard = SignalPayload.buildSharedContact("   ", phoneNumbers = listOf("" to null))!!
        assertEquals(0, blankNumberCard.numberCount)
    }

    @Test
    fun sharedContact_carriesNameNumbersEmailsAndOrg() {
        val contact = SignalPayload.buildSharedContact(
            "Ada", familyName = "Lovelace",
            phoneNumbers = listOf("+15551234567" to null, "+15557654321" to "work", "" to null),
            emails = listOf("ada@example.com", ""),
            organization = "Analytical Engines",
        )!!
        assertEquals("Ada", contact.name.givenName)
        assertEquals("Lovelace", contact.name.familyName)
        assertEquals(2, contact.numberCount)
        assertEquals(
            SignalServiceProtos.DataMessage.Contact.Phone.Type.MOBILE,
            contact.getNumber(0).type,
        )
        assertEquals(
            SignalServiceProtos.DataMessage.Contact.Phone.Type.CUSTOM,
            contact.getNumber(1).type,
        )
        assertEquals("work", contact.getNumber(1).label)
        assertEquals(1, contact.emailCount)
        assertEquals(
            SignalServiceProtos.DataMessage.Contact.Email.Type.HOME,
            contact.getEmail(0).type,
        )
        assertEquals("Analytical Engines", contact.organization)
    }

    @Test
    fun sharedContact_nameOnlyIsAccepted() {
        // A name with no numbers still builds (only both-empty is refused).
        assertNotNull(SignalPayload.buildSharedContact("Solo"))
    }

    @Test
    fun contentWithDataMessage_carriesSenderKeyAndPniSignature() {
        val dm = SignalPayload.buildDataMessage("hi", timestamp = 1L)
        val plain = SignalPayload.buildContentWithDataMessage(dm)
        assertTrue(plain.hasDataMessage())

        val skdm = byteArrayOf(5, 6)
        val withSkdm = SignalPayload.buildContentWithDataMessage(dm, senderKeyDistributionMessage = skdm)
        assertTrue(withSkdm.senderKeyDistributionMessage.toByteArray().contentEquals(skdm))

        val pni = SignalServiceProtos.PniSignatureMessage.newBuilder()
            .setPni(ByteString.copyFrom(ByteArray(16) { 1 }))
            .setSignature(ByteString.copyFrom(byteArrayOf(2))).build()
        val withPni = SignalPayload.buildContentWithDataMessage(dm, pniSignatureMessage = pni)
        assertTrue(withPni.hasPniSignatureMessage())
    }

    @Test
    fun receiptAndTypingAndEdit_builders() {
        val receipt = SignalPayload.buildContentForReceipt(
            SignalServiceProtos.ReceiptMessage.Type.VIEWED, listOf(7L),
        )
        assertEquals(SignalServiceProtos.ReceiptMessage.Type.VIEWED, receipt.receiptMessage.type)
        assertEquals(listOf(7L), receipt.receiptMessage.timestampList)

        val typing = SignalPayload.buildContentForTyping(
            8L, SignalServiceProtos.TypingMessage.Action.STARTED, groupId = byteArrayOf(1, 2),
        )
        assertEquals(8L, typing.typingMessage.timestamp)
        assertEquals(2, typing.typingMessage.groupId.size())

        val dm = SignalPayload.buildDataMessage("new", timestamp = 3L)
        val edit = SignalPayload.buildContentForEdit(99L, dm)
        assertEquals(99L, edit.editMessage.targetSentTimestamp)
    }

    @Test
    fun reactionBuilder_withAciStringOrBinary() {
        val byString = SignalPayload.buildReaction("❤", false, targetAuthorAci = "some-aci", targetSentTimestamp = 1L)
        assertEquals("some-aci", byString.targetAuthorAci)
        val byBinary = SignalPayload.buildReaction(
            "❤", true, targetAuthorAciBinary = byteArrayOf(1), targetSentTimestamp = 2L,
        )
        assertTrue(byBinary.remove)
        assertEquals(1, byBinary.targetAuthorAciBinary.size())
    }

    @Test
    fun deleteAndBodyRangeAndGroupContext() {
        assertEquals(55L, SignalPayload.buildDelete(55L).targetSentTimestamp)
        // mentionAci and style share a oneof: setting both keeps the last one set.
        val mention = SignalPayload.buildBodyRange(1, 3, mentionAci = "aci")
        assertEquals("aci", mention.mentionAci)
        val styled = SignalPayload.buildBodyRange(
            1, 3, style = SignalServiceProtos.BodyRange.Style.BOLD,
        )
        assertEquals(SignalServiceProtos.BodyRange.Style.BOLD, styled.style)
        val binary = SignalPayload.buildBodyRange(1, 3, mentionAciBinary = byteArrayOf(4))
        assertTrue(binary.mentionAciBinary.toByteArray().contentEquals(byteArrayOf(4)))

        val ctx = SignalPayload.buildGroupContextV2(ByteArray(32) { 5 }, revision = 6)
        assertEquals(6, ctx.revision)
        val ctxChange = SignalPayload.buildGroupContextV2(ByteArray(32) { 5 }, 6, groupChange = byteArrayOf(9))
        assertTrue(ctxChange.hasGroupChange())
    }

    @Test
    fun pollBuilders_carryQuestionOptionsAndVote() {
        val create = SignalPayload.buildPollCreate("lunch?", listOf("a", "b"), allowMultiple = true)
        assertEquals("lunch?", create.question)
        assertEquals(listOf("a", "b"), create.optionsList)
        assertTrue(create.allowMultiple)

        val vote = SignalPayload.buildPollVote(
            targetAuthorAciBinary = byteArrayOf(1, 2), targetSentTimestamp = 10L,
            optionIndexes = listOf(0), voteCount = 1,
        )
        assertEquals(10L, vote.targetSentTimestamp)
        assertEquals(listOf(0), vote.optionIndexesList)
        assertEquals(1, vote.voteCount)
    }

    @Test
    fun accountAttributes_carryRegIdsAndCapabilities() {
        val auth = SignalAuthData(
            phoneNumber = "+15551234567", aci = "aci-x", registrationId = 11,
            pniRegistrationId = 22, profileName = "Ada",
            unidentifiedAccessKey = "QUJD", registrationLock = "lock-1",
        )
        val parsed = org.json.JSONObject(SignalPayload.buildAccountAttributes(auth))
        assertEquals(11, parsed.getInt("registrationId"))
        assertEquals(22, parsed.getInt("pniRegistrationId"))
        assertEquals("Ada", parsed.getString("name"))
        assertEquals("lock-1", parsed.getString("registrationLock"))
        assertTrue(parsed.getJSONObject("capabilities").getBoolean("senderKey"))
        assertTrue(parsed.isNull("signalingKey"))

        // Fallback paths: legacy single registrationId as PNI, auth-level lock.
        // Note the builder omits pniRegistrationId when it equals the ACI id
        // (single-identity auth); it only emits the field when they differ.
        val legacy = SignalAuthData(phoneNumber = "+1", registrationId = 7, registrationLock = "rl")
        val parsedLegacy = org.json.JSONObject(SignalPayload.buildAccountAttributes(legacy))
        assertEquals(7, parsedLegacy.getInt("registrationId"))
        assertFalse(parsedLegacy.has("pniRegistrationId"))
        assertEquals("rl", parsedLegacy.getString("registrationLock"))

        val splitIds = SignalAuthData(
            phoneNumber = "+1", registrationId = 7, aciRegistrationId = 8, registrationLock = "rl",
        )
        val parsedSplit = org.json.JSONObject(SignalPayload.buildAccountAttributes(splitIds))
        assertEquals(8, parsedSplit.getInt("registrationId"))
        assertEquals(7, parsedSplit.getInt("pniRegistrationId"))

        val noLock = SignalAuthData(phoneNumber = "+1")
        val parsedNoLock = org.json.JSONObject(SignalPayload.buildAccountAttributes(noLock, registrationLock = "x"))
        assertEquals("x", parsedNoLock.getString("registrationLock"))
    }

    @Test
    fun userAgent_mentionsSignalAndroid() {
        assertTrue(SignalPayload.userAgent().startsWith("Signal-Android"))
    }

    // ---- WebSocket framing (SignalPayloadWs) ----

    @Test
    fun wsRequest_omitsEmptyBodyAndHeaders() {
        val bare = SignalPayload.buildWebSocketRequestMessage("GET", "/v1/x", id = 1L)
        assertFalse(bare.hasBody())
        assertEquals(0, bare.headersCount)
        val withBody = SignalPayload.buildWebSocketRequestMessage(
            "PUT", "/v1/y", body = byteArrayOf(1), id = 2L, headers = listOf("a:b"),
        )
        assertTrue(withBody.hasBody())
        assertEquals(listOf("a:b"), withBody.headersList)
        // Empty body array is treated as absent.
        val emptyBody = SignalPayload.buildWebSocketRequestMessage("PUT", "/v1/y", body = ByteArray(0), id = 3L)
        assertFalse(emptyBody.hasBody())
    }

    @Test
    fun wsResponse_carriesBodyAndHeaders() {
        val bare = SignalPayload.buildWebSocketResponseMessage(id = 1L)
        assertEquals(200, bare.status)
        assertEquals("OK", bare.message)
        val full = SignalPayload.buildWebSocketResponseMessage(
            id = 2L, status = 500, message = "err", body = byteArrayOf(9), headers = listOf("h:v"),
        )
        assertEquals(500, full.status)
        assertTrue(full.body.toByteArray().contentEquals(byteArrayOf(9)))
        assertEquals(listOf("h:v"), full.headersList)
    }

    @Test
    fun wsMessageWrappers_setType() {
        val req = SignalPayload.buildWebSocketRequestMessage("PUT", "/v1/k", id = 9L)
        val wrapped = SignalPayload.buildWebSocketMessageForRequest(req)
        assertEquals(WebSocketMessage.Type.REQUEST, wrapped.type)
        val resp = SignalPayload.buildWebSocketResponseMessage(id = 9L)
        val wrappedResp = SignalPayload.buildWebSocketMessageForResponse(resp)
        assertEquals(WebSocketMessage.Type.RESPONSE, wrappedResp.type)
    }

    @Test
    fun wsEncode_roundTrips() {
        val bytes = SignalPayload.encodeWebSocketRequest("PUT", "/v1/keepalive", id = 77L)
        val decoded = WebSocketMessage.parseFrom(bytes)
        assertEquals(77L, decoded.request.id)
        val respBytes = SignalPayload.encodeWebSocketResponse(78L, status = 201, message = "Created")
        val decodedResp = WebSocketMessage.parseFrom(respBytes)
        assertEquals(201, decodedResp.response.status)
    }

    @Test
    fun nextRequestId_isPositive() {
        assertTrue(SignalPayload.nextRequestId() > 0)
    }

    // ---- applyGroupV2 / applyDataOptionals ----

    @Test
    fun applyGroupV2_setsOnlyPresentFields() {
        val b = SignalServiceProtos.DataMessage.newBuilder().setBody("x")
        SignalPayload.applyGroupV2(b, null, null, null)
        assertFalse(b.build().hasGroupV2())

        val b2 = SignalServiceProtos.DataMessage.newBuilder().setBody("x")
        SignalPayload.applyGroupV2(b2, ByteArray(32) { 1 }, null, null)
        assertTrue(b2.build().hasGroupV2())
    }

    @Test
    fun applyDataOptionals_setsEveryField() {
        val b = SignalServiceProtos.DataMessage.newBuilder().setBody("x")
        SignalPayload.applyDataOptionals(
            b,
            bodyRanges = listOf(SignalPayload.buildBodyRange(0, 1)),
            attachments = listOf(
                SignalServiceProtos.AttachmentPointer.newBuilder().setCdnKey("k").build(),
            ),
            quote = SignalServiceProtos.DataMessage.Quote.newBuilder().setId(3L).build(),
            reaction = SignalPayload.buildReaction("e", false, targetSentTimestamp = 1L),
            delete = SignalPayload.buildDelete(2L),
            pollCreate = SignalPayload.buildPollCreate("q", listOf("o")),
            pollVote = SignalPayload.buildPollVote(targetSentTimestamp = 1L, optionIndexes = listOf(0)),
            pollTerminate = SignalServiceProtos.DataMessage.PollTerminate.newBuilder()
                .setTargetSentTimestamp(9L).build(),
            expireTimer = 30,
            profileKey = ByteArray(32) { 2 },
            contact = SignalPayload.buildSharedContact("N"),
        )
        val dm = b.build()
        assertEquals(1, dm.bodyRangesCount)
        assertEquals(1, dm.attachmentsCount)
        assertTrue(dm.hasQuote())
        assertTrue(dm.hasReaction())
        assertTrue(dm.hasDelete())
        assertTrue(dm.hasPollCreate())
        assertTrue(dm.hasPollVote())
        assertTrue(dm.hasPollTerminate())
        assertEquals(30, dm.expireTimer)
        assertTrue(dm.hasProfileKey())
        assertEquals(1, dm.contactCount)
    }

    // ---- deprecated JSON helpers ----

    @Test
    fun deprecatedBuilders_keepShape() {
        val req = SignalPayload.buildWebSocketRequest("PUT", "/v1/x", body = byteArrayOf(1, 2), id = "z")
        val reqJson = org.json.JSONObject(req)
        assertEquals("REQUEST", reqJson.getString("type"))
        assertEquals("PUT", reqJson.getString("verb"))
        assertEquals("/v1/x", reqJson.getString("path"))
        assertEquals("z", reqJson.getString("id"))
        assertTrue(reqJson.has("body"))
        val resp = SignalPayload.buildWebSocketResponse("z", 200, body = byteArrayOf(3))
        assertTrue(resp.contains("z"))
        val legacy = SignalPayload.buildDataMessageLegacy("hi", timestamp = 5L, quoteId = 9L)
        assertEquals("hi", legacy.getString("body"))
        assertEquals(5L, legacy.getLong("timestamp"))
        assertEquals(9L, legacy.getLong("quoteId"))
        val withGroup = SignalPayload.buildDataMessageLegacy("hi", groupId = byteArrayOf(1))
        assertTrue(withGroup.has("groupId"))
    }
}
