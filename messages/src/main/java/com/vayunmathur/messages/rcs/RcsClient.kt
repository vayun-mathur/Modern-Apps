package com.vayunmathur.messages.rcs

import android.content.Context
import android.util.Log
import com.vayunmathur.messages.data.MessageSource
import com.vayunmathur.messages.gmessages.GMEvent
import com.vayunmathur.messages.util.ContactSuggestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RCS client for Google Jibe backend via Tachyon gRPC protocol.
 *
 * Architecture based on reverse engineering:
 * - Google Messages APK (com.google.android.apps.messaging)
 * - Google Play Services GMS (com.google.android.gms) - Tachyon endpoints
 * - Shannon RCS service (com.shannon.rcsservice) - GSMA RCC.14 reference
 *
 * Protocol flow:
 * 1. ACS Provisioning: HTTP GET to config.rcs.mnc<MNC>.mcc<MCC>.pub.3gppnetwork.org
 *    or Google Jibe ACS at *.telephony.goog
 * 2. Handle 200 OK (XML config) or 511 (SMS OTP required)
 * 3. Parse XML for SIP credentials (P-CSCF, username, password, auth type)
 * 4. SIP REGISTER to Jibe IMS core
 * 5. Tachyon gRPC for messaging (Google's proprietary layer over SIP)
 *    Endpoints: tachyon-*-rcs-*-grpc.*.googleapis.com
 *    OAuth scopes: https://www.googleapis.com/auth/tachyon,
 *                  https://www.googleapis.com/auth/android-messages
 *
 * Current status: SKELETON - protocol implementation is TODO.
 * See reverse-engineering findings in:
 * - reverse-engineering/messages-re/FINDINGS.md
 * - reverse-engineering/shannon-re/FINDINGS.md
 */
object RcsClient {

    private const val TAG = "RcsClient"

    sealed interface State {
        data object Idle : State
        data object NeedsSetup : State
        data object Connecting : State
        data object Connected : State
        data class Disconnected(val reason: String) : State
        /** Waiting for SMS OTP during ACS provisioning (511 response) */
        data object AwaitingOtp : State
        /** ACS provisioning in progress */
        data object Provisioning : State
    }

    val source: MessageSource = MessageSource.RCS

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GMEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<GMEvent> = _events.asSharedFlow()

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var appContext: Context
    private var acsJob: Job? = null
    private var sipJob: Job? = null
    private var tachyonJob: Job? = null

    // RCS configuration from ACS
    private var rcsConfig: RcsConfiguration? = null
    private var phoneNumber: String? = null

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        Log.i(TAG, "init")
        // TODO: Load persisted RCS config and auth state from SharedPreferences / Room
        _state.value = State.NeedsSetup
    }

    fun start() {
        if (!initialized.get()) return
        Log.i(TAG, "start, current state: ${_state.value}")
        when (_state.value) {
            is State.NeedsSetup, is State.Idle -> {
                // Don't auto-start, wait for user to configure in UI
            }
            is State.Disconnected -> {
                // Try to reconnect with saved config
                startProvisioning()
            }
            else -> Unit
        }
    }

    fun stop() {
        Log.i(TAG, "stop")
        acsJob?.cancel()
        sipJob?.cancel()
        tachyonJob?.cancel()
        sipManager?.shutdown()
        tachyonClient?.shutdown()
        _state.value = State.Disconnected("Stopped by user")
    }

    private var acsClient: AcsClient? = null
    private var sipManager: SipManager? = null
    private var tachyonClient: TachyonClient? = null
    private var pendingOtpPhoneNumber: String? = null

    /**
     * Start RCS provisioning flow.
     * 1. Detect MCC/MNC from SIM or use manual phone number
     * 2. Construct ACS URL
     * 3. Send HTTP GET to ACS
     * 4. Handle response (200 with XML config, or 511 requiring SMS OTP)
     * 5. Parse XML, extract SIP credentials
     * 6. SIP REGISTER
     * 7. Connect Tachyon gRPC
     */
    fun startProvisioning(phoneNumberE164: String? = null) {
        acsJob?.cancel()
        acsJob = scope.launch {
            _state.value = State.Provisioning
            try {
                Log.i(TAG, "Starting RCS provisioning for $phoneNumberE164")
                phoneNumber = phoneNumberE164
                pendingOtpPhoneNumber = phoneNumberE164

                if (acsClient == null) {
                    acsClient = AcsClient(appContext)
                }

                // Phase 2: ACS provisioning via GSMA RCC.14
                val response = acsClient!!.provision(phoneNumberE164, otp = null)

                when (response) {
                    is AcsResponse.Success -> {
                        Log.i(TAG, "ACS provisioning successful!")
                        Log.i(TAG, "P-CSCF: ${response.config.pcscfAddress}")
                        Log.i(TAG, "SIP URI: ${response.config.publicUserIdentity}")
                        Log.i(TAG, "Auth: ${response.config.authType}, user: ${response.config.username}")
                        Log.i(TAG, "Services: chat=${response.config.chatAuth}, " +
                                "group=${response.config.groupChatAuth}, " +
                                "ft=${response.config.ftAuth}")

                        rcsConfig = response.config

                        // Phase 3: Initialize SIP stack with ACS credentials
                        _state.value = State.Connecting
                        if (sipManager == null) {
                            sipManager = SipManager(appContext)
                        }
                        val sipOk = sipManager!!.init(response.config)
                        if (!sipOk) {
                            Log.w(TAG, "SIP init failed (expected - PJSIP not integrated yet)")
                            // Continue anyway for now to demonstrate ACS flow works
                        } else {
                            val registered = sipManager!!.register()
                            if (!registered) {
                                Log.w(TAG, "SIP REGISTER failed")
                            }
                        }

                        // Phase 4: Initialize Tachyon gRPC client for Google Jibe
                        if (tachyonClient == null) {
                            tachyonClient = TachyonClient(appContext)
                        }
                        val tachyonOk = tachyonClient!!.init(phoneNumber ?: "")
                        if (!tachyonOk) {
                            Log.w(TAG, "Tachyon init failed (expected - requires reverse engineering)")
                        } else {
                            // Start message stream
                            tachyonClient!!.startMessageStream { msg ->
                                // Convert TachyonMessage to GMEvent and emit
                                // TODO: Implement message conversion and event emission
                            }
                        }

                        // For now, mark as connected if ACS succeeded
                        // In full implementation, require SIP+Tachyon success
                        _state.value = State.Connected

                        // TODO: Persist config to SharedPreferences/Room for future sessions
                    }
                    is AcsResponse.NeedsOtp -> {
                        Log.i(TAG, "ACS requires OTP verification")
                        _state.value = State.AwaitingOtp
                        // TODO: Register SMS receiver for port 37273 to auto-capture OTP
                        // For now user enters manually in UI
                    }
                    is AcsResponse.Forbidden -> {
                        _state.value = State.Disconnected("ACS forbidden: ${response.reason ?: "not authorized for RCS"}")
                    }
                    is AcsResponse.NeedsUserAction -> {
                        _state.value = State.Disconnected("ACS requires user action: ${response.message ?: "accept terms"}")
                    }
                    is AcsResponse.RetryLater -> {
                        _state.value = State.Disconnected("ACS unavailable, retry in ${response.retryAfterSeconds}s")
                    }
                    is AcsResponse.Error -> {
                        _state.value = State.Disconnected("ACS error ${response.code}: ${response.message ?: "unknown"}")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Provisioning failed", e)
                _state.value = State.Disconnected(e.message ?: "Provisioning failed")
            }
        }
    }

    /**
     * Submit OTP received via SMS during ACS provisioning (511 flow).
     */
    fun submitOtp(otpCode: String) {
        acsJob?.cancel()
        acsJob = scope.launch {
            _state.value = State.Provisioning
            try {
                Log.i(TAG, "Submitting OTP: $otpCode")
                val client = acsClient ?: AcsClient(appContext).also { acsClient = it }

                val response = client.provision(pendingOtpPhoneNumber, otp = otpCode)

                when (response) {
                    is AcsResponse.Success -> {
                        Log.i(TAG, "ACS OTP verification successful!")
                        rcsConfig = response.config
                        _state.value = State.Connected
                    }
                    is AcsResponse.NeedsOtp -> {
                        _state.value = State.Disconnected("OTP invalid or expired")
                    }
                    else -> {
                        _state.value = State.Disconnected("OTP verification failed")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "OTP submission failed", e)
                _state.value = State.Disconnected(e.message ?: "OTP failed")
            }
        }
    }

    suspend fun sendMessage(conversationId: String, body: String): Boolean {
        Log.i(TAG, "sendMessage to $conversationId: $body")
        // Extract phone number from conversation ID (format: "rcs:+1234567890" or similar)
        val toNumber = conversationId.substringAfter("rcs:", conversationId)

        // Try Tachyon first (Google Jibe path), fall back to SIP (standard RCS path)
        tachyonClient?.let { tachyon ->
            val sent = tachyon.sendMessage(toNumber, body, "msg-${System.currentTimeMillis()}")
            if (sent) return true
        }

        sipManager?.let { sip ->
            return sip.sendMessage(toNumber, body, "msg-${System.currentTimeMillis()}")
        }

        Log.w(TAG, "No active transport (SIP or Tachyon) available for sending")
        return false
    }

    suspend fun sendMedia(
        conversationId: String,
        bytes: ByteArray,
        mime: String,
        fileName: String,
        caption: String?,
    ): Boolean {
        Log.i(TAG, "sendMedia to $conversationId: $fileName ($mime, ${bytes.size} bytes)")
        val toNumber = conversationId.substringAfter("rcs:", conversationId)

        // Try Tachyon first, then SIP/FT
        tachyonClient?.let { tachyon ->
            val sent = tachyon.sendFile(toNumber, bytes, mime, fileName)
            if (sent) return true
        }

        sipManager?.let { sip ->
            return sip.sendFile(toNumber, bytes, mime, fileName)
        }

        return false
    }

    suspend fun sendPoll(
        conversationId: String,
        question: String,
        options: List<String>,
        allowMultiple: Boolean,
    ): Boolean {
        // RCS UP doesn't define polls natively - could send as text or use bot framework
        return false
    }

    suspend fun sendReaction(
        messageId: String,
        conversationId: String,
        emoji: String,
        add: Boolean,
    ): Boolean {
        // TODO: RCS reactions via CPIM extensions or bot commands
        return false
    }

    suspend fun sendTyping(conversationId: String): Boolean {
        // TODO: SIP MESSAGE with isComposing XML body per RFC 3994
        return false
    }

    suspend fun sendReadReceipt(
        conversationId: String,
        messageId: String?,
        timestamp: Long,
    ): Boolean {
        // TODO: SIP MESSAGE with IMDN delivered/displayed notification per RFC 5438
        return false
    }

    suspend fun deleteThread(conversationId: String, fromMessageRequest: Boolean = false): Boolean {
        // Local delete only - RCS has no server-side delete concept like Signal
        return true
    }

    suspend fun acceptMessageRequest(conversationId: String): Boolean {
        // RCS doesn't have message requests like Signal/Meta
        return false
    }

    suspend fun searchContacts(query: String): List<ContactSuggestion> {
        // TODO: RCS capability discovery via SIP OPTIONS to check if number supports RCS
        return emptyList()
    }

    fun fetchMessages(conversationId: String) {
        // RCS messages arrive via SIP or Tachyon push, no fetch needed
        // But we could query message history from server if supported
    }

    fun forceResync() {
        Log.i(TAG, "forceResync")
        // TODO: Re-fetch conversations and messages from server
    }
}
