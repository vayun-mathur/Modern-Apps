package com.vayunmathur.messages.gvoice.sip

import android.content.Context
import android.util.Log
import org.pjsip.pjsua2.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SIP stack manager wrapping PJSUA2 (PJSIP Java bindings).
 *
 * Handles:
 * - PJSIP endpoint initialization
 * - SIP account registration with voice.sip.google.com
 * - Outgoing call placement
 * - Incoming call handling
 * - Audio device management
 *
 * Based on PJSUA2 API: https://docs.pjsip.org/en/latest/pjsua2/intro.html
 */
object SipManager {

    private const val TAG = "SipManager"

    private val initialized = AtomicBoolean(false)
    private var endpoint: Endpoint? = null
    private var account: SipAccount? = null
    private var currentCall: SipCall? = null

    /**
     * Initialize PJSIP endpoint. Must be called once on app startup
     * before any SIP operations.
     */
    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return

        try {
            // Load native library
            System.loadLibrary("c++_shared")
            System.loadLibrary("pjsua2")
            Log.i(TAG, "PJSIP native libraries loaded")

            endpoint = Endpoint()
            endpoint!!.libCreate()

            // Configure endpoint
            val epConfig = EpConfig()
            epConfig.uaConfig.threadCnt = 1
            epConfig.uaConfig.mainThreadOnly = false

            // Configure DNS resolver with public DNS servers to avoid resolution failures
            // PJSIP's UaConfig.nameserver allows setting custom DNS servers
            val nameservers = StringVector()
            nameservers.add("8.8.8.8")      // Google DNS primary
            nameservers.add("8.8.4.4")      // Google DNS secondary
            nameservers.add("1.1.1.1")      // Cloudflare DNS
            nameservers.add("1.0.0.1")      // Cloudflare DNS secondary
            epConfig.uaConfig.nameserver = nameservers
            Log.i(TAG, "Configured PJSIP DNS resolver with public nameservers (8.8.8.8, 8.8.4.4, 1.1.1.1, 1.0.0.1)")

            // Media config - use WebRTC AEC like GV app does
            epConfig.medConfig.ecOptions = pjmedia_echo_flag.PJMEDIA_ECHO_WEBRTC.toLong()
            epConfig.medConfig.ecTailLen = 200
            epConfig.medConfig.noVad = false
            epConfig.medConfig.quality = 4
            epConfig.medConfig.ptime = 20
            epConfig.medConfig.sndClockRate = 16000

            // Logging
            epConfig.logConfig.level = 4
            epConfig.logConfig.consoleLevel = 4
            val logWriter = object : LogWriter() {
                override fun write(entry: LogEntry) {
                    Log.d("PJSIP", entry.msg.trim())
                }
            }
            epConfig.logConfig.writer = logWriter

            endpoint!!.libInit(epConfig)
            Log.i(TAG, "PJSIP endpoint initialized")

            // Create UDP transport for SIP signaling
            val transportConfig = TransportConfig()
            transportConfig.port = 5060
            endpoint!!.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_UDP, transportConfig)
            Log.i(TAG, "SIP UDP transport created on port 5060")

            // Create TLS transport for secure SIP (port 5061)
            try {
                val tlsConfig = TransportConfig()
                tlsConfig.port = 5061
                endpoint!!.transportCreate(pjsip_transport_type_e.PJSIP_TRANSPORT_TLS, tlsConfig)
                Log.i(TAG, "SIP TLS transport created on port 5061")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create TLS transport (may need certificates): ${e.message}")
            }

            endpoint!!.libStart()
            Log.i(TAG, "PJSIP endpoint started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PJSIP: ${e.message}", e)
            initialized.set(false)
        }
    }

    /**
     * Register SIP account with Google Voice SIP server using Bearer OAuth
     * authentication (RFC 8760).
     *
     * Based on deobfuscation findings, the official GV app uses Bearer OAuth
     * tokens, not digest authentication. BirdsongConfig has auth_user and
     * auth_realm fields but no password field. Native code receives OAuth
     * token via GaiaOauthTokenGetterAsync.
     *
     * PJSIP supports custom headers via AccountConfig.regConfig.headers.
     * We inject Authorization: Bearer <oauth_token> header for SIP REGISTER.
     *
     * @return true if registration initiated successfully
     */
    fun register(credentials: SipCredentials): Boolean {
        val ep = endpoint ?: run {
            Log.e(TAG, "Cannot register: endpoint not initialized")
            return false
        }

        try {
            Log.i(TAG, "Registering SIP account with ${credentials.sipServer} using Bearer OAuth (RFC 8760)")
            Log.i(TAG, "Device ID: ${credentials.sipDeviceId}")
            Log.i(TAG, "Auth user: ${credentials.authUser}")
            Log.i(TAG, "Auth realm: ${credentials.authRealm}")
            Log.i(TAG, "OAuth token: ${credentials.oauthToken.take(20)}... (${credentials.oauthToken.length} chars)")

            // Use server URI from BirdsongConfig or default
            // Try both with and without explicit port for robustness
            val serverUris = listOf(
                "sip:${credentials.sipServer}",           // Without port (let DNS SRV handle)
                "sip:${credentials.sipServer}:${credentials.sipPort}"  // With explicit port 5061
            )

            var successCount = 0
            for (serverUri in serverUris) {
                try {
                    Log.i(TAG, "=== Registering with server URI: $serverUri ===")

                    val accCfg = AccountConfig()
                    accCfg.idUri = credentials.idUri
                    accCfg.regConfig.registrarUri = serverUri

                    // Configure Bearer OAuth authentication (RFC 8760)
                    // PJSIP AccountRegConfig supports custom headers via setHeaders()
                    // We inject Authorization: Bearer <token> for SIP REGISTER requests
                    val authHeader = SipHeader()
                    authHeader.hName = "Authorization"
                    authHeader.hValue = credentials.getAuthorizationHeader()
                    val headers = SipHeaderVector()
                    headers.add(authHeader)
                    accCfg.regConfig.headers = headers
                    Log.i(TAG, "Configured Bearer OAuth Authorization header for SIP REGISTER")

                    // Set auth creds with Bearer scheme for completeness
                    // PJSIP may use this for 401 challenges, though with Bearer we send preemptively
                    val bearerCred = AuthCredInfo(
                        "Bearer",  // RFC 8760 scheme
                        credentials.authRealm,
                        credentials.authUser,
                        0,  // data type (0 = plain text)
                        credentials.oauthToken
                    )
                    accCfg.sipConfig.authCreds.add(bearerCred)

                    // Enable preemptive auth to send Authorization header on initial REGISTER
                    // (not just after 401 challenge)
                    accCfg.sipConfig.authInitialEmpty = false
                    accCfg.sipConfig.authInitialAlgorithm = "Bearer"

                    // Enable ICE for NAT traversal
                    accCfg.natConfig.iceEnabled = true
                    accCfg.natConfig.turnEnabled = false

                    // Create account
                    val sipAccount = SipAccount("Bearer")
                    sipAccount.create(accCfg)
                    Log.i(TAG, "[Bearer] SIP account created for $serverUri with OAuth Bearer auth, registration in progress...")
                    successCount++

                    // Store first account as primary (for makeCall/endCall)
                    if (account == null) {
                        account = sipAccount
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create account for $serverUri: ${e.message}", e)
                }
            }

            Log.i(TAG, "Initiated $successCount registration attempts with Bearer OAuth across ${serverUris.size} server URIs")
            return successCount > 0

        } catch (e: Exception) {
            Log.e(TAG, "SIP registration failed: ${e.message}", e)
            return false
        }
    }

    /**
     * Place an outgoing voice call to the given phone number via SIP.
     */
    fun makeCall(phoneNumber: String): Boolean {
        val acc = account ?: run {
            Log.e(TAG, "Cannot make call: not registered")
            return false
        }

        try {
            // Format as SIP URI - GV likely expects E.164 format
            val sipUri = if (phoneNumber.startsWith("+")) {
                "sip:$phoneNumber@voice.sip.google.com"
            } else {
                "sip:+$phoneNumber@voice.sip.google.com"
            }

            Log.i(TAG, "Placing call to $sipUri")

            val call = SipCall(acc, -1)
            val prm = CallOpParam(true)
            call.makeCall(sipUri, prm)
            currentCall = call

            Log.i(TAG, "Call placed successfully")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to place call: ${e.message}", e)
            return false
        }
    }

    /**
     * End the current active call.
     */
    fun endCall(): Boolean {
        val call = currentCall ?: return false
        return try {
            val prm = CallOpParam()
            prm.statusCode = pjsip_status_code.PJSIP_SC_DECLINE
            call.hangup(prm)
            currentCall = null
            Log.i(TAG, "Call ended")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to end call: ${e.message}", e)
            false
        }
    }

    /**
     * Answer the current incoming call.
     */
    fun answerCall(): Boolean {
        val call = currentCall ?: return false
        return try {
            val prm = CallOpParam()
            prm.statusCode = pjsip_status_code.PJSIP_SC_OK
            call.answer(prm)
            Log.i(TAG, "Call answered")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to answer call: ${e.message}", e)
            false
        }
    }

    /**
     * Place the current call on hold.
     */
    fun holdCall(): Boolean {
        val call = currentCall ?: return false
        return try {
            val prm = CallOpParam()
            call.setHold(prm)
            Log.i(TAG, "Call held")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hold call: ${e.message}", e)
            false
        }
    }

    /**
     * Unhold the current call.
     */
    fun unholdCall(): Boolean {
        val call = currentCall ?: return false
        return try {
            val prm = CallOpParam()
            prm.options = pjsua_call_flag.PJSUA_CALL_UNHOLD.toLong()
            call.reinvite(prm)
            Log.i(TAG, "Call unheld")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unhold call: ${e.message}", e)
            false
        }
    }

    /**
     * Set audio route for the call (speaker, earpiece, bluetooth, etc.)
     */
    fun setAudioRoute(route: Int) {
        // TODO: Implement audio routing via AudioManager
        // route values from AudioState: ROUTE_EARPIECE, ROUTE_SPEAKER, ROUTE_BLUETOOTH, etc.
        Log.i(TAG, "setAudioRoute called with route=$route")
    }

    /**
     * Clean up PJSIP resources. Call on app shutdown.
     */
    fun destroy() {
        try {
            currentCall?.let {
                try { it.hangup(CallOpParam()) } catch (_: Exception) {}
            }
            currentCall = null

            account?.let {
                try { it.shutdown() } catch (_: Exception) {}
                try { it.delete() } catch (_: Exception) {}
            }
            account = null

            endpoint?.let {
                try { it.libDestroy() } catch (_: Exception) {}
            }
            endpoint = null
            initialized.set(false)
            Log.i(TAG, "PJSIP destroyed")
        } catch (e: Exception) {
            Log.e(TAG, "Error during PJSIP destroy: ${e.message}")
        }
    }
}

/**
 * SIP Account implementation handling registration state changes.
 */
private class SipAccount(private val variantName: String = "?") : Account() {
    override fun onRegState(prm: OnRegStateParam?) {
        Log.i("SipAccount", "[$variantName] Registration state: ${prm?.code} ${prm?.reason}, expiration=${prm?.expiration}")
        if (prm?.code == pjsip_status_code.PJSIP_SC_OK) {
            Log.i("SipAccount", "[$variantName] SIP REGISTER successful!")
        } else if (prm?.code == pjsip_status_code.PJSIP_SC_UNAUTHORIZED) {
            Log.i("SipAccount", "[$variantName] SIP REGISTER got 401 Unauthorized - credentials mapping may be close! Server is reachable.")
        } else if (prm?.code ?: 0 >= 400) {
            Log.e("SipAccount", "[$variantName] SIP REGISTER failed: ${prm?.code} ${prm?.reason}")
        }
    }

    override fun onIncomingCall(prm: OnIncomingCallParam?) {
        Log.i("SipAccount", "[$variantName] Incoming call from ${prm?.callId}")
        // TODO: Handle incoming call - create SipCall and notify UI via Telecom ConnectionService
    }
}

/**
 * SIP Call implementation handling call state changes.
 */
private class SipCall(acc: Account, callId: Int = -1) : Call(acc, callId) {
    override fun onCallState(prm: OnCallStateParam?) {
        val ci = try { getInfo() } catch (_: Exception) { null }
        Log.i("SipCall", "Call state: ${ci?.stateText} (${ci?.state}), last status: ${ci?.lastStatusCode} ${ci?.lastReason}")
    }

    override fun onCallMediaState(prm: OnCallMediaStateParam?) {
        val ci = try { getInfo() } catch (_: Exception) { null }
        Log.i("SipCall", "Call media state changed")
        // TODO: Handle audio media activation
        // Note: media.size() causes Kotlin compiler confusion with SWIG bindings,
        // using try-catch with hardcoded loop limit as workaround for now
        ci?.media?.let {
            for (i in 0 until 10) {  // Max 10 media streams is safe upper bound
                val media = try { getMedia(i.toLong()) } catch (_: Exception) { break }
                if (media == null) break
                if (media.type == pjmedia_type.PJMEDIA_TYPE_AUDIO) {
                    val audioMedia = AudioMedia.typecastFromMedia(media)
                    // Connect to sound device
                    try {
                        val audDevManager = Endpoint.instance().audDevManager()
                        audioMedia.startTransmit(audDevManager.playbackDevMedia)
                        audDevManager.captureDevMedia.startTransmit(audioMedia)
                        Log.i("SipCall", "Audio media connected to sound device")
                    } catch (e: Exception) {
                        Log.e("SipCall", "Failed to connect audio: ${e.message}")
                    }
                    break
                }
            }
        }
    }
}
