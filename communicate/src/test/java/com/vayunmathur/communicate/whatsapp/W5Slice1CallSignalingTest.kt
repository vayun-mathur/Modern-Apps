package com.vayunmathur.communicate.whatsapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.call.WhatsAppCallSignaling
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 1 (w5): call signaling builders/parsers (WhatsAppCallSignaling.kt).
 * Robolectric: uses android.util.Base64 for the webrtc SDP/ICE extension.
 */
@RunWith(AndroidJUnit4::class)
class W5Slice1CallSignalingTest {

    @Test
    fun offer_videoWithSdpAndCandidates_shape() {
        val encs = listOf(WhatsAppProtocol.ParticipantEnc("1:0@s.whatsapp.net", "msg", byteArrayOf(7)))
        val node = WhatsAppCallSignaling.buildOffer(
            to = "peer@s.whatsapp.net", callId = "CALL1", callCreator = "me@s.whatsapp.net",
            video = true, encs = encs, stanzaId = "S1",
            sdp = "v=0 fake sdp", candidates = listOf("cand-a", "cand-b"),
        )
        assertEquals("call", node.tag)
        assertEquals("S1", node.attrs["id"])
        val offer = node.getChildByTag("offer")!!
        assertEquals("CALL1", offer.attrs["call-id"])
        assertTrue(offer.getChildByTag("video") != null)
        val webrtc = offer.getChildByTag("webrtc")!!
        assertEquals("v=0 fake sdp", WhatsAppCallSignaling.extractSdp(webrtc))
        assertEquals(listOf("cand-a", "cand-b"), WhatsAppCallSignaling.extractCandidates(webrtc))
        // one <to> destination per enc
        assertEquals(1, offer.content.count { (it as WhatsAppProtocol.Node).tag == "to" })
    }

    @Test
    fun offer_audioNoSdp_noWebrtcChild() {
        val node = WhatsAppCallSignaling.buildOffer(
            "p@s.whatsapp.net", "C2", "me@s.whatsapp.net", false, emptyList(), "S2",
        )
        val offer = node.getChildByTag("offer")!!
        val audio = offer.getChildByTag("audio")!!
        assertEquals("opus", audio.attrs["enc"])
        assertNull(offer.getChildByTag("webrtc"))
    }

    @Test
    fun preAccept_accept_reject_terminate_shapes() {
        val pre = WhatsAppCallSignaling.buildPreAccept("p@s.whatsapp.net", "C3", "me@s.whatsapp.net", true, "S3", sdp = "sdp-a")
        assertTrue(pre.getChildByTag("preaccept")!!.getChildByTag("video") != null)
        assertEquals("sdp-a", WhatsAppCallSignaling.extractSdp(pre.getChildByTag("preaccept")!!.getChildByTag("webrtc")!!))

        val acc = WhatsAppCallSignaling.buildAccept("p@s.whatsapp.net", "C4", "me@s.whatsapp.net", "S4")
        assertTrue(acc.getChildByTag("accept") != null)

        val rej = WhatsAppCallSignaling.buildReject("p@s.whatsapp.net", "C5", "me@s.whatsapp.net", "S5")
        assertEquals("C5", rej.getChildByTag("reject")!!.attrs["call-id"])

        val term = WhatsAppCallSignaling.buildTerminate("p@s.whatsapp.net", "C6", "me@s.whatsapp.net", "timeout", "S6")
        assertEquals("timeout", term.getChildByTag("terminate")!!.attrs["reason"])
    }

    @Test
    fun iceUpdate_carriesCandidates() {
        val node = WhatsAppCallSignaling.buildIceUpdate("p@s.whatsapp.net", "C7", "me@s.whatsapp.net", "S7", listOf("c1"))
        val relay = node.getChildByTag("relay")!!
        assertEquals(listOf("c1"), WhatsAppCallSignaling.extractCandidates(relay.getChildByTag("webrtc")!!))
    }

    @Test
    fun webrtcChild_empty_returnsNull() {
        assertNull(WhatsAppCallSignaling.webrtcChild(null, emptyList()))
        assertEquals(null, WhatsAppCallSignaling.extractSdp(WhatsAppProtocol.Node(tag = "webrtc")))
        assertEquals(emptyList(), WhatsAppCallSignaling.extractCandidates(WhatsAppProtocol.Node(tag = "webrtc")))
    }

    @Test
    fun parse_offerRoundTrip() {
        val encs = listOf(WhatsAppProtocol.ParticipantEnc("1:0@s.whatsapp.net", "pkmsg", byteArrayOf(1, 2)))
        val built = WhatsAppCallSignaling.buildOffer(
            "peer@s.whatsapp.net", "CALL9", "caller@s.whatsapp.net", false, encs, "ST9",
            sdp = "v=0 sdp", candidates = listOf("cc"),
        )
        // inbound stanzas carry from=
        val inbound = WhatsAppProtocol.Node(
            tag = "call",
            attrs = built.attrs + mapOf("from" to "caller@s.whatsapp.net"),
            content = built.content,
        )
        val parsed = WhatsAppCallSignaling.parse(inbound)!!
        assertEquals(WhatsAppCallSignaling.InboundCall.Kind.OFFER, parsed.kind)
        assertEquals("CALL9", parsed.callId)
        assertEquals("caller@s.whatsapp.net", parsed.from)
        assertEquals("caller@s.whatsapp.net", parsed.creator)
        assertEquals(false, parsed.isVideo)
        assertEquals("v=0 sdp", parsed.sdp)
        assertEquals(listOf("cc"), parsed.candidates)
        assertEquals("ST9", parsed.stanzaId)
    }

    @Test
    fun parse_nonCallOrMissingFrom_returnsNull() {
        assertNull(WhatsAppCallSignaling.parse(WhatsAppProtocol.Node(tag = "iq")))
        assertNull(WhatsAppCallSignaling.parse(WhatsAppProtocol.Node(tag = "call", content = listOf(WhatsAppProtocol.Node(tag = "offer")))))
        assertNull(WhatsAppCallSignaling.parse(WhatsAppProtocol.Node(tag = "call", attrs = mapOf("from" to "a"))))
    }

    @Test
    fun parse_unknownKind_mapsToUnknown() {
        val node = WhatsAppProtocol.Node(
            tag = "call",
            attrs = mapOf("from" to "a@s.whatsapp.net", "id" to "Z1"),
            content = listOf(WhatsAppProtocol.Node(tag = "weird", attrs = mapOf("call-id" to "CZ"))),
        )
        val parsed = WhatsAppCallSignaling.parse(node)!!
        assertEquals(WhatsAppCallSignaling.InboundCall.Kind.UNKNOWN, parsed.kind)
        assertEquals("CZ", parsed.callId)
    }

    @Test
    fun parse_videoChild_setsIsVideo() {
        val built = WhatsAppCallSignaling.buildOffer("p@s.whatsapp.net", "CV", "me@s.whatsapp.net", true, emptyList(), "SV")
        val inbound = WhatsAppProtocol.Node(
            tag = "call", attrs = built.attrs + mapOf("from" to "me@s.whatsapp.net"), content = built.content,
        )
        assertEquals(true, WhatsAppCallSignaling.parse(inbound)!!.isVideo)
    }
}
