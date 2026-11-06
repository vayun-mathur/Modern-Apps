package com.vayunmathur.communicate.whatsapp

import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.buildAck
import com.vayunmathur.communicate.data.whatsapp.buildChatPresence
import com.vayunmathur.communicate.data.whatsapp.buildContactProto
import com.vayunmathur.communicate.data.whatsapp.buildConversationMessage
import com.vayunmathur.communicate.data.whatsapp.buildConversationPlaintext
import com.vayunmathur.communicate.data.whatsapp.buildCreateGroup
import com.vayunmathur.communicate.data.whatsapp.buildDisappearingTimerProto
import com.vayunmathur.communicate.data.whatsapp.buildFanOutMessageNode
import com.vayunmathur.communicate.data.whatsapp.buildGroupInfoChange
import com.vayunmathur.communicate.data.whatsapp.buildGroupParticipantChange
import com.vayunmathur.communicate.data.whatsapp.buildGroupParticipantsQuery
import com.vayunmathur.communicate.data.whatsapp.buildHistoryOnDemandRequest
import com.vayunmathur.communicate.data.whatsapp.buildKeepalive
import com.vayunmathur.communicate.data.whatsapp.buildLeaveGroup
import com.vayunmathur.communicate.data.whatsapp.buildLocationProto
import com.vayunmathur.communicate.data.whatsapp.buildMediaConnQuery
import com.vayunmathur.communicate.data.whatsapp.buildMediaProto
import com.vayunmathur.communicate.data.whatsapp.buildMexQuery
import com.vayunmathur.communicate.data.whatsapp.buildPollCreationProto
import com.vayunmathur.communicate.data.whatsapp.buildReactionProto
import com.vayunmathur.communicate.data.whatsapp.buildReadReceipt
import com.vayunmathur.communicate.data.whatsapp.buildReceipt
import com.vayunmathur.communicate.data.whatsapp.buildRetryReceipt
import com.vayunmathur.communicate.data.whatsapp.buildSetGroupTopic
import com.vayunmathur.communicate.data.whatsapp.buildTextProto
import com.vayunmathur.communicate.data.whatsapp.buildUsyncDevicesQuery
import com.vayunmathur.communicate.data.whatsapp.deviceSentPlaintext
import com.vayunmathur.communicate.data.whatsapp.isRevokeFromMe
import com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto
import com.vayunmathur.communicate.data.whatsapp.senderKeyDistributionPlaintext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Slice 1 (w5): node + proto builders (WhatsAppProtocolBuilders.kt / Builders2.kt).
 * Pure JVM: asserts wire shapes (tags, attrs, children, proto fields).
 */
class W5Slice1BuildersTest {

    @Test
    fun conversationPlaintext_roundTripsThroughParse() {
        val bytes = WhatsAppProtocol.buildConversationPlaintext("hello builders")
        assertEquals("hello builders", WhatsAppE2EProto.Message.parseFrom(bytes).conversation)
        assertEquals("hello builders", WhatsAppProtocol.buildConversationMessage("hello builders").conversation)
    }

    @Test
    fun historyOnDemandRequest_setsFields() {
        val msg = WhatsAppProtocol.buildHistoryOnDemandRequest("1@g.us", "OLD1", true, 1700000000L, 50)
        assertTrue(msg.hasProtocolMessage())
        val pm = msg.protocolMessage
        assertEquals(WhatsAppE2EProto.ProtocolMessage.Type.PEER_DATA_OPERATION_REQUEST_MESSAGE, pm.type)
        val req = pm.peerDataOperationRequestMessage.historySyncOnDemandRequest
        assertEquals("1@g.us", req.chatJid)
        assertEquals("OLD1", req.oldestMsgId)
        assertTrue(req.oldestMsgFromMe)
        assertEquals(1700000000L, req.oldestMsgTimestampMs)
        assertEquals(50, req.onDemandMsgCount)
    }

    @Test
    fun deviceSentPlaintext_wrapsDestination() {
        val inner = WhatsAppProtocol.buildConversationMessage("inner")
        val bytes = WhatsAppProtocol.deviceSentPlaintext("peer@s.whatsapp.net", inner)
        val back = WhatsAppE2EProto.Message.parseFrom(bytes)
        assertTrue(back.hasDeviceSentMessage())
        assertEquals("peer@s.whatsapp.net", back.deviceSentMessage.destinationJid)
        assertEquals("inner", back.deviceSentMessage.message.conversation)
    }

    @Test
    fun skdmPlaintext_carriesGroupAndBytes() {
        val skdm = byteArrayOf(1, 2, 3, 4)
        val back = WhatsAppE2EProto.Message.parseFrom(
            WhatsAppProtocol.senderKeyDistributionPlaintext("9@g.us", skdm),
        )
        assertEquals("9@g.us", back.senderKeyDistributionMessage.groupId)
        assertTrue(back.senderKeyDistributionMessage.axolotlSenderKeyDistributionMessage.toByteArray().contentEquals(skdm))
    }

    @Test
    fun fanOutNode_shapesParticipantsAndExtras() {
        val encs = listOf(
            WhatsAppProtocol.ParticipantEnc("1:0@s.whatsapp.net", "msg", byteArrayOf(1)),
            WhatsAppProtocol.ParticipantEnc("2:0@s.whatsapp.net", "pkmsg", byteArrayOf(2)),
        )
        val node = WhatsAppProtocol.buildFanOutMessageNode(
            to = "1@g.us", id = "F1", type = "text",
            participantEncs = encs, includeDeviceIdentity = true, deviceIdentity = byteArrayOf(9),
        )
        assertEquals("message", node.tag)
        assertEquals("F1", node.attrs["id"])
        val parts = node.getChildByTag("participants")!!
        assertEquals(2, parts.content.size)
        assertEquals("1:0@s.whatsapp.net", (parts.content[0] as WhatsAppProtocol.Node).attrs["jid"])
        assertTrue(node.getChildByTag("device-identity") != null)
    }

    @Test
    fun fanOutNode_withoutExtras_omitsIdentity() {
        val node = WhatsAppProtocol.buildFanOutMessageNode(
            "1@s.whatsapp.net", "F2", "text", emptyList(), false, null,
            messageAttrs = mapOf("edit" to "7"),
        )
        assertEquals("7", node.attrs["edit"])
        assertEquals(null, node.getChildByTag("device-identity"))
        assertEquals(0, node.getChildByTag("participants")!!.content.size)
    }

    @Test
    fun retryReceipt_copiesRecipientAndParticipant() {
        val orig = WhatsAppProtocol.Node(
            tag = "message",
            attrs = mapOf("id" to "M1", "from" to "a@s.whatsapp.net", "t" to "5",
                "recipient" to "r@s.whatsapp.net", "participant" to "p@s.whatsapp.net"),
        )
        val receipt = WhatsAppProtocol.buildRetryReceipt(orig, registrationId = 0x01020304, retryCount = 2, keysNode = null)
        assertEquals("receipt", receipt.tag)
        assertEquals("retry", receipt.attrs["type"])
        assertEquals("a@s.whatsapp.net", receipt.attrs["to"])
        assertEquals("r@s.whatsapp.net", receipt.attrs["recipient"])
        val retry = receipt.getChildByTag("retry")!!
        assertEquals("2", retry.attrs["count"])
        val reg = receipt.getChildByTag("registration")!!
        assertTrue(reg.data!!.contentEquals(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun groupParticipantsQuery_shape() {
        val q = WhatsAppProtocol.buildGroupParticipantsQuery("1@g.us", "Q1")
        assertEquals("iq", q.tag)
        assertEquals("w:g2", q.attrs["xmlns"])
        assertEquals("interactive", q.getChildByTag("query")!!.attrs["request"])
    }

    @Test
    fun usyncQuery_listsUsers() {
        val q = WhatsAppProtocol.buildUsyncDevicesQuery(listOf("1@s.whatsapp.net", "2@s.whatsapp.net"), "U1", "S1")
        assertEquals("usync", q.attrs["xmlns"])
        val list = q.getChildByTag("usync")!!.getChildByTag("list")!!
        assertEquals(2, list.content.size)
        assertEquals("message", q.getChildByTag("usync")!!.attrs["context"])
    }

    @Test
    fun mediaConnQuery_shape() {
        val q = WhatsAppProtocol.buildMediaConnQuery("MC1")
        assertEquals("w:m", q.attrs["xmlns"])
        assertTrue(q.getChildByTag("media_conn") != null)
    }

    @Test
    fun mexQuery_embedsEnvelopeAndArgoMode() {
        val n = WhatsAppProtocol.buildMexQuery("123", """{"queryId":"123"}""", "MX1", argoMode = 3L)
        assertEquals("w:mex", n.attrs["xmlns"])
        val query = n.getChildByTag("query")!!
        assertEquals("123", query.attrs["query_id"])
        assertEquals("3", query.attrs["argo_mode"])
        assertEquals("""{"queryId":"123"}""", String(query.data!!, Charsets.UTF_8))
    }

    @Test
    fun reactionProto_groupOtherSender_setsParticipant() {
        val msg = WhatsAppProtocol.buildReactionProto("1@g.us", "T1", "❤️", false, "o@s.whatsapp.net")
        val r = msg.reactionMessage
        assertEquals("❤️", r.text)
        assertEquals("o@s.whatsapp.net", r.key.participant)
        assertTrue(r.senderTimestampMs > 0)
    }

    @Test
    fun receipt_singleAndMulti() {
        val single = WhatsAppProtocol.buildReceipt("1@s.whatsapp.net", listOf("M1"), "read")
        assertEquals("M1", single.attrs["id"])
        assertEquals("read", single.attrs["type"])
        assertEquals(null, single.getChildByTag("list"))

        val multi = WhatsAppProtocol.buildReceipt("1@g.us", listOf("M1", "M2", "M3"), "", senderJid = "s@s.whatsapp.net")
        assertEquals(null, multi.attrs["type"])
        assertEquals("s@s.whatsapp.net", multi.attrs["participant"])
        assertEquals(2, multi.getChildByTag("list")!!.content.size)
    }

    @Test
    fun receipt_emptyIds_throws() {
        assertFailsWith<IllegalArgumentException> {
            WhatsAppProtocol.buildReceipt("1@s.whatsapp.net", emptyList(), "read")
        }
        assertFailsWith<IllegalArgumentException> {
            WhatsAppProtocol.buildReadReceipt("1@s.whatsapp.net", emptyList())
        }
    }

    @Test
    fun readReceipt_groupSetsParticipant() {
        val r = WhatsAppProtocol.buildReadReceipt("1@g.us", listOf("M1", "M2"), senderJid = "s@s.whatsapp.net")
        assertEquals("read", r.attrs["type"])
        assertEquals("s@s.whatsapp.net", r.attrs["participant"])
        assertTrue(r.attrs["t"]!!.toLong() > 0)
    }

    @Test
    fun mediaProto_eachTypeSetsField() {
        val img = WhatsAppProtocol.buildMediaProto("u", "p", ByteArray(32), ByteArray(32), ByteArray(32), 10, "image/jpeg", "cap", "image")
        assertTrue(img.hasImageMessage())
        assertEquals("cap", img.imageMessage.caption)
        val vid = WhatsAppProtocol.buildMediaProto("u", "p", ByteArray(32), ByteArray(32), ByteArray(32), 10, "video/mp4", null, "video")
        assertTrue(vid.hasVideoMessage())
        assertEquals("", vid.videoMessage.caption)
        val aud = WhatsAppProtocol.buildMediaProto("u", "p", ByteArray(32), ByteArray(32), ByteArray(32), 10, "audio/ogg", null, "audio")
        assertTrue(aud.hasAudioMessage())
        val doc = WhatsAppProtocol.buildMediaProto("u", "p", ByteArray(32), ByteArray(32), ByteArray(32), 10, "application/pdf", null, "document")
        assertTrue(doc.hasDocumentMessage())
        val sticker = WhatsAppProtocol.buildMediaProto("u", "p", ByteArray(32), ByteArray(32), ByteArray(32), 10, "image/webp", null, "sticker")
        assertTrue(sticker.hasStickerMessage())
        val unknown = WhatsAppProtocol.buildMediaProto("u", "p", ByteArray(32), ByteArray(32), ByteArray(32), 10, "x", null, "bogus")
        assertEquals(WhatsAppE2EProto.Message.getDefaultInstance(), unknown)
    }

    @Test
    fun presence_composingPausedAndAudio() {
        val composing = WhatsAppProtocol.buildChatPresence("1@s.whatsapp.net", true)
        assertEquals("chatstate", composing.tag)
        assertTrue(composing.getChildByTag("composing") != null)
        val paused = WhatsAppProtocol.buildChatPresence("1@s.whatsapp.net", false, ownJid = "me@s.whatsapp.net")
        assertTrue(paused.getChildByTag("paused") != null)
        assertEquals("me@s.whatsapp.net", paused.attrs["from"])
        val audio = WhatsAppProtocol.buildChatPresence("1@s.whatsapp.net", true, isAudio = true)
        assertEquals("audio", audio.getChildByTag("composing")!!.attrs["media"])
    }

    @Test
    fun keepalive_shape() {
        val k = WhatsAppProtocol.buildKeepalive("K1")
        assertEquals("w:p", k.attrs["xmlns"])
        assertEquals("K1", k.attrs["id"])
    }

    @Test
    fun ack_typeOnlyForNonMessageClass() {
        val msgAck = WhatsAppProtocol.buildAck("message", "A1", "from@s.whatsapp.net", type = "read")
        assertEquals(null, msgAck.attrs["type"])
        val rcptAck = WhatsAppProtocol.buildAck(
            "receipt", "A2", "from@s.whatsapp.net",
            participant = "p@g.us", recipient = "r@s.whatsapp.net", type = "read",
        )
        assertEquals("read", rcptAck.attrs["type"])
        assertEquals("p@g.us", rcptAck.attrs["participant"])
        assertEquals("r@s.whatsapp.net", rcptAck.attrs["recipient"])
    }

    @Test
    fun pollCreation_selectableCountClamps() {
        val secret = ByteArray(32)
        val normal = WhatsAppProtocol.buildPollCreationProto("Q?", listOf("a", "b"), 1, secret)
        assertTrue(normal.hasPollCreationMessageV3())
        assertEquals(1, normal.pollCreationMessageV3.selectableOptionsCount)
        assertEquals(listOf("a", "b"), normal.pollCreationMessageV3.optionsList.map { it.optionName })
        assertTrue(normal.messageContextInfo.messageSecret.toByteArray().contentEquals(secret))
        val over = WhatsAppProtocol.buildPollCreationProto("Q?", listOf("a"), 5, secret)
        assertEquals(0, over.pollCreationMessageV3.selectableOptionsCount)
        val neg = WhatsAppProtocol.buildPollCreationProto("Q?", listOf("a"), -1, secret)
        assertEquals(0, neg.pollCreationMessageV3.selectableOptionsCount)
    }

    @Test
    fun locationProto_optionalFields() {
        val full = WhatsAppProtocol.buildLocationProto(1.5, -2.5, "Name", "Addr")
        assertEquals(1.5, full.locationMessage.degreesLatitude, 1e-9)
        assertEquals("Name", full.locationMessage.name)
        val bare = WhatsAppProtocol.buildLocationProto(0.0, 0.0)
        assertFalse(bare.locationMessage.hasName())
        assertFalse(bare.locationMessage.hasAddress())
    }

    @Test
    fun contactProto_setsFields() {
        val m = WhatsAppProtocol.buildContactProto("Al", "VCARD")
        assertEquals("Al", m.contactMessage.displayName)
        assertEquals("VCARD", m.contactMessage.vcard)
    }

    @Test
    fun disappearingTimerProto_setsValue() {
        val m = WhatsAppProtocol.buildDisappearingTimerProto(604800L)
        assertEquals(604800, m.protocolMessage.ephemeralExpiration)
    }

    @Test
    fun groupInfoChange_embedsValue() {
        val n = WhatsAppProtocol.buildGroupInfoChange("1@g.us", "subject", "New Name", "G1")
        assertEquals("w:g2", n.attrs["xmlns"])
        val child = n.getChildByTag("subject")!!
        assertEquals("New Name", String(child.data!!, Charsets.UTF_8))
    }

    @Test
    fun setGroupTopic_deleteVsBody() {
        val withBody = WhatsAppProtocol.buildSetGroupTopic("1@g.us", "topic here", "N1", previousId = "P0")
        val desc = withBody.getChildByTag("description")!!
        assertEquals("N1", desc.attrs["id"])
        assertEquals("P0", desc.attrs["prev"])
        assertEquals("topic here", String(desc.getChildByTag("body")!!.data!!, Charsets.UTF_8))

        val deleted = WhatsAppProtocol.buildSetGroupTopic("1@g.us", "", "N2")
        val descDel = deleted.getChildByTag("description")!!
        assertEquals("true", descDel.attrs["delete"])
        assertEquals(null, descDel.getChildByTag("body"))
    }

    @Test
    fun leaveGroup_targetsGus() {
        val n = WhatsAppProtocol.buildLeaveGroup("1@g.us", "L1")
        assertEquals("g.us", n.attrs["to"])
        assertEquals("1@g.us", n.getChildByTag("leave")!!.getChildByTag("group")!!.attrs["id"])
    }

    @Test
    fun createGroup_listsParticipants() {
        val n = WhatsAppProtocol.buildCreateGroup("Subject", listOf("a@s.whatsapp.net", "b@s.whatsapp.net"), "C1", "key-1")
        val create = n.getChildByTag("create")!!
        assertEquals("Subject", create.attrs["subject"])
        assertEquals("key-1", create.attrs["key"])
        assertEquals(2, create.content.size)
    }

    @Test
    fun groupParticipantChange_actionNode() {
        val n = WhatsAppProtocol.buildGroupParticipantChange("1@g.us", listOf("a@s.whatsapp.net"), "add", "P1")
        assertEquals("1@g.us", n.attrs["to"])
        assertEquals(1, n.getChildByTag("add")!!.content.size)
    }

    @Test
    fun isRevokeFromMe_cases() {
        assertTrue(WhatsAppProtocol.isRevokeFromMe("", "me@s.whatsapp.net"))
        assertTrue(WhatsAppProtocol.isRevokeFromMe("me@s.whatsapp.net", "me@s.whatsapp.net"))
        // NOTE: device-suffixed own JID compares by the pre-"@" part only, so "me:1"
        // does NOT match "me" (possible gap vs the module's bare-user JID convention;
        // flagged in the task report, production left unchanged).
        assertFalse(WhatsAppProtocol.isRevokeFromMe("me:1@s.whatsapp.net", "me@s.whatsapp.net"))
        assertFalse(WhatsAppProtocol.isRevokeFromMe("other@s.whatsapp.net", "me@s.whatsapp.net"))
    }

    @Test
    fun textProto_plainVsRich() {
        val plain = WhatsAppProtocol.buildTextProto("hi")
        assertTrue(plain.hasConversation())
        val rich = WhatsAppProtocol.buildTextProto("hi @a", mentionedJids = listOf("a@s.whatsapp.net"))
        assertTrue(rich.hasExtendedTextMessage())
        assertEquals(listOf("a@s.whatsapp.net"), rich.extendedTextMessage.contextInfo.mentionedJidList)
    }
}
