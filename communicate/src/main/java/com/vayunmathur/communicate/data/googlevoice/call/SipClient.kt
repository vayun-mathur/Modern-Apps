package com.vayunmathur.communicate.data.googlevoice.call

import com.vayunmathur.library.log.Log
import com.vayunmathur.communicate.data.googlevoice.GvSipRegisterInfo
import com.vayunmathur.library.network.WebSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.random.Random

/**
 * Minimal SIP-over-WebSocket client for Google Voice web calling.
 *
 * Google Voice web calls run SIP over `wss://web.voice.telephony.goog/websocket` with
 * DTLS-SRTP WebRTC media (see `voice-documentation.md` → "Voice Calling (SIP over WebSocket)").
 * Outbound frames are readable SIP text; the registrar is `*.pbx.voice.sip.google.com`.
 *
 * This implements the observed lifecycle:
 * ```
 * REGISTER -> 200
 * Outbound: INVITE(SDP offer) -> 100/180/183 -> PRACK -> 200 OK(SDP) -> ACK -> media -> BYE
 * Inbound:  INVITE(recv) -> 180 Ringing -> 200 OK(answer) / 603 Decline / 487
 * ```
 *
 * ⚠️ The exact SIP auth (digest realm/credentials mapping) and some header specifics were not
 * recoverable from the HAR (inbound frames were binary/compressed). This is a structurally
 * complete best-effort that will need on-device iteration — the plan flags Phase 5 as the most
 * likely to need tuning. Transport uses the repo's [WebSocketClient] (no OkHttp).
 */
class SipClient(
    private val registerInfo: GvSipRegisterInfo,
    private val gvNumber: String,
    private val scope: CoroutineScope,
    private val listener: Listener,
) {
    /** Callbacks for SIP registration, provisional responses and remote SDP on the Google Voice call leg. */
    interface Listener {
        fun onRegistered()
        fun onProvisional(code: Int)
        /** Remote answer SDP for our outbound INVITE. */
        fun onAnswered(remoteSdp: String)
        /** Early-media SDP delivered in a reliable provisional (183) before the final 200. */
        fun onEarlyMedia(remoteSdp: String)
        fun onEnded()
        fun onFailed(reason: String)
        /** Inbound INVITE: remote offer SDP + caller number. */
        fun onIncomingInvite(remoteSdp: String, from: String)
    }

    private var socket: WebSocketClient? = null
    private var readJob: Job? = null

    internal val localHost = "${randomToken(12)}.invalid"
    internal val callId = UUID.randomUUID().toString()
    internal val fromTag = randomToken(8)
    private var toTag: String? = null
    internal var cseq = 1
    private var lastInviteSdp: String? = null
    private var inviteTarget: String? = null
    private var inviteCseq = 0
    private var remoteContact: String? = null
    private var routeSet: List<String> = emptyList()
    private var lastExpires = 3600
    private var registerRetried = false
    private var authRetried = false
    private var inboundInvite: InboundInvite? = null

    // The SIP AOR/username is credential[0]; the digest password is credential[1] (from
    // sipregisterinfo/get). The AOR user is credential[0] URL-encoded ("=" -> "%3D").
    internal val authUsername = registerInfo.credentials.getOrNull(0) ?: gvNumber
    internal val authPassword = registerInfo.credentials.getOrNull(1) ?: ""
    internal val aorUser = authUsername.replace("=", "%3D")
    internal val contactUser = randomToken(8)

    suspend fun connect() {
        if (socket != null) return
        socket = WebSocketClient.connect(
            urlStr = WS_URL,
            headers = mapOf(
                "Sec-WebSocket-Protocol" to "sip",
                "Origin" to "https://voice.google.com",
            ),
        )
        readJob = scope.launch(Dispatchers.IO) {
            val s = socket ?: return@launch
            s.incomingFlow().collect { frame ->
                val text = when (frame) {
                    is WebSocketClient.WsFrame.Text -> frame.text
                    // Inbound frames were binary/compressed in the HAR; try a UTF-8 view.
                    is WebSocketClient.WsFrame.Binary ->
                        runCatching { frame.bytes.toString(Charsets.UTF_8) }.getOrNull()
                    is WebSocketClient.WsFrame.Close -> { listener.onEnded(); null }
                    else -> null
                }
                if (!text.isNullOrBlank()) handleIncoming(text)
            }
        }
    }

    suspend fun register(expires: Int = 3600, authHeader: String? = null) {
        lastExpires = expires
        val msg = buildRequest(
            method = "REGISTER",
            requestUri = "sip:$SIP_DOMAIN",
            to = fromUri(),
            extraHeaders = listOfNotNull(
                "Expires: $expires",
                authHeader?.let { "Authorization: $it" },
            ),
        )
        send(msg)
    }

    /** Place an outbound call with the given ICE-complete SDP offer. */
    suspend fun invite(target: String, sdpOffer: String) {
        lastInviteSdp = sdpOffer
        inviteTarget = target
        val msg = buildRequest(
            method = "INVITE",
            requestUri = "sip:$target@$SIP_DOMAIN",
            to = "<sip:$target@$SIP_DOMAIN>",
            body = sdpOffer,
            contentType = "application/sdp",
        )
        inviteCseq = cseq
        send(msg)
    }

    /** Acknowledge a reliable provisional response (100rel) so the call can proceed. */
    private suspend fun prack(target: String, rseq: Int) {
        val msg = buildRequest(
            method = "PRACK",
            requestUri = "sip:$target@$SIP_DOMAIN",
            to = "<sip:$target@$SIP_DOMAIN>${toTag?.let { ";tag=$it" } ?: ""}",
            extraHeaders = listOf("RAck: $rseq $inviteCseq INVITE"),
        )
        send(msg)
    }

    suspend fun ack(target: String) {
        // In-dialog: send to the remote Contact with the route set, reusing the INVITE CSeq.
        val uri = remoteContact ?: "sip:$target@$SIP_DOMAIN"
        val msg = buildRequest(
            method = "ACK",
            requestUri = uri,
            to = "<sip:$target@$SIP_DOMAIN>${toTag?.let { ";tag=$it" } ?: ""}",
            extraHeaders = routeSet.map { "Route: $it" },
            cseqOverride = inviteCseq,
        )
        send(msg)
    }

    suspend fun bye(target: String) {
        // Must target the remote Contact URI and carry the dialog route set, or Google can't
        // match the dialog and the far leg never hangs up.
        val uri = remoteContact ?: "sip:$target@$SIP_DOMAIN"
        val msg = buildRequest(
            method = "BYE",
            requestUri = uri,
            to = "<sip:$target@$SIP_DOMAIN>${toTag?.let { ";tag=$it" } ?: ""}",
            extraHeaders = routeSet.map { "Route: $it" },
        )
        send(msg)
    }

    /** Answer an inbound INVITE with our answer SDP (200 OK). */
    suspend fun answerInbound(answerSdp: String) {
        val invite = inboundInvite ?: return
        send(buildInboundResponse(invite, OK, "OK", body = answerSdp, contentType = "application/sdp"))
    }

    /** Decline an inbound INVITE (603). */
    suspend fun declineInbound() {
        val invite = inboundInvite ?: return
        send(buildInboundResponse(invite, DECLINE, "Decline"))
        inboundInvite = null
    }

    fun close() {
        readJob?.cancel()
        readJob = null
        val s = socket
        socket = null
        inboundInvite = null
        scope.launch { runCatching { s?.close() } }
    }

    // ------------------------------------------------------------------

    private suspend fun send(message: String) {
        Log.dev(TAG, "SIP >>\n$message")
        socket?.send(message)
    }

    private fun handleIncoming(message: String) {
        Log.dev(TAG, "SIP <<\n$message")
        val firstLine = message.lineSequence().firstOrNull()?.trim().orEmpty()
        val headers = parseHeaders(message)
        headers["to"]?.let { extractTag(it)?.let { t -> toTag = t } }
        val body = message.substringAfter("\r\n\r\n", "").ifBlank { message.substringAfter("\n\n", "") }

        captureInviteDialog(message, headers, firstLine)

        when {
            firstLine.startsWith("SIP/2.0") -> handleResponse(headers, firstLine, body)
            firstLine.startsWith("INVITE", true) -> handleInvite(message, headers, body)
            firstLine.startsWith("CANCEL", true) -> handleCancel(message, headers)
            firstLine.startsWith("BYE", true) -> handleBye(message, headers)
        }
    }

    /**
     * Capture the dialog remote target + route set from INVITE responses (183/200), needed
     * so in-dialog ACK/BYE route correctly and actually tear the call down.
     */
    private fun captureInviteDialog(message: String, headers: Map<String, String>, firstLine: String) {
        val cseqHeaderTop = headers["cseq"].orEmpty()
        val codeTop = if (firstLine.startsWith("SIP/2.0")) {
            firstLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        } else {
            -1
        }
        if (cseqHeaderTop.contains("INVITE", true) && codeTop in DIALOG_CODES) {
            if (remoteContact == null) extractContact(message)?.let { remoteContact = it }
            if (routeSet.isEmpty()) {
                val rr = extractRecordRoutes(message)
                if (rr.isNotEmpty()) routeSet = rr.reversed()
            }
        }
    }

    /** Route a SIP response by status code + CSeq method. */
    private fun handleResponse(
        headers: Map<String, String>,
        firstLine: String,
        body: String,
    ) {
        val code = firstLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        val cseqHeader = headers["cseq"].orEmpty()
        if (handleRegisterResponse(code, cseqHeader, headers)) return
        val isInvite = cseqHeader.contains("INVITE", true)
        when {
            code == OK && isInvite -> listener.onAnswered(body)
            else -> handleTerminalResponse(code, headers, body, firstLine)
        }
    }

    /** REGISTER lifecycle (interval retry, digest challenge, success). True when consumed. */
    private fun handleRegisterResponse(
        code: Int,
        cseqHeader: String,
        headers: Map<String, String>,
    ): Boolean {
        if (!cseqHeader.contains("REGISTER", true)) return false
        when {
            // Registrar wants a longer registration interval; retry once at Min-Expires.
            code == INTERVAL_TOO_BRIEF && !registerRetried -> {
                registerRetried = true
                val min = headers["min-expires"]?.toIntOrNull() ?: (lastExpires * 2)
                scope.launch { runCatching { register(min) } }
            }
            // Digest challenge: compute the response and re-REGISTER with Authorization.
            isAuthChallenge(code) && !authRetried -> {
                authRetried = true
                respondToChallenge(headers)
            }
            code == OK -> listener.onRegistered()
            else -> return false
        }
        return true
    }

    private fun isAuthChallenge(code: Int): Boolean =
        code == UNAUTHORIZED || code == PROXY_AUTH_REQUIRED

    /** Provisional, terminal-error and failure responses, independent of the CSeq method. */
    private fun handleTerminalResponse(
        code: Int,
        headers: Map<String, String>,
        body: String,
        firstLine: String,
    ) {
        when {
            code in PROVISIONAL_CODES -> handleProvisional(headers, body, code)
            code == REQUEST_TERMINATED || code == DECLINE || code == BUSY_HERE -> listener.onEnded()
            code in FAILURE_CODES -> listener.onFailed("SIP $firstLine")
        }
    }

    /** Answer a digest challenge with an authorized re-REGISTER. */
    private fun respondToChallenge(headers: Map<String, String>) {
        val challenge = headers["www-authenticate"] ?: headers["proxy-authenticate"] ?: ""
        val nonce = extractParam(challenge, "nonce") ?: ""
        val realm = extractParam(challenge, "realm") ?: SIP_DOMAIN
        val auth = digestAuth("REGISTER", "sip:$SIP_DOMAIN", nonce, realm)
        scope.launch { runCatching { register(lastExpires, auth) } }
    }

    /** Provisional response: emit + handle reliable (100rel) early media. */
    private fun handleProvisional(
        headers: Map<String, String>,
        body: String,
        code: Int,
    ) {
        listener.onProvisional(code)
        // Reliable provisional (100rel) carries the SDP answer + RSeq; apply the
        // early media and PRACK it, or the registrar drops the call with 504.
        if (body.contains("m=", ignoreCase = true)) listener.onEarlyMedia(body)
        val rseq = headers["rseq"]?.toIntOrNull()
        val tgt = inviteTarget
        if (rseq != null && tgt != null) {
            scope.launch { runCatching { prack(tgt, rseq) } }
        }
    }

    /** Inbound INVITE: acknowledge + ring + notify. */
    private fun handleInvite(message: String, headers: Map<String, String>, body: String) {
        val invite = InboundInvite.from(message, headers)
        inboundInvite = invite
        scope.launch {
            runCatching { send(buildInboundResponse(invite, TRYING, "Trying")) }
            runCatching { send(buildInboundResponse(invite, RINGING, "Ringing")) }
        }
        val from = headers["from"]?.let { extractUserFromHeader(it) } ?: "Unknown"
        listener.onIncomingInvite(body, from)
    }

    /** Inbound CANCEL: acknowledge + terminate the pending invite. */
    private fun handleCancel(message: String, headers: Map<String, String>) {
        val invite = inboundInvite
        scope.launch {
            runCatching { send(buildCancelOk(message, headers)) }
            if (invite != null) {
                runCatching { send(buildInboundResponse(invite, REQUEST_TERMINATED, "Request Terminated")) }
            }
        }
        inboundInvite = null
        listener.onEnded()
    }

    /** Inbound BYE: acknowledge + end. */
    private fun handleBye(message: String, headers: Map<String, String>) {
        scope.launch { runCatching { send(buildByeOk(message, headers)) } }
        inboundInvite = null
        listener.onEnded()
    }



    /** Registrar endpoint and logging constants for [SipClient]. */
    companion object {
        private const val TAG = "SipClient"
        private const val WS_URL = "wss://web.voice.telephony.goog/websocket"
        // Registrar/domain observed in capture 2 (User-Agent: GoogleVoice; PBX host).
        internal const val SIP_DOMAIN = "web.c.pbx.voice.sip.google.com"

        // SIP status codes.
        internal const val TRYING = 100
        private const val RINGING = 180
        internal const val OK = 200
        private const val UNAUTHORIZED = 401
        private const val PROXY_AUTH_REQUIRED = 407
        private const val INTERVAL_TOO_BRIEF = 423
        private const val REQUEST_TERMINATED = 487
        private const val BUSY_HERE = 486
        private const val DECLINE = 603
        internal const val TAG_TOKEN_LENGTH = 8
        private val DIALOG_CODES = RINGING..299
        private val PROVISIONAL_CODES = TRYING..199
        private val FAILURE_CODES = 400..699
    }
}

internal fun randomToken(len: Int): String {
    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    return (1..len).map { chars[Random.nextInt(chars.length)] }.joinToString("")
}
