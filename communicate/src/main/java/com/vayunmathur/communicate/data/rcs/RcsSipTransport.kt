package com.vayunmathur.communicate.data.rcs

import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.ims.DelegateRegistrationState
import android.telephony.ims.DelegateRequest
import android.telephony.ims.FeatureTagState
import android.telephony.ims.ImsException
import android.telephony.ims.SipDelegateConfiguration
import android.telephony.ims.SipDelegateConnection
import android.telephony.ims.SipDelegateManager
import android.telephony.ims.SipMessage
import android.telephony.ims.stub.DelegateConnectionMessageCallback
import android.telephony.ims.stub.DelegateConnectionStateCallback
import com.vayunmathur.library.log.Log
import com.vayunmathur.communicate.data.SimManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Per-subscription `SipDelegateManager` wrapper (single-registration transport).
 *
 * One delegate per active [SubscriptionManager] subId, tied to
 * [SimManager]'s active subscription. CPM feature tags only. Every platform
 * call catches `SecurityException` (no carrier privilege on a normal
 * dev-build install) and maps to `Unavailable(NoCarrierPrivilege)` — never
 * crashes. Until the transport is `Available`, sends fall back to SMS.
 */
object RcsSipTransport {
    private const val TAG = "RcsSipTransport"
    private val SUCCESS_CODES = 200..299
    private val PROVISIONAL_CODES = 100..199
    private const val FAILURE_CODES = 300
    private const val UNAUTHORIZED = 401
    private const val PROXY_AUTH_REQUIRED = 407
    private const val CALL_LEG_GONE = 481
    private const val REQUEST_TIMEOUT = 408
    private const val TEMP_UNAVAILABLE = 480
    private const val BUSY_HERE = 486
    private const val DECLINE = 603

    /**
     * CPM feature tags for RCS chat + file transfer (RCC.07 §2.6.1.3). Must be
     * allowed by the carrier (`KEY_RCS_FEATURE_TAG_ALLOWED_STRING_ARRAY`) or
     * the request is denied. Tag set mirrors TestRcsApp's DelegateActivity:
     * session + pager-mode variants + FT.
     */
    val CPM_FEATURE_TAGS: Set<String> = setOf(
        "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.session\"",
        "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.msg\"",
        "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.largemsg\"",
        "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.deferred\"",
        "+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gpp-application.ims.iari.rcs.fthttp\"",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val executor: Executor = Executors.newSingleThreadExecutor()

    private val _state = MutableStateFlow<RcsRegistrationState>(RcsRegistrationState.Unknown)
    val state: StateFlow<RcsRegistrationState> = _state.asStateFlow()

    @Volatile private var connection: SipDelegateConnection? = null
    @Volatile private var configVersion: Long = -1L
    @Volatile private var activeSubId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    /** Last IMS configuration (identity, server, route headers for sends). */
    @Volatile private var lastConfig: SipDelegateConfiguration? = null

    /**
     * Snapshot of the fields SIP construction needs, decoupled from the hidden
     * config class so callers never touch framework types.
     */
    data class ConfigSnapshot(
        val version: Long,
        val publicUserId: String?,
        val homeDomain: String?,
        val msrpLocalIp: String?,
        val serviceRoute: String?,
        val pani: String?,
        val userAgent: String?,
        val imei: String?,
    )

    fun lastConfigSnapshot(): ConfigSnapshot? {
        val cfg = lastConfig ?: return null
        return runCatching {
            ConfigSnapshot(
                version = cfg.getVersion(),
                publicUserId = cfg.getPublicUserIdentifier(),
                homeDomain = cfg.getHomeDomain(),
                // IMS-PDN local address when known — routable by carrier peers,
                // unlike the delegate config (which carries no local IP field).
                msrpLocalIp = RcsImsNetwork.lastLocalIp,
                serviceRoute = cfg.getSipServiceRouteHeader(),
                pani = cfg.getSipPaniHeader(),
                userAgent = cfg.getSipUserAgentHeader(),
                imei = cfg.getImei(),
            )
        }.getOrNull()
    }

    /** Inbound SIP MESSAGE listener (sync service writes to Room + notifies). */
    @Volatile
    var onInboundMessage: ((SipMessage) -> Unit)? = null

    /** Pending outbound sends keyed by Via branch transaction id. */
    private data class PendingSend(
        val callback: (Boolean) -> Unit,
        val startLine: String,
        val headers: String,
        val body: ByteArray,
        val authAttempts: Int = 0,
    )

    private val pendingSends = ConcurrentHashMap<String, PendingSend>()

    /**
     * Hidden-API callbacks, loaded on first use only. They are created by
     * [transportCallbacks], whose signature is hidden-API-free: the callback
     * types appear only as `Any` here, so neither the property types nor the
     * `<clinit>` reference the missing classes. Call sites cast back to the
     * framework types (safe on-device, where the framework provides them).
     */
    private val stateCallback: Any by lazy { transportCallbacks().first }
    private val messageCallback: Any by lazy { transportCallbacks().second }

    /** Test seam: the lazily-built delegate callbacks, for JVM tests. */
    internal fun callbacksForTest(): Pair<Any, Any> = stateCallback to messageCallback

    /**
     * Test seam: install (or clear, with null) a delegate connection plus
     * its config version/snapshot, mirroring what `onCreated` +
     * `onConfigurationChanged` set on device. Production code never calls
     * this; the framework callbacks remain the only real installers.
     */
    internal fun installConnectionForTest(
        connection: SipDelegateConnection?,
        version: Long,
        config: SipDelegateConfiguration?,
    ) {
        this.connection = connection
        configVersion = version
        lastConfig = config
        _state.value = if (connection != null) {
            RcsRegistrationState.Available
        } else {
            RcsRegistrationState.Unknown
        }
    }

    /**
     * Build the two delegate callbacks. The anonymous classes — and hence the
     * hidden-API callback types — load only when this function runs
     * on-device, never during the outer object's `<clinit>`.
     */
    private fun transportCallbacks(): Pair<Any, Any> {
        val state = object : DelegateConnectionStateCallback {
            override fun onCreated(c: SipDelegateConnection) {
                connection = c
                _state.value = RcsRegistrationState.Available
                Log.status(TAG, "SipDelegate created")
            }

            override fun onFeatureTagStatusChanged(
                registrationState: DelegateRegistrationState,
                deniedFeatureTags: Set<FeatureTagState>,
            ) {
                if (deniedFeatureTags.isNotEmpty()) {
                    Log.status(TAG, "Feature tags denied: ${deniedFeatureTags.map { it.getFeatureTag() }}")
                    _state.value = RcsRegistrationState.Unavailable(RcsUnavailableReason.TransportDenied)
                }
            }

            override fun onConfigurationChanged(registeredSipConfig: SipDelegateConfiguration) {
                configVersion = registeredSipConfig.getVersion()
                lastConfig = registeredSipConfig
            }

            override fun onDestroyed(reason: Int) {
                Log.status(TAG, "SipDelegate destroyed reason=$reason")
                connection = null
                configVersion = -1L
                lastConfig = null
                if (_state.value is RcsRegistrationState.Available) {
                    _state.value = RcsRegistrationState.Unavailable(RcsUnavailableReason.ServiceUnavailable)
                }
            }
        }
        val message = object : DelegateConnectionMessageCallback {
            override fun onMessageReceived(message: SipMessage) {
                runCatching { connection?.notifyMessageReceived(message.getViaBranchParameter()) }
                onInboundMessage?.invoke(message)
            }

            override fun onMessageSent(viaTransactionId: String) {
                pendingSends.remove(viaTransactionId)?.callback?.invoke(true)
            }

            override fun onMessageSendFailure(viaTransactionId: String, reason: Int) {
                Log.status(TAG, "Message send failed tx=$viaTransactionId reason=$reason")
                pendingSends.remove(viaTransactionId)?.callback?.invoke(false)
            }
        }
        return state to message
    }

    /**
     * Bring up the transport for the active subscription. Runs provisioning
     * first; only creates the delegate when provisioned. Never throws.
     */
    fun ensureRegistered(context: Context) {
        if (!RcsFeature.enabled) {
            _state.value = RcsRegistrationState.Disabled
            return
        }
        scope.launch {
            val app = context.applicationContext
            val subId = activeSubId(app)
            if (!SubscriptionManager.isValidSubscriptionId(subId)) {
                _state.value = RcsRegistrationState.Unavailable(RcsUnavailableReason.NoSubscription)
                return@launch
            }
            if (subId == activeSubId && connection != null) return@launch
            if (subId != activeSubId) tearDown(app)
            activeSubId = subId
            // Start the live provisioning watch before creating the delegate:
            // config changes (FT server URI, re-provisioning) arrive via
            // callback instead of waiting for the next probe.
            RcsProvisioningWatcher.watch(app, subId)
            when (val probe = RcsProvisioning.probe(app, subId)) {
                is RcsRegistrationState.Available -> createDelegate(app, subId)
                is RcsRegistrationState.Unavailable -> {
                    // Carrier says no to single-reg but the device supports
                    // it: fall back to the direct SIP stack over the IMS PDN
                    // (P-CSCF + ISIM AKA, no delegate). Any other Unavailable
                    // reason stands.
                    if (probe.reason == RcsUnavailableReason.NotSupported &&
                        RcsProvisioning.deviceSupportsSingleReg(app)
                    ) {
                        startDirectSip(app, subId)
                    } else {
                        _state.value = probe
                    }
                }
                else -> Unit
            }
        }
    }

    fun tearDown(context: Context? = null) {
        directSipUp = false
        RcsDirectSip.onInboundMessage = null
        RcsDirectSip.stop()
        val c = connection
        if (c != null && context != null && RcsFeature.enabled) {
            runCatching {
                // Requires the same privileged permission; failure just orphans the
                // delegate, which the framework reaps.
                val manager = RcsHiddenApi.sipDelegateManager(context, activeSubId)
                manager?.destroySipDelegate(c, SipDelegateManager.SIP_DELEGATE_DESTROY_REASON_REQUESTED_BY_APP)
            }
        }
        RcsProvisioningWatcher.unwatch()
        RcsMsrpListen.stop()
        RcsImsNetwork.reset()
        connection = null
        configVersion = -1L
        activeSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }

    /**
     * Test seam: when set, [canSend] is true and [sendSipMessage] routes to
     * this sender instead of a live delegate/direct-SIP leg. Lets JVM tests
     * drive the session/focus/typing layers that sit above the transport
     * without an IMS PDN. Production code never sets it.
     */
    @Volatile
    internal var fakeSenderForTest: ((startLine: String, headers: String, body: ByteArray) -> Boolean)? = null

    /** True when a SIP MESSAGE can be sent right now (either leg). */
    fun canSend(): Boolean =
        RcsFeature.enabled && (
            fakeSenderForTest != null ||
                (_state.value is RcsRegistrationState.Available && connection != null) ||
                directSipUp
            )

    /** True when the direct-SIP fallback leg is REGISTERed. */
    @Volatile
    private var directSipUp: Boolean = false

    /** Which leg carries traffic: delegate (carrier single-reg) or direct SIP. */
    val activeLeg: String
        get() = when {
            !RcsFeature.enabled -> "none"
            connection != null -> "single-reg delegate"
            directSipUp -> "direct SIP (IMS PDN)"
            else -> "none"
        }

    /**
     * Start the direct-SIP fallback leg (IMS PDN → P-CSCF → AKA REGISTER).
     * On success the transport reports `Available` with no delegate
     * connection; sends route to [RcsDirectSip]. On failure the probe's
     * original `Unavailable` stands.
     */
    private suspend fun startDirectSip(app: Context, subId: Int) {
        _state.value = RcsRegistrationState.Provisioning
        val ok = RcsDirectSip.start(app, subId)
        if (ok) {
            directSipUp = true
            RcsDirectSip.onInboundMessage = { startLine, headers, body ->
                // Reuse the delegate-path inbound pipeline: synthesize the
                // SipMessage shape the sync service parses.
                runCatching {
                    val msg = SipMessage(startLine, headers, body)
                    onInboundMessage?.invoke(msg)
                }
            }
            _state.value = RcsRegistrationState.Available
            Log.status(TAG, "Direct SIP leg up (fallback)")
        } else {
            directSipUp = false
            Log.status(TAG, "Direct SIP leg failed; transport unavailable")
            _state.value = RcsRegistrationState.Unavailable(RcsUnavailableReason.NotSupported)
        }
    }

    /**
     * Tell the framework to release delegate state for [callId]
     * (`SipDelegateConnection#cleanupSession`). Best-effort: no connection
     * or any failure is a silent no-op.
     */
    fun cleanupSession(callId: String) {
        val c = connection ?: return
        if (!RcsFeature.enabled || callId.isBlank()) return
        runCatching { c.cleanupSession(callId) }
    }

    /**
     * Send a SIP MESSAGE. Returns true on delegate ack, false otherwise
     * (caller falls back to SMS). Never throws.
     *
     * The full request is stashed per branch so 401/407 challenges can be
     * retried with digest auth ([onSipResponseMessage]).
     */
    suspend fun sendSipMessage(startLine: String, headerSection: String, body: ByteArray): Boolean {
        // Test seam (never set in production): record/accept the send.
        fakeSenderForTest?.let { return it(startLine, headerSection, body) }
        // Direct-SIP leg: route to the P-CSCF socket (no delegate).
        if (directSipUp && RcsFeature.enabled) {
            return RcsDirectSip.sendMessage(startLine, headerSection, body)
        }
        val c = connection
        if (!canSend() || c == null) return false
        val version = configVersion
        if (version < 0) return false
        return runCatching {
            val message = SipMessage(startLine, headerSection, body)
            suspendCancellableCoroutine { cont ->
                val branch = message.getViaBranchParameter()
                pendingSends[branch] = PendingSend(
                    callback = { ok -> if (cont.isActive) cont.resume(ok) },
                    startLine = startLine,
                    headers = headerSection,
                    body = body,
                )
                runCatching { c.sendMessage(message, version) }.onFailure {
                    pendingSends.remove(branch)
                    if (cont.isActive) cont.resume(false)
                }
            }
        }.getOrDefault(false)
    }

    /**
     * Route an inbound SIP *response* (`SIP/2.0 xxx` start line). Returns
     * true when the response was consumed (matched a pending transaction or
     * a session dialog); false when the caller should treat it as an
     * inbound MESSAGE.
     *
     * - 1xx → absorbed (retransmissions/100 Trying need no action).
     * - 2xx to INVITE → session establishment + ACK (§1.1).
     * - 401/407 → one digest-auth retry, then fail (§1.3).
     * - 481/408/480/486/603 → dialog-mismatch recovery (§1.6).
     * - 2xx to anything else → pending send succeeds.
     * - anything else → pending send fails.
     */
    suspend fun onSipResponseMessage(message: SipMessage): Boolean {
        if (!RcsFeature.enabled) return false
        val parsed = RcsSipResponse.parse(message) ?: return false
        // 2xx to our INVITE: complete the dialog + ACK, even if the original
        // branch aged out of pendingSends (responses can arrive late).
        if (parsed.statusCode in SUCCESS_CODES && parsed.cseqMethod.equals("INVITE", ignoreCase = true)) {
            pendingSends.remove(parsed.branch)?.callback?.invoke(true)
            forwardToSessionManager(parsed)
            return true
        }
        val pending = pendingSends[parsed.branch]
        if (pending == null) {
            return handleStrayResponse(parsed)
        }
        handlePendingResponse(parsed, pending)
        return true
    }

    /** Route a parsed response to the session manager. */
    private suspend fun forwardToSessionManager(parsed: RcsSipResponse.Parsed) {
        RcsSessionManager.onSipResponse(
            statusCode = parsed.statusCode,
            callId = parsed.callId,
            remoteTag = parsed.toTag,
            sdpAnswer = parsed.body,
            responseHeaders = parsed.headers,
        )
    }

    /** Stray response (late duplicate, CANCEL race): route dialog finals by Call-ID. */
    private suspend fun handleStrayResponse(parsed: RcsSipResponse.Parsed): Boolean {
        if (parsed.statusCode >= FAILURE_CODES) {
            forwardToSessionManager(parsed)
            return true
        }
        return false
    }

    /** Route a response with a pending transaction. */
    private suspend fun handlePendingResponse(parsed: RcsSipResponse.Parsed, pending: PendingSend) {
        when {
            parsed.statusCode in PROVISIONAL_CODES -> Unit // absorbed; ack comes later
            parsed.statusCode in SUCCESS_CODES -> {
                pendingSends.remove(parsed.branch)?.callback?.invoke(true)
            }
            parsed.statusCode == UNAUTHORIZED || parsed.statusCode == PROXY_AUTH_REQUIRED -> {
                if (!retryWithDigestAuth(parsed, pending)) {
                    pendingSends.remove(parsed.branch)?.callback?.invoke(false)
                }
                // On retry the callback stays parked under the NEW branch.
            }
            parsed.statusCode == CALL_LEG_GONE || parsed.statusCode == REQUEST_TIMEOUT ||
                parsed.statusCode == TEMP_UNAVAILABLE || parsed.statusCode == BUSY_HERE ||
                parsed.statusCode == DECLINE -> {
                pendingSends.remove(parsed.branch)?.callback?.invoke(false)
                RcsSessionManager.onDialogError(parsed.callId, parsed.statusCode)
            }
            else -> {
                pendingSends.remove(parsed.branch)?.callback?.invoke(false)
                if (parsed.statusCode >= FAILURE_CODES) {
                    forwardToSessionManager(parsed)
                }
            }
        }
    }

    /**
     * Rebuild a challenged request with digest `Authorization` /
     * `Proxy-Authorization` and resend under a fresh branch (RFC 3261
     * §22.2: same Call-ID/CSeq/From-tag, new Via branch). One retry per
     * transaction max. Returns true when a retry was issued.
     */
    private suspend fun retryWithDigestAuth(
        parsed: RcsSipResponse.Parsed,
        pending: PendingSend,
    ): Boolean {
        if (pending.authAttempts >= 1) return false
        val request = digestAuthRequest(parsed, pending) ?: return false
        // Re-park the callback BEFORE sending (the ack may arrive inline).
        val oldBranch = parsed.branch
        pendingSends.remove(oldBranch)
        val c = connection ?: run {
            pending.callback(false)
            return false
        }
        val version = configVersion
        if (version < 0) {
            pending.callback(false)
            return false
        }
        var issued = false
        runCatching {
            val message = SipMessage(pending.startLine, request.rebuilt, pending.body)
            // SipMessage computes its own branch from the header section;
            // our rebuilt headers carry newBranch, so key on it.
            pendingSends[request.newBranch] = pending.copy(
                headers = request.rebuilt,
                authAttempts = pending.authAttempts + 1,
            )
            runCatching { c.sendMessage(message, version) }.onFailure {
                pendingSends.remove(request.newBranch)
                pending.callback(false)
            }
            issued = true
        }.onFailure {
            pendingSends.remove(request.newBranch)
            pending.callback(false)
        }
        Log.status(TAG, "Digest auth retry issued for ${parsed.statusCode} (proxy=${request.proxy})")
        return issued
    }

    private data class DigestAuthRequest(
        val rebuilt: String,
        val newBranch: String,
        val proxy: Boolean,
    )

    /** Build the authed re-send headers for a 401/407 challenge, or null when unbuildable. */
    private fun digestAuthRequest(parsed: RcsSipResponse.Parsed, pending: PendingSend): DigestAuthRequest? {
        val proxy = parsed.statusCode == 407
        val challengeHeader = if (proxy) "Proxy-Authenticate:" else "WWW-Authenticate:"
        val challenge = RcsSipDialog.headerValue(parsed.headers, challengeHeader) ?: return null
        val gba = RcsGbaAuth.injected
        val username = gba?.btId
            ?: lastConfigSnapshot()?.publicUserId?.substringAfter("sip:")?.substringBefore("@")
                ?.takeIf { it.isNotBlank() } ?: return null
        val password = gba?.let {
            android.util.Base64.encodeToString(it.key, android.util.Base64.NO_WRAP)
        }.orEmpty()
        val method = pending.startLine.substringBefore(" ").trim().ifBlank { return null }
        val uri = pending.startLine.substringAfter(" ").substringBefore(" SIP/").trim()
            .ifBlank { return null }
        val cnonce = UUID.randomUUID().toString().replace("-", "").take(16)
        val authValue = RcsGbaAuth.digestAuthorizationHeader(
            challenge = challenge,
            method = method,
            uri = uri,
            username = username,
            password = password,
            cnonce = cnonce,
        ).ifBlank { return null }
        // Same request, fresh branch, auth header appended (replaces any
        // stale Authorization/Proxy-Authorization from a previous attempt).
        val newBranch = RcsSipDialog.newBranch()
        val baseHeaders = pending.headers.lineSequence()
            .filter { line ->
                val t = line.trim()
                !(t.startsWith("Via:", ignoreCase = true) ||
                    t.startsWith("Authorization:", ignoreCase = true) ||
                    t.startsWith("Proxy-Authorization:", ignoreCase = true))
            }.joinToString("\r\n")
        val viaHost = pending.headers.lineSequence()
            .firstOrNull { it.trim().startsWith("Via:", ignoreCase = true) }
            ?.substringAfter("SIP/2.0/TCP")?.substringBefore(";")?.trim()
            ?.takeIf { it.isNotEmpty() } ?: "local"
        val rebuilt = buildString {
            append(baseHeaders)
            if (baseHeaders.isNotEmpty()) append("\r\n")
            append("Via: SIP/2.0/TCP $viaHost;branch=$newBranch\r\n")
            append(if (proxy) "Proxy-Authorization: $authValue\r\n" else "Authorization: $authValue\r\n")
        }
        return DigestAuthRequest(rebuilt, newBranch, proxy)
    }

    /**
     * Build a pager-mode SIP MESSAGE start line + headers for a 1:1 CPM chat
     * message. The branch parameter doubles as the send ack key.
     *
     * The body is a `message/cpim` envelope (TestRcsApp CpimUtils) carrying
     * IMDN metadata; the From identity comes from the delegate configuration
     * (public user identifier) when known, else the [fromUri] fallback.
     */
    fun buildChatMessage(
        fromUri: String,
        toUri: String,
        callId: String = "${UUID.randomUUID()}@rcs",
        body: String,
    ): Triple<String, String, ByteArray> {
        val branch = "z9hG4bK${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val from = lastConfig?.getPublicUserIdentifier()?.takeIf { it.isNotBlank() } ?: fromUri
        val cpim = buildCpimBody(body)
        val bytes = cpim.toByteArray(Charsets.UTF_8)
        val startLine = "MESSAGE $toUri SIP/2.0"
        val headers = buildString {
            append("Via: SIP/2.0/TCP $from;branch=$branch\r\n")
            append("From: <$from>;tag=${UUID.randomUUID().toString().take(8)}\r\n")
            append("To: <$toUri>\r\n")
            append("Call-ID: $callId\r\n")
            append("CSeq: 1 MESSAGE\r\n")
            append("Content-Type: message/cpim\r\n")
            append("Content-Length: ${bytes.size}\r\n")
        }
        return Triple(startLine, headers, bytes)
    }

    /**
     * Minimal `message/cpim` envelope for a text message (TestRcsApp
     * CpimUtils.createForText): IMDN namespace + delivery/display disposition,
     * anonymous From/To (routing uses the SIP headers), ISO-instant DateTime.
     */
    fun buildCpimBody(text: String): String = buildString {
        append("NS: imdn <urn:ietf:params:imdn>\r\n")
        append("imdn.Message-ID: rcs-${UUID.randomUUID()}\r\n")
        append("imdn.Disposition-Notification: positive-delivery, display\r\n")
        append("To: <sip:anonymous@anonymous.invalid>\r\n")
        append("From: <sip:anonymous@anonymous.invalid>\r\n")
        append("DateTime: ${java.time.Instant.now()}\r\n")
        append("Content-Type: text/plain;charset=UTF-8\r\n")
        append("Content-Length: ${text.toByteArray(Charsets.UTF_8).size}\r\n")
        append("\r\n")
        append(text)
    }

    private fun createDelegate(context: Context, subId: Int) {
        try {
            val manager = RcsHiddenApi.sipDelegateManager(context, subId)
                ?: run {
                    _state.value = RcsRegistrationState.Unavailable(RcsUnavailableReason.ServiceUnavailable)
                    return
                }
            val supported: Boolean = runCatching { manager.isSupported() }.getOrElse {
                _state.value = mapSetupFailure(it)
                return
            }
            if (!supported) {
                _state.value = RcsRegistrationState.Unavailable(RcsUnavailableReason.NotSupported)
                return
            }
            manager.createSipDelegate(
                DelegateRequest(CPM_FEATURE_TAGS),
                executor,
                stateCallback as DelegateConnectionStateCallback,
                messageCallback as DelegateConnectionMessageCallback,
            )
        } catch (e: SecurityException) {
            Log.status(TAG, "No carrier privilege for single registration", e)
            _state.value = RcsRegistrationState.Unavailable(RcsUnavailableReason.NoCarrierPrivilege)
        } catch (e: ImsException) {
            Log.status(TAG, "IMS service unavailable", e)
            _state.value = RcsRegistrationState.Unavailable(RcsUnavailableReason.ServiceUnavailable)
        } catch (e: IllegalArgumentException) {
            Log.status(TAG, "Invalid subscription", e)
            _state.value = RcsRegistrationState.Unavailable(RcsUnavailableReason.NoSubscription)
        }
    }

    private fun mapSetupFailure(t: Throwable): RcsRegistrationState.Unavailable =
        when (t) {
            is SecurityException -> RcsRegistrationState.Unavailable(RcsUnavailableReason.NoCarrierPrivilege)
            is ImsException -> RcsRegistrationState.Unavailable(RcsUnavailableReason.ServiceUnavailable)
            else -> RcsRegistrationState.Unavailable(RcsUnavailableReason.Unknown)
        }

    private fun activeSubId(context: Context): Int {
        if (!RcsProvisioning.hasPhoneStatePermission(context)) {
            // TestRcsApp keys everything off the default SMS subscription.
            return SubscriptionManager.getDefaultSmsSubscriptionId()
        }
        val sims = SimManager.activeSims(context)
        if (sims.isEmpty()) return SubscriptionManager.INVALID_SUBSCRIPTION_ID
        val current = activeSubId
        return sims.firstOrNull { it.subscriptionId == current }?.subscriptionId
            ?: sims.firstOrNull()?.subscriptionId
            ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }
}
