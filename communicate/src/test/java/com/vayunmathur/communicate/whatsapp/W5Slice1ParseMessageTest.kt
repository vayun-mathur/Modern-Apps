package com.vayunmathur.communicate.whatsapp

import com.vayunmathur.communicate.data.whatsapp.ContactData
import com.vayunmathur.communicate.data.whatsapp.LocationData
import com.vayunmathur.communicate.data.whatsapp.PollData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppMessage
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.extractMessageBody
import com.vayunmathur.communicate.data.whatsapp.getMessageType
import com.vayunmathur.communicate.data.whatsapp.padMessage
import com.vayunmathur.communicate.data.whatsapp.parseMessage
import com.vayunmathur.communicate.data.whatsapp.pollCreation
import com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto
import com.vayunmathur.communicate.data.whatsapp.rerouteLIDSender
import com.vayunmathur.communicate.data.whatsapp.unpadMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 1 (w5): inbound message parsing (WhatsAppProtocolParsing.kt).
 * Pure JVM: parseMessage takes an injectable decrypt callback; the legacy
 * no-crypto path treats enc bytes as already-plaintext padded protobuf.
 */
class W5Slice1ParseMessageTest {

    private fun encNodeFor(msg: WhatsAppE2EProto.Message, type: String = "msg"): WhatsAppProtocol.Node {
        val padded = WhatsAppProtocol.padMessage(msg.toByteArray())
        return WhatsAppProtocol.Node(tag = "enc", attrs = mapOf("type" to type), data = padded)
    }

    private fun msgNode(
        from: String,
        id: String,
        children: List<WhatsAppProtocol.Node>,
        extra: Map<String, String> = emptyMap(),
    ): WhatsAppProtocol.Node = WhatsAppProtocol.Node(
        tag = "message",
        attrs = mapOf("from" to from, "id" to id, "type" to "text", "t" to "1700000000") + extra,
        content = children,
    )

    // ---- plaintext fallback ----

    @Test
    fun parseMessage_plaintextBody() {
        val node = msgNode(
            "111@s.whatsapp.net", "ID1",
            listOf(WhatsAppProtocol.Node(tag = "body", data = "hello".toByteArray())),
        )
        val msg = WhatsAppProtocol.parseMessage(node)
        assertTrue(msg != null)
        assertEquals("hello", msg!!.body)
        assertEquals("ID1", msg.id)
        assertEquals("111@s.whatsapp.net", msg.from)
    }

    @Test
    fun parseMessage_wrongTagOrMissingAttrs_returnsNull() {
        assertNull(WhatsAppProtocol.parseMessage(WhatsAppProtocol.Node(tag = "iq")))
        assertNull(WhatsAppProtocol.parseMessage(WhatsAppProtocol.Node(tag = "message", attrs = mapOf("id" to "x"))))
        assertNull(
            WhatsAppProtocol.parseMessage(
                WhatsAppProtocol.Node(tag = "message", attrs = mapOf("from" to "a@b")),
            ),
        )
    }

    @Test
    fun parseMessage_senderPnNormalizesFrom() {
        val node = msgNode(
            "12345@lid", "ID2",
            listOf(WhatsAppProtocol.Node(tag = "body", data = "hi".toByteArray())),
            extra = mapOf("sender_pn" to "15551234567@s.whatsapp.net"),
        )
        assertEquals("15551234567@s.whatsapp.net", WhatsAppProtocol.parseMessage(node)!!.from)
    }

    @Test
    fun parseMessage_groupFrom_keepsFromDespiteSenderPn() {
        val node = msgNode(
            "999@g.us", "ID3",
            listOf(WhatsAppProtocol.Node(tag = "body", data = "hi".toByteArray())),
            extra = mapOf("sender_pn" to "15551234567@s.whatsapp.net", "participant" to "1@s.whatsapp.net"),
        )
        val msg = WhatsAppProtocol.parseMessage(node)!!
        assertEquals("999@g.us", msg.from)
        assertEquals("1@s.whatsapp.net", msg.participant)
    }

    // ---- encrypted path (legacy: enc bytes already padded plaintext) ----

    @Test
    fun parseMessage_conversationProto() {
        val e2e = WhatsAppE2EProto.Message.newBuilder().setConversation("secret hi").build()
        val msg = WhatsAppProtocol.parseMessage(msgNode("111@s.whatsapp.net", "E1", listOf(encNodeFor(e2e))))!!
        assertEquals("secret hi", msg.body)
        assertEquals("text", msg.messageType)
        assertFalse(msg.isFromMe)
    }

    @Test
    fun parseMessage_withDecryptCallback_usesDecryptedBytes() {
        val e2e = WhatsAppE2EProto.Message.newBuilder().setConversation("via callback").build()
        val raw = WhatsAppProtocol.Node(tag = "enc", attrs = mapOf("type" to "pkmsg"), data = "cipher".toByteArray())
        var sawType = ""
        val msg = WhatsAppProtocol.parseMessage(msgNode("111@s.whatsapp.net", "E2", listOf(raw))) { _, encType, _ ->
            sawType = encType
            WhatsAppProtocol.padMessage(e2e.toByteArray())
        }!!
        assertEquals("pkmsg", sawType)
        assertEquals("via callback", msg.body)
    }

    @Test
    fun parseMessage_decryptReturnsNull_yieldsEmptyBody() {
        val raw = WhatsAppProtocol.Node(tag = "enc", data = "cipher".toByteArray())
        val msg = WhatsAppProtocol.parseMessage(msgNode("111@s.whatsapp.net", "E3", listOf(raw))) { _, _, _ -> null }!!
        assertEquals("", msg.body)
    }

    @Test
    fun parseMessage_deviceSentMessage_unwrapsToDestination() {
        val inner = WhatsAppE2EProto.Message.newBuilder().setConversation("echo from laptop").build()
        val dsm = WhatsAppE2EProto.Message.newBuilder()
            .setDeviceSentMessage(
                WhatsAppE2EProto.DeviceSentMessage.newBuilder()
                    .setDestinationJid("peer@s.whatsapp.net")
                    .setMessage(inner),
            )
            .build()
        val msg = WhatsAppProtocol.parseMessage(msgNode("me@s.whatsapp.net", "E4", listOf(encNodeFor(dsm))))!!
        assertEquals("peer@s.whatsapp.net", msg.from)
        assertEquals("echo from laptop", msg.body)
        assertTrue(msg.isFromMe)
    }

    @Test
    fun parseMessage_recipientAttr_marksFromMe() {
        val e2e = WhatsAppE2EProto.Message.newBuilder().setConversation("self echo").build()
        val node = msgNode(
            "me@s.whatsapp.net", "E5", listOf(encNodeFor(e2e)),
            extra = mapOf("recipient" to "peer@s.whatsapp.net"),
        )
        val msg = WhatsAppProtocol.parseMessage(node)!!
        assertTrue(msg.isFromMe)
        assertEquals("peer@s.whatsapp.net", msg.from)
    }

    @Test
    fun parseMessage_reactionSetsFlags() {
        val key = WhatsAppE2EProto.MessageKey.newBuilder().setId("T1").setFromMe(false).build()
        val e2e = WhatsAppE2EProto.Message.newBuilder()
            .setReactionMessage(
                WhatsAppE2EProto.ReactionMessage.newBuilder().setKey(key).setText("👍"),
            )
            .build()
        val msg = WhatsAppProtocol.parseMessage(msgNode("111@s.whatsapp.net", "E6", listOf(encNodeFor(e2e))))!!
        assertTrue(msg.isReaction)
        assertEquals("T1", msg.reactionTargetId)
        assertEquals("👍", msg.body)
        assertEquals("reaction", msg.messageType)
    }

    @Test
    fun parseMessage_emptyReaction_typesAsReactionRemove() {
        val key = WhatsAppE2EProto.MessageKey.newBuilder().setId("T1").build()
        val e2e = WhatsAppE2EProto.Message.newBuilder()
            .setReactionMessage(WhatsAppE2EProto.ReactionMessage.newBuilder().setKey(key).setText(""))
            .build()
        assertEquals("reaction remove", WhatsAppProtocol.getMessageType(e2e))
    }

    @Test
    fun parseMessage_revokeAndEdit() {
        val revokeKey = WhatsAppE2EProto.MessageKey.newBuilder().setId("T9").setFromMe(true).build()
        val revoke = WhatsAppE2EProto.Message.newBuilder()
            .setProtocolMessage(
                WhatsAppE2EProto.ProtocolMessage.newBuilder()
                    .setType(WhatsAppE2EProto.ProtocolMessage.Type.REVOKE).setKey(revokeKey),
            )
            .build()
        val r = WhatsAppProtocol.parseMessage(msgNode("111@s.whatsapp.net", "E7", listOf(encNodeFor(revoke))))!!
        assertTrue(r.isRevoke)
        assertEquals("T9", r.revokeTargetId)
        assertEquals("[Message Deleted]", r.body)

        val editKey = WhatsAppE2EProto.MessageKey.newBuilder().setId("T8").setFromMe(true).build()
        val edited = WhatsAppE2EProto.Message.newBuilder().setConversation("v2").build()
        val edit = WhatsAppE2EProto.Message.newBuilder()
            .setProtocolMessage(
                WhatsAppE2EProto.ProtocolMessage.newBuilder()
                    .setType(WhatsAppE2EProto.ProtocolMessage.Type.MESSAGE_EDIT)
                    .setKey(editKey).setEditedMessage(edited),
            )
            .build()
        val e = WhatsAppProtocol.parseMessage(msgNode("111@s.whatsapp.net", "E8", listOf(encNodeFor(edit))))!!
        assertTrue(e.isEdit)
        assertEquals("T8", e.editTargetId)
    }

    @Test
    fun parseMessage_viewOnce_setsFlag() {
        val inner = WhatsAppE2EProto.Message.newBuilder()
            .setImageMessage(WhatsAppE2EProto.ImageMessage.newBuilder().setMimetype("image/jpeg"))
            .build()
        val wrapped = WhatsAppE2EProto.Message.newBuilder()
            .setViewOnceMessageV2(WhatsAppE2EProto.FutureProofMessage.newBuilder().setMessage(inner))
            .build()
        val msg = WhatsAppProtocol.parseMessage(msgNode("111@s.whatsapp.net", "E9", listOf(encNodeFor(wrapped))))!!
        assertTrue(msg.isViewOnce)
        assertEquals("image image/jpeg", msg.messageType)
    }

    @Test
    fun parseMessage_senderKeyOnly_passesThroughForKeyProcessing() {
        val skdm = WhatsAppE2EProto.Message.newBuilder()
            .setSenderKeyDistributionMessage(
                WhatsAppE2EProto.SenderKeyDistributionMessage.newBuilder().setGroupId("1@g.us"),
            )
            .build()
        val msg = WhatsAppProtocol.parseMessage(msgNode("111@s.whatsapp.net", "E10", listOf(encNodeFor(skdm))))
        assertTrue(msg != null)
    }

    @Test
    fun parseMessage_unknownProtocolMessage_returnsNull() {
        val pm = WhatsAppE2EProto.Message.newBuilder()
            .setProtocolMessage(
                WhatsAppE2EProto.ProtocolMessage.newBuilder()
                    .setType(WhatsAppE2EProto.ProtocolMessage.Type.SHARE_PHONE_NUMBER),
            )
            .build()
        assertNull(WhatsAppProtocol.parseMessage(msgNode("111@s.whatsapp.net", "E11", listOf(encNodeFor(pm)))))
    }

    @Test
    fun parseMessage_locationContactAndMediaBodies() {
        val loc = WhatsAppE2EProto.Message.newBuilder()
            .setLocationMessage(
                WhatsAppE2EProto.LocationMessage.newBuilder()
                    .setDegreesLatitude(37.77).setDegreesLongitude(-122.41).setName("SF"),
            )
            .build()
        val l = WhatsAppProtocol.parseMessage(msgNode("1@s.whatsapp.net", "L1", listOf(encNodeFor(loc))))!!
        assertEquals("location", l.messageType)
        assertTrue(l.body.contains("maps.google.com"), l.body)
        assertEquals(37.77, l.locationData!!.latitude, 1e-9)

        val contact = WhatsAppE2EProto.Message.newBuilder()
            .setContactMessage(
                WhatsAppE2EProto.ContactMessage.newBuilder().setDisplayName("Al").setVcard("VC"),
            )
            .build()
        val c = WhatsAppProtocol.parseMessage(msgNode("1@s.whatsapp.net", "C1", listOf(encNodeFor(contact))))!!
        assertEquals(ContactData("Al", "VC"), c.contactData)
        assertTrue(c.body.contains("[Contact: Al]"))

        val audio = WhatsAppE2EProto.Message.newBuilder()
            .setAudioMessage(WhatsAppE2EProto.AudioMessage.newBuilder())
            .build()
        assertEquals("[Audio]", WhatsAppProtocol.parseMessage(msgNode("1@s.whatsapp.net", "A1", listOf(encNodeFor(audio))))!!.body)
    }

    @Test
    fun parseMessage_ephemeralSetting_setsTimer() {
        val pm = WhatsAppE2EProto.Message.newBuilder()
            .setProtocolMessage(
                WhatsAppE2EProto.ProtocolMessage.newBuilder()
                    .setType(WhatsAppE2EProto.ProtocolMessage.Type.EPHEMERAL_SETTING)
                    .setEphemeralExpiration(86400),
            )
            .build()
        val msg = WhatsAppProtocol.parseMessage(msgNode("1@s.whatsapp.net", "EP1", listOf(encNodeFor(pm))))!!
        assertEquals(86400L, msg.disappearingTimer)
        assertEquals("ephemeral setting", msg.messageType)
    }

    // ---- getMessageType / extractMessageBody / pollCreation / reroute ----

    @Test
    fun messageType_coversAllBranches() {
        assertEquals("ignore", WhatsAppProtocol.getMessageType(null))
        assertEquals("text", WhatsAppProtocol.getMessageType(WhatsAppE2EProto.Message.newBuilder().setConversation("x").build()))
        assertEquals(
            "contact",
            WhatsAppProtocol.getMessageType(
                WhatsAppE2EProto.Message.newBuilder()
                    .setContactMessage(WhatsAppE2EProto.ContactMessage.newBuilder()).build(),
            ),
        )
        assertEquals(
            "location",
            WhatsAppProtocol.getMessageType(
                WhatsAppE2EProto.Message.newBuilder()
                    .setLocationMessage(WhatsAppE2EProto.LocationMessage.newBuilder()).build(),
            ),
        )
        assertEquals(
            "document application/pdf",
            WhatsAppProtocol.getMessageType(
                WhatsAppE2EProto.Message.newBuilder().setDocumentMessage(
                    WhatsAppE2EProto.DocumentMessage.newBuilder().setMimetype("application/pdf"),
                ).build(),
            ),
        )
        assertEquals(
            "unknown",
            WhatsAppProtocol.getMessageType(WhatsAppE2EProto.Message.getDefaultInstance()),
        )
        // edited message delegates to inner type
        val inner = WhatsAppE2EProto.Message.newBuilder().setConversation("v").build()
        val edited = WhatsAppE2EProto.Message.newBuilder()
            .setEditedMessage(WhatsAppE2EProto.FutureProofMessage.newBuilder().setMessage(inner)).build()
        assertEquals("text", WhatsAppProtocol.getMessageType(edited))
    }

    @Test
    fun extractMessageBody_coversMediaPlaceholders() {
        assertEquals("[Image]", WhatsAppProtocol.extractMessageBody(imageMsg("", "image/jpeg")))
        assertEquals("cap", WhatsAppProtocol.extractMessageBody(imageMsg("cap", "image/jpeg")))
        assertEquals("[Video]", WhatsAppProtocol.extractMessageBody(videoMsg("")))
        assertEquals("[Document: t]", WhatsAppProtocol.extractMessageBody(docMsg()))
        assertEquals("[Sticker]", WhatsAppProtocol.extractMessageBody(stickerMsg()))
        assertEquals("[Contact: Al]", WhatsAppProtocol.extractMessageBody(contactMsg()))
        assertEquals("[Location]", WhatsAppProtocol.extractMessageBody(locationMsg()))
        assertEquals("", WhatsAppProtocol.extractMessageBody(WhatsAppE2EProto.Message.getDefaultInstance()))
    }

    private fun imageMsg(caption: String, mime: String) = WhatsAppE2EProto.Message.newBuilder()
        .setImageMessage(WhatsAppE2EProto.ImageMessage.newBuilder().setCaption(caption).setMimetype(mime)).build()

    private fun videoMsg(caption: String) = WhatsAppE2EProto.Message.newBuilder()
        .setVideoMessage(WhatsAppE2EProto.VideoMessage.newBuilder().setCaption(caption)).build()

    private fun docMsg() = WhatsAppE2EProto.Message.newBuilder()
        .setDocumentMessage(WhatsAppE2EProto.DocumentMessage.newBuilder().setTitle("t")).build()

    private fun stickerMsg() = WhatsAppE2EProto.Message.newBuilder()
        .setStickerMessage(WhatsAppE2EProto.StickerMessage.newBuilder()).build()

    private fun contactMsg() = WhatsAppE2EProto.Message.newBuilder()
        .setContactMessage(WhatsAppE2EProto.ContactMessage.newBuilder().setDisplayName("Al")).build()

    private fun locationMsg() = WhatsAppE2EProto.Message.newBuilder()
        .setLocationMessage(WhatsAppE2EProto.LocationMessage.newBuilder()).build()

    @Test
    fun pollCreation_picksNewestVersionPresent() {
        assertNull(WhatsAppProtocol.pollCreation(null))
        assertNull(WhatsAppProtocol.pollCreation(WhatsAppE2EProto.Message.getDefaultInstance()))
        val v1 = WhatsAppE2EProto.PollCreationMessage.newBuilder().setName("v1")
        val v2 = WhatsAppE2EProto.PollCreationMessage.newBuilder().setName("v2")
        val v3 = WhatsAppE2EProto.PollCreationMessage.newBuilder().setName("v3")
        val all = WhatsAppE2EProto.Message.newBuilder()
            .setPollCreationMessage(v1).setPollCreationMessageV2(v2).setPollCreationMessageV3(v3).build()
        assertEquals("v3", WhatsAppProtocol.pollCreation(all)!!.name)
        assertEquals("poll", WhatsAppProtocol.getMessageType(all))
        val onlyV1 = WhatsAppE2EProto.Message.newBuilder().setPollCreationMessage(v1).build()
        assertEquals("v1", WhatsAppProtocol.pollCreation(onlyV1)!!.name)
    }

    @Test
    fun rerouteLIDSender_mapsThroughParticipants() {
        assertEquals("a@s.whatsapp.net", WhatsAppProtocol.rerouteLIDSender("a@s.whatsapp.net", null))
        assertEquals(
            "phone@s.whatsapp.net",
            WhatsAppProtocol.rerouteLIDSender("x@lid", mapOf("x@lid" to "phone@s.whatsapp.net")),
        )
        assertEquals("y@lid", WhatsAppProtocol.rerouteLIDSender("y@lid", emptyMap()))
    }

    @Test
    fun locationData_geoHelpers() {
        val loc = LocationData(latitude = 37.7749, longitude = -122.4194)
        assertEquals("geo:37.77490,-122.41940", loc.toGeoUri())
        assertEquals("https://maps.google.com/?q=37.77490,-122.41940", loc.toMapsUrl())
    }

    @Test
    fun whatsAppMessage_defaults() {
        val m = WhatsAppMessage(id = "i", from = "f", to = "t", body = "b", timestamp = 1, type = "text")
        assertEquals("unknown", m.messageType)
        assertEquals(emptyList(), m.mentionedJids)
        assertFalse(m.isFromMe)
        assertEquals(PollData(), PollData(question = "", options = emptyList()))
    }
}
