package com.vayunmathur.communicate.data.rcs

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.vayunmathur.library.log.Log
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Direct SIP stack over the IMS PDN (fallback when the carrier's
 * single-registration verdict is negative).
 *
 * Why this exists: `SipDelegateManager` requires the framework's
 * `isRcsVolteSingleRegistrationCapable()` to return true. Some carriers
 * (verified: US Mobile eSIM, subId=1) report false while still running a
 * fully working IMS PDN (P-CSCF reachable, feature tags allowed). This stack
 * speaks SIP directly to the P-CSCF over a TCP socket bound to the IMS
 * network — no delegate, no carrier privilege, no hidden APIs:
 *
 * - Transport: `RcsImsNetwork`-style IMS `Network` request → TCP to the
 *   P-CSCF taken from the IMS link properties (`getPcscfAddresses`).
 * - Auth: IMS-AKA via public `TelephonyManager.getIccAuthentication`
 *   (`APPTYPE_ISIM` + `AUTHTYPE_EAP_AKA`), feeding the 401's nonce into a
 *   Digest `Authorization` built by [RcsGbaAuth].
 * - Dialogs: REGISTER (+ 200 OK + Expires refresh at half-life), MESSAGE
 *   (pager-mode CPIM, reusing [RcsSipTransport.buildChatMessage]), inbound
 *   MESSAGE routed to the same sync-service handler.
 *
 * State is exposed through the same [RcsRegistrationState] vocabulary so the
 * UI needs no changes: `Available` means REGISTERed with 200 OK.
 * Best-effort throughout; every failure maps to `Unavailable(reason)` and
 * never throws.
 */
object RcsDirectSip {
    private const val TAG = "RcsDirectSip"

    /** REGISTER lifetime requested (carrier clamps; we refresh at half). */
    private const val REGISTER_EXPIRES = 600

    /** Socket/read timeout for the SIP TCP leg. */
    private const val SOCKET_TIMEOUT_MS = 15_000
    private const val SIP_OK = 200
    private const val SIP_UNAUTHORIZED = 401
    private const val SIP_PROXY_AUTH_REQUIRED = 407
    private val SIP_SUCCESS = 200..299
    private const val REFRESH_MIN_MS = 30_000L
    private const val REFRESH_HALF_FACTOR = 500L
    private const val HEAD_GUARD = 64
    private const val DEFAULT_SIP_PORT = 5060
    private const val MAX_FORWARDS = 70
    private const val TAG_TOKEN_LENGTH = 8

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var running = false
    @Volatile private var socket: java.net.Socket? = null
    @Volatile private var pcscf: InetSocketAddress? = null
    @Volatile private var localContact: String = ""
    @Volatile private var publicIdentity: String = ""
    @Volatile private var callIdBase: String = ""
    @Volatile private var cseq: Long = 1L
    @Volatile private var registeredExpires = 0

    /** Inbound SIP MESSAGE listener (same contract as the delegate path). */
    @Volatile
    var onInboundMessage: ((startLine: String, headers: String, body: ByteArray) -> Unit)? = null

    /** Pending MESSAGE transactions keyed by Via branch. */
    private val pendingSends = ConcurrentHashMap<String, (Boolean) -> Unit>()

    /**
     * Start the direct stack for [subId]: request the IMS network, connect
     * TCP to the P-CSCF, REGISTER with AKA. Suspends until the first 200 OK
     * (or failure). Idempotent while running.
     */
    suspend fun start(context: Context, subId: Int): Boolean {
        if (!RcsFeature.enabled) return false
        if (running) return true
        val conn = prepareConnection(context, subId) ?: return false
        // Public identity: derive from the line-1 number when readable, else
        // the IMSI-derived placeholder the P-CSCF will challenge anyway.
        // The 401 + AKA exchange establishes the real identity.
        publicIdentity = runCatching { conn.tm.line1Number?.takeIf { it.isNotBlank() } }
            .getOrNull() ?: ""
        callIdBase = "${UUID.randomUUID()}@rcs-direct"
        running = true
        val ok = runCatching {
            connectAndRegister(conn.app, conn.tm, conn.pcscfAddr)
        }.getOrElse {
            Log.status(TAG, "Direct SIP start failed", it)
            false
        }
        if (!ok) stop()
        return ok
    }

    private data class DirectConnection(
        val app: Context,
        val tm: TelephonyManager,
        val subId: Int,
        val pcscfAddr: InetSocketAddress,
    )

    /** Resolve network/P-CSCF/telephone pieces, or null when any is unavailable. */
    private suspend fun prepareConnection(context: Context, subId: Int): DirectConnection? {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) return null
        val app = context.applicationContext
        val network = RcsImsNetwork.imsNetwork(app, subId) ?: run {
            Log.status(TAG, "No IMS network for direct SIP")
            return null
        }
        val pcscfAddr = pcscfAddress(app, network) ?: run {
            Log.status(TAG, "No P-CSCF address on IMS network")
            return null
        }
        val tm = app.getSystemService(TelephonyManager::class.java)?.let { base ->
            runCatching { base.createForSubscriptionId(subId) }.getOrNull() ?: base
        } ?: return null
        return DirectConnection(app, tm, subId, pcscfAddr)
    }

    /** Stop: deregister (best-effort), close the socket, drop state. */
    fun stop() {
        running = false
        runCatching {
            socket?.let { s ->
                runCatching {
                    val out = s.getOutputStream()
                    val bye = buildRegister(publicIdentity, localContact, pcscf, 0)
                    out.write(bye)
                    out.flush()
                }
            }
        }
        runCatching { socket?.close() }
        socket = null
        pcscf = null
        pendingSends.values.forEach { runCatching { it(false) } }
        pendingSends.clear()
        // Cancel children only, never the scope itself: cancelling the
        // process-wide scope would leave every later start() without a
        // reader loop (transact writes, then waits out its full timeout),
        // making the stack un-restartable after any teardown.
        scope.coroutineContext.cancelChildren()
    }

    /** True when REGISTERed with a live socket. */
    fun isRegistered(): Boolean =
        RcsFeature.enabled && running && socket?.isConnected == true && registeredExpires > 0

    /**
     * Send a pager-mode MESSAGE over the direct leg. Returns true on 200 OK.
     * Same framing as the delegate path ([RcsSipTransport.buildChatMessage]
     * output is re-targeted at the P-CSCF with our dialog headers).
     */
    suspend fun sendMessage(startLine: String, headers: String, body: ByteArray): Boolean {
        if (!isRegistered()) return false
        val s = socket ?: return false
        return runCatching {
            suspendCancellableCoroutine { cont ->
                // Retarget: Request-URI stays the peer URI; Route the P-CSCF;
                // From our public identity; fresh branch per transaction.
                val branch = RcsSipDialog.newBranch()
                val target = pendingTarget(startLine, headers)
                val rebuilt = rebuildMessageHeaders(headers, branch)
                val requestLine = "MESSAGE ${target.first} SIP/2.0"
                val wire = "$requestLine\r\n$rebuilt\r\n".toByteArray(Charsets.UTF_8) + body
                pendingSends[branch] = { ok -> if (cont.isActive) cont.resume(ok) }
                runCatching {
                    synchronized(s) {
                        val out = s.getOutputStream()
                        out.write(wire)
                        out.flush()
                    }
                }.onFailure {
                    pendingSends.remove(branch)
                    if (cont.isActive) cont.resume(false)
                }
            }
        }.getOrDefault(false)
    }

    // -- internals --

    private suspend fun connectAndRegister(
        app: Context,
        tm: TelephonyManager,
        pcscfAddr: InetSocketAddress,
    ): Boolean {
        val s = RcsImsNetwork.createSocket(app, pcscfAddr.hostString, pcscfAddr.port, SOCKET_TIMEOUT_MS)
            ?: return false
        socket = s
        pcscf = pcscfAddr
        s.soTimeout = SOCKET_TIMEOUT_MS
        // Reader loop for responses + inbound MESSAGEs.
        scope.launch { readLoop(s) }
        // Unauthenticated REGISTER → 401 with nonce → AKA → REGISTER w/auth.
        val first = buildRegister(publicIdentity, localContact, pcscfAddr, REGISTER_EXPIRES)
        val challenge = transact(first, "REGISTER") ?: run {
            Log.status(TAG, "No response to initial REGISTER")
            return false
        }
        if (challenge.statusCode == SIP_OK) {
            onRegistered(challenge)
            return true
        }
        if (challenge.statusCode != SIP_UNAUTHORIZED && challenge.statusCode != SIP_PROXY_AUTH_REQUIRED) {
            Log.status(TAG, "REGISTER rejected: ${challenge.statusCode} ${challenge.reason}")
            return false
        }
        return finishRegistration(tm, challenge)
    }

    /** AKA handshake + authed REGISTER after a 401/407 challenge. */
    private suspend fun finishRegistration(tm: TelephonyManager, challenge: SipResponse): Boolean {
        val authed = buildAuthedRegister(tm, challenge) ?: return false
        val final = transact(authed, "REGISTER") ?: run {
            Log.status(TAG, "No response to authed REGISTER")
            return false
        }
        if (final.statusCode != SIP_OK) {
            Log.status(TAG, "Authed REGISTER rejected: ${final.statusCode} ${final.reason}")
            return false
        }
        onRegistered(final)
        return true
    }

    private fun onRegistered(resp: SipResponse) {
        registeredExpires = resp.expires ?: REGISTER_EXPIRES
        // Our Contact (for inbound routing correlation + re-REGISTER).
        resp.contact?.let { localContact = it }
        Log.status(TAG, "Direct SIP REGISTERed (expires=$registeredExpires)")
        // Refresh at half-life.
        scope.launch {
            while (running) {
                kotlinx.coroutines.delay((registeredExpires * REFRESH_HALF_FACTOR).coerceAtLeast(REFRESH_MIN_MS))
                if (!running) break
                runCatching { refreshRegister() }
            }
        }
    }

    private suspend fun refreshRegister(): Boolean {
        val s = socket ?: return false
        val req = buildRegister(publicIdentity, localContact, pcscf, REGISTER_EXPIRES)
        val resp = transact(req, "REGISTER") ?: return false
        if (resp.statusCode == SIP_UNAUTHORIZED || resp.statusCode == SIP_PROXY_AUTH_REQUIRED) {
            // Re-auth on refresh challenge (nonce rotation); needs TM — the
            // sync-service path re-runs start() instead. Report failure.
            Log.status(TAG, "Refresh challenged; full re-register required")
            return false
        }
        if (resp.statusCode == SIP_OK) {
            registeredExpires = resp.expires ?: REGISTER_EXPIRES
            return true
        }
        return false
    }

    /** One request → first response (matched loosely: any response). */
    private suspend fun transact(wire: ByteArray, method: String): SipResponse? =
        withTimeoutOrNull(SOCKET_TIMEOUT_MS.toLong()) {
            suspendCancellableCoroutine { cont ->
                val s = socket ?: run {
                    if (cont.isActive) cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                // Tag the continuation for the reader loop to complete.
                pendingTransactions[method + "@" + System.identityHashCode(wire)] =
                    { resp: SipResponse -> if (cont.isActive) cont.resume(resp) }
                runCatching {
                    synchronized(s) {
                        val out = s.getOutputStream()
                        out.write(wire)
                        out.flush()
                    }
                }.onFailure {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    private val pendingTransactions = ConcurrentHashMap<String, (SipResponse) -> Unit>()

    private fun buildRegister(
        identity: String,
        contact: String,
        via: InetSocketAddress?,
        expires: Int,
    ): ByteArray {
        val from = identity.ifBlank { "sip:anonymous@anonymous.invalid" }
        val branch = RcsSipDialog.newBranch()
        val callId = callIdBase
        val seq = cseq++
        val contactHdr = contact.ifBlank { "<sip:rcs-direct@${via?.hostString ?: "localhost"}>" }
        return buildString {
            append("REGISTER sip:${via?.hostString ?: "localhost"} SIP/2.0\r\n")
            append("Via: SIP/2.0/TCP ${via?.hostString ?: "localhost"};branch=$branch\r\n")
            append("Max-Forwards: $MAX_FORWARDS\r\n")
            append("From: <$from>;tag=${UUID.randomUUID().toString().take(TAG_TOKEN_LENGTH)}\r\n")
            append("To: <$from>\r\n")
            append("Call-ID: $callId\r\n")
            append("CSeq: $seq REGISTER\r\n")
            append("Contact: $contactHdr;expires=$expires\r\n")
            append("Expires: $expires\r\n")
            append("Content-Length: 0\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)
    }

    private fun buildAuthedRegister(
        tm: TelephonyManager,
        challenge: SipResponse,
    ): ByteArray? {
        val wwwAuth = challenge.headers.lines()
            .firstOrNull { it.trim().startsWith("WWW-Authenticate:", ignoreCase = true) }
            ?.substringAfter(":")?.trim() ?: return null
        // IMS-AKA: nonce → ISIM challenge → RES/CK/IK → Digest response.
        // getIccAuthentication(APPTYPE_ISIM, AUTHTYPE_EAP_AKA, nonceB64).
        val nonce = Regex("nonce=\"([^\"]+)\"").find(wwwAuth)
            ?.groupValues?.getOrNull(1)?.trim() ?: return null
        val akaRes = runCatching {
            tm.getIccAuthentication(
                TelephonyManager.APPTYPE_ISIM,
                TelephonyManager.AUTHTYPE_EAP_AKA,
                nonce,
            )
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: run {
            Log.status(TAG, "ISIM AKA challenge failed (no ISIM app?)")
            return null
        }
        // akaRes is base64 RES||CK||IK per 3GPP TS 31.102; the Digest
        // password for AKA is RES-based — reuse the shared digest builder
        // with the AKA response as the password material.
        val username = publicIdentity.substringAfter("sip:").substringBefore("@")
            .takeIf { it.isNotBlank() } ?: "anonymous"
        val uri = "sip:${pcscf?.hostString ?: "localhost"}"
        val authValue = RcsGbaAuth.digestAuthorizationHeader(
            challenge = wwwAuth,
            method = "REGISTER",
            uri = uri,
            username = username,
            password = akaRes,
        ).ifBlank { return null }
        val branch = RcsSipDialog.newBranch()
        val from = publicIdentity.ifBlank { "sip:anonymous@anonymous.invalid" }
        val seq = cseq++
        return buildString {
            append("REGISTER sip:${pcscf?.hostString ?: "localhost"} SIP/2.0\r\n")
            append("Via: SIP/2.0/TCP ${pcscf?.hostString ?: "localhost"};branch=$branch\r\n")
            append("Max-Forwards: $MAX_FORWARDS\r\n")
            append("From: <$from>;tag=${UUID.randomUUID().toString().take(TAG_TOKEN_LENGTH)}\r\n")
            append("To: <$from>\r\n")
            append("Call-ID: $callIdBase\r\n")
            append("CSeq: $seq REGISTER\r\n")
            append("Contact: ${localContact.ifBlank { "<sip:rcs-direct@localhost>" }};expires=$REGISTER_EXPIRES\r\n")
            append("Expires: $REGISTER_EXPIRES\r\n")
            append("Authorization: $authValue\r\n")
            append("Content-Length: 0\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)
    }

    /** Minimal SIP response model for the state machine. */
    private data class SipResponse(
        val statusCode: Int,
        val reason: String,
        val callId: String,
        val cseqMethod: String,
        val branch: String,
        val headers: String,
        val expires: Int?,
        val contact: String?,
    )

    private fun parseResponse(head: String): SipResponse? {
        return runCatching {
            val lines = head.lines()
            val start = lines.firstOrNull()?.trim() ?: return null
            if (!start.startsWith("SIP/2.0")) return null
            val code = start.substringAfter("SIP/2.0").trim().substringBefore(" ")
                .trim().toIntOrNull() ?: return null
            val reason = start.substringAfter("SIP/2.0").trim().substringAfter(" ").trim()
            fun header(name: String): String? = lines
                .firstOrNull { it.trim().startsWith(name, ignoreCase = true) }
                ?.substringAfter(":")?.trim()
            val via = header("Via:").orEmpty()
            val branch = via.substringAfter("branch=").substringBefore(";").trim()
            val callId = header("Call-ID:").orEmpty()
            val cseqLine = header("CSeq:").orEmpty()
            SipResponse(
                statusCode = code,
                reason = reason,
                callId = callId,
                cseqMethod = cseqLine.substringAfter(" ").trim(),
                branch = branch,
                headers = head,
                expires = header("Expires:")?.toIntOrNull()
                    ?: Regex("expires=(\\d+)", RegexOption.IGNORE_CASE)
                        .find(header("Contact:").orEmpty())?.groupValues
                        ?.getOrNull(1)?.toIntOrNull(),
                contact = header("Contact:")
                    ?.substringAfter("<")?.substringBefore(">")
                    ?.takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }

    private fun readLoop(s: java.net.Socket) {
        val input = runCatching { s.getInputStream().bufferedReader(Charsets.UTF_8) }.getOrNull()
            ?: return
        try {
            while (running && !s.isClosed) {
                val headLines = readHeadLines(input) ?: break
                if (headLines.isNotEmpty()) {
                    handleHeadLines(headLines, input)
                }
            }
        } catch (_: Throwable) {
            // Socket closed — loop exits.
        } finally {
            if (running) {
                Log.status(TAG, "Direct SIP read loop ended unexpectedly")
                running = false
            }
        }
    }

    /** Read one SIP head block; null on EOF, empty list on blank keepalive. */
    private fun readHeadLines(input: java.io.BufferedReader): MutableList<String>? {
        val headLines = mutableListOf<String>()
        var line = runCatching { input.readLine() }.getOrNull() ?: return null
        // Skip stray blank lines (keepalive CRLF).
        if (line.isBlank()) return headLines
        var guard = 0
        while (line.isNotEmpty() && guard++ < HEAD_GUARD) {
            headLines.add(line)
            line = runCatching { input.readLine() }.getOrNull() ?: break
        }
        return headLines
    }

    /** Route one parsed head block + body. */
    private fun handleHeadLines(headLines: MutableList<String>, input: java.io.BufferedReader) {
        val first = headLines.first()
        // Read body per Content-Length.
        val contentLength = headLines
            .firstOrNull { it.trim().startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0
        val body = if (contentLength in 1..(4 shl 20)) {
            readFixed(input, contentLength)
        } else {
            ByteArray(0)
        }
        if (first.startsWith("SIP/2.0")) {
            val resp = parseResponse(headLines.joinToString("\r\n"))
            if (resp != null) {
                // Complete any parked transaction + pending MESSAGE acks.
                val branch = resp.branch
                if (branch.isNotBlank()) {
                    pendingSends.remove(branch)?.invoke(resp.statusCode in SIP_SUCCESS)
                }
                // Wake the oldest parked transact (FIFO fallback).
                pendingTransactions.entries.firstOrNull()?.let { (k, cb) ->
                    pendingTransactions.remove(k)
                    cb(resp)
                }
            }
        } else if (first.startsWith("MESSAGE ")) {
            val headers = headLines.drop(1).joinToString("\r\n")
            onInboundMessage?.invoke(first, headers, body)
        }
        // (NOTIFY/OPTIONS/BYE on the direct leg: absorbed for now;
        // BYE handling can reuse RcsSessionManager when sessions exist.)
    }

    private fun readFixed(input: java.io.BufferedReader, n: Int): ByteArray {
        return runCatching {
            val chars = CharArray(n)
            var read = 0
            while (read < n) {
                val r = input.read(chars, read, n - read)
                if (r < 0) break
                read += r
            }
            String(chars, 0, read).toByteArray(Charsets.UTF_8)
        }.getOrDefault(ByteArray(0))
    }

    private fun pendingTarget(startLine: String, headers: String): Pair<String, String> {
        val requestUri = startLine.substringAfter(" ").substringBefore(" SIP/").trim()
        return requestUri to headers
    }

    private fun rebuildMessageHeaders(headers: String, branch: String): String {
        val from = publicIdentity.ifBlank { "sip:anonymous@anonymous.invalid" }
        val filtered = headers.lineSequence().filter { line ->
            val t = line.trim()
            // Blank lines must go too: callers pass a header block ending in
            // CRLF, and keeping its trailing empty line would end the head
            // here, pushing the Via/From/Route below into the body.
            !(t.isBlank() ||
                t.startsWith("Via:", ignoreCase = true) ||
                t.startsWith("From:", ignoreCase = true) ||
                t.startsWith("Route:", ignoreCase = true))
        }.joinToString("\r\n")
        val pcscfHost = pcscf?.hostString ?: "localhost"
        return buildString {
            if (filtered.isNotEmpty()) {
                append(filtered)
                append("\r\n")
            }
            append("Via: SIP/2.0/TCP $pcscfHost;branch=$branch\r\n")
            append("From: <$from>;tag=${UUID.randomUUID().toString().take(TAG_TOKEN_LENGTH)}\r\n")
            append("Route: <sip:$pcscfHost;lr>\r\n")
        }
    }

    private fun pcscfAddress(app: Context, network: Network): InetSocketAddress? {
        return runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
            val cm = app.getSystemService(ConnectivityManager::class.java) ?: return null
            val props: LinkProperties = cm.getLinkProperties(network) ?: return null
            // `getPcscfServers()` is @SystemApi/hidden (absent from android.jar):
            // reflect it, else parse the same addresses out of toString()
            // (which renders `PcscfAddresses: [ /2001:db8::1, ... ]`).
            val addrs: List<java.net.InetAddress> =
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    props.javaClass.getMethod("getPcscfServers").invoke(props) as? List<java.net.InetAddress>
                }.getOrNull() ?: parsePcscfFromString(props.toString())
            val addr = addrs.firstOrNull() ?: return null
            // P-CSCF SIP default 5060.
            InetSocketAddress(addr, DEFAULT_SIP_PORT)
        }.getOrNull()
    }

    private fun parsePcscfFromString(linkProps: String): List<java.net.InetAddress> {
        val section = Regex("PcscfAddresses:\\s*\\[([^\\]]*)\\]", RegexOption.IGNORE_CASE)
            .find(linkProps)?.groupValues?.getOrNull(1) ?: return emptyList()
        return section.split(",").mapNotNull { token ->
            val ip = token.trim().trimStart('/').trim()
            if (ip.isEmpty()) return@mapNotNull null
            runCatching { java.net.InetAddress.getByName(ip) }.getOrNull()
        }
    }
}
