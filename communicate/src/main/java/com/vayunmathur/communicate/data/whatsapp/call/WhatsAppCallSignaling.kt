package com.vayunmathur.communicate.data.whatsapp.call

import android.util.Base64
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol.Node

/**
 * WhatsApp call **signaling** (Phase D 3a). Builds/parses the `<call>` XMPP stanzas that ride the
 * same Noise socket as messaging (offer / preaccept / accept / reject / terminate), with per-device
 * E2E `<enc>` children carrying the 32-byte call key (see [WhatsAppCallCrypto]).
 *
 * Because WhatsApp's wire carries **no SDP** (its media is a proprietary native RTP engine), we
 * transport standards-based WebRTC SDP + ICE candidates inside our **own** `<webrtc>` child of the
 * `<call>` action. This makes calling interoperate **client-to-client (our app ↔ our app)** but NOT
 * with official WhatsApp calling servers — an accepted, documented limitation of the dev scaffolding.
 *
 * Reference: whatsapp-documentation.md "Voice Calls And Video Calls" (SignalingXmppCallback
 * `sendCallStanza`, OutgoingSignalingHandler per-destination `enc` children) + signaling.md.
 */
object WhatsAppCallSignaling {

    /** Our WebRTC transport extension tag (not part of the official WA wire). */
    private const val WEBRTC_TAG = "webrtc"

    /** A parsed inbound call stanza. */
    data class InboundCall(
        val callId: String,
        val from: String,
        val creator: String,
        val isVideo: Boolean,
        val kind: Kind,
        /** The `<enc>` carrying the call key, targeted to our device (null on accept/reject/etc.). */
        val enc: Enc? = null,
        /** WebRTC offer/answer SDP carried in our `<webrtc>` extension, if present. */
        val sdp: String? = null,
        /** Trickle/gathered ICE candidate lines from our `<webrtc>` extension. */
        val candidates: List<String> = emptyList(),
        val stanzaId: String = "",
    ) {
        /** The kind of a WhatsApp call signalling stanza. */
        enum class Kind { OFFER, PREACCEPT, ACCEPT, REJECT, TERMINATE, RELAY, UNKNOWN }
    }

    /** A single Signal `<enc>` (type = pkmsg|msg, ciphertext). */
    data class Enc(val type: String, val ciphertext: ByteArray)

    // ------------------------------------------------------------------ builders

    /**
     * Build an outbound call offer.
     * @param encs per-device encrypted call-key children (dev jid, enc type, ciphertext).
     * @param sdp / [candidates] our WebRTC offer carried in the `<webrtc>` extension.
     */
    fun buildOffer(
        to: String,
        callId: String,
        callCreator: String,
        video: Boolean,
        encs: List<WhatsAppProtocol.ParticipantEnc>,
        stanzaId: String,
        sdp: String? = null,
        candidates: List<String> = emptyList(),
    ): Node {
        val destinations = encs.map { enc ->
            Node(
                tag = "to",
                attrs = mapOf("jid" to enc.deviceJid),
                content = listOf(
                    Node(tag = "enc", attrs = mapOf("v" to "2", "type" to enc.encType), data = enc.ciphertext),
                ),
            )
        }
        val offer = Node(
            tag = "offer",
            attrs = mapOf("call-id" to callId, "call-creator" to callCreator),
            content = buildList {
                add(mediaMarker(video))
                add(Node(tag = "net", attrs = mapOf("medium" to "3")))
                add(Node(tag = "encopt", attrs = mapOf("keygen" to "2")))
                webrtcChild(sdp, candidates)?.let { add(it) }
                addAll(destinations)
            },
        )
        return call(to, stanzaId, offer)
    }

    /** Pre-accept (early, before the user picks up) — lets media/ICE start during ringing. */
    fun buildPreAccept(
        to: String,
        callId: String,
        callCreator: String,
        video: Boolean,
        stanzaId: String,
        sdp: String? = null,
        candidates: List<String> = emptyList(),
    ): Node {
        val preaccept = Node(
            tag = "preaccept",
            attrs = mapOf("call-id" to callId, "call-creator" to callCreator),
            content = buildList {
                add(mediaMarker(video))
                webrtcChild(sdp, candidates)?.let { add(it) }
            },
        )
        return call(to, stanzaId, preaccept)
    }

    /** Accept an inbound call, carrying our WebRTC answer. */
    fun buildAccept(
        to: String,
        callId: String,
        callCreator: String,
        stanzaId: String,
        video: Boolean = false,
        sdp: String? = null,
        candidates: List<String> = emptyList(),
    ): Node {
        val accept = Node(
            tag = "accept",
            attrs = mapOf("call-id" to callId, "call-creator" to callCreator),
            content = buildList {
                add(mediaMarker(video))
                webrtcChild(sdp, candidates)?.let { add(it) }
            },
        )
        return call(to, stanzaId, accept)
    }

    fun buildReject(to: String, callId: String, callCreator: String, stanzaId: String): Node =
        call(to, stanzaId, Node(tag = "reject", attrs = mapOf("call-id" to callId, "call-creator" to callCreator)))

    fun buildTerminate(to: String, callId: String, callCreator: String, reason: String, stanzaId: String): Node =
        call(
            to, stanzaId,
            Node(
                tag = "terminate",
                attrs = mapOf("call-id" to callId, "call-creator" to callCreator, "reason" to reason)),
        )

    /** Trickle a batch of ICE candidates mid-call inside a `<transport>`-like `<webrtc>` node. */
    fun buildIceUpdate(
        to: String,
        callId: String,
        callCreator: String,
        stanzaId: String,
        candidates: List<String>): Node =
        call(
            to, stanzaId,
            Node(
                tag = "relay",
                attrs = mapOf("call-id" to callId, "call-creator" to callCreator),
                content = listOfNotNull(webrtcChild(null, candidates)),
            ),
        )

    // ------------------------------------------------------------------ parse

    /** Parse an inbound `<call>` stanza into a typed [InboundCall]. */
    fun parse(node: Node): InboundCall? {
        if (node.tag != "call") return null
        val from = node.attrs["from"] ?: node.attrs["participant"] ?: return null
        val child = node.getChildren().firstOrNull() ?: return null
        val kind = kindFor(child.tag)
        val callId = child.attrs["call-id"] ?: node.attrs["id"] ?: ""
        val creator = child.attrs["call-creator"] ?: from
        val isVideo = child.getChildren().any { it.tag == "video" }
        val encNode = child.getChildByTag("enc")
        val enc = encNode?.data?.let { Enc(encNode.attrs["type"] ?: "msg", it) }
        val webrtc = child.getChildByTag(WEBRTC_TAG)
        return InboundCall(
            callId = callId,
            from = from,
            creator = creator,
            isVideo = isVideo,
            kind = kind,
            enc = enc,
            sdp = webrtc?.let { extractSdp(it) },
            candidates = webrtc?.let { extractCandidates(it) } ?: emptyList(),
            stanzaId = node.attrs["id"] ?: "",
        )
    }

    /** Map a call child tag to its kind. */
    private fun kindFor(tag: String): InboundCall.Kind = when (tag) {
        "offer" -> InboundCall.Kind.OFFER
        "preaccept" -> InboundCall.Kind.PREACCEPT
        "accept" -> InboundCall.Kind.ACCEPT
        "reject" -> InboundCall.Kind.REJECT
        "terminate" -> InboundCall.Kind.TERMINATE
        "relay" -> InboundCall.Kind.RELAY
        else -> InboundCall.Kind.UNKNOWN
    }

    // ------------------------------------------------------------------ webrtc extension helpers

    /**
     * Build our `<webrtc>` transport extension: base64 SDP as the `<sdp>` node data plus one
     * `<ice>` node per candidate. Returns null when there's nothing to carry.
     */
    fun webrtcChild(sdp: String?, candidates: List<String>): Node? {
        if (sdp == null && candidates.isEmpty()) return null
        val children = buildList {
            if (sdp != null) {
                add(Node(tag = "sdp", data = Base64.encode(sdp.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)))
            }
            candidates.forEach { c ->
                add(Node(tag = "ice", data = Base64.encode(c.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)))
            }
        }
        return Node(tag = WEBRTC_TAG, content = children)
    }

    fun extractSdp(webrtc: Node): String? =
        webrtc.getChildByTag("sdp")?.data?.let { String(Base64.decode(it, Base64.NO_WRAP), Charsets.UTF_8) }

    fun extractCandidates(webrtc: Node): List<String> =
        webrtc.getChildren().filter { it.tag == "ice" }.mapNotNull { ice ->
            ice.data?.let { String(Base64.decode(it, Base64.NO_WRAP), Charsets.UTF_8) }
        }

    // ------------------------------------------------------------------ small builders

    private fun mediaMarker(video: Boolean): Node =
        if (video) Node(tag = "video") else Node(tag = "audio", attrs = mapOf("enc" to "opus", "rate" to "16000"))

    private fun call(to: String, stanzaId: String, action: Node): Node =
        Node(tag = "call", attrs = mapOf("to" to to, "id" to stanzaId), content = listOf(action))
}
