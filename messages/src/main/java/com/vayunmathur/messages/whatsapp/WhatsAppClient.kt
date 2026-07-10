package com.vayunmathur.messages.whatsapp

import android.content.Context
import android.util.Base64
import android.util.Log
import com.vayunmathur.messages.data.MessageSource
import com.vayunmathur.messages.gmessages.GMEvent
import com.vayunmathur.messages.util.ContactSuggestion
import com.vayunmathur.messages.whatsapp.e2e.WhatsAppE2E
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.whispersystems.libsignal.state.PreKeyBundle
import com.vayunmathur.messages.whatsapp.proto.WhatsAppE2EProto
import com.vayunmathur.messages.whatsapp.proto.WhatsAppAppStateProto
import java.security.SecureRandom
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object WhatsAppClient {

    private const val TAG = "WhatsAppClient"

    sealed interface State {
        data object Idle : State
        data object NeedsSetup : State
        data class AwaitingQrScan(val qrData: String) : State
        data object Connecting : State
        data object Connected : State
        data class Disconnected(val reason: String) : State
    }

    val source: MessageSource = MessageSource.WHATSAPP

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GMEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<GMEvent> = _events.asSharedFlow()

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()

    private lateinit var appContext: Context
    private var authData: WhatsAppAuthData? = null
    // Use WebView-based WebSocket to bypass TLS fingerprinting
    // WhatsApp blocks non-browser TLS fingerprints (JA3). WebView uses Chromium's
    // network stack which is indistinguishable from Chrome browser.
    private var webSocket: WebViewWebSocket? = null
    // Collector jobs for the current socket; cancelled on teardown so old (zombie) sockets
    // don't keep running keepalives or trigger reconnects, which causes self-conflicts.
    private var socketCollectorJobs: MutableList<Job> = mutableListOf()
    // Set when the server tells us this session is terminal (conflict/replaced/device removed),
    // so the disconnect handler does not start a pointless reconnect loop.
    private var suppressReconnect = false
    private var db: WhatsAppDatabase? = null
    private var e2e: WhatsAppE2E? = null
    private var backfillJob: Job? = null
    private var qrJob: Job? = null
    private var qrRotateJob: Job? = null

    // Pending IQ request/response correlation (by stanza id) for prekey fetch/upload/count.
    private val pendingIqs = ConcurrentHashMap<String, CompletableDeferred<WhatsAppProtocol.Node>>()

    private val nameCache = ConcurrentHashMap<String, String>()
    private val lidToPhoneMap = ConcurrentHashMap<String, String>()
    private val undecryptableTracker = ConcurrentHashMap<String, Int>()
    private val pendingMessageIDs: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private val processedEditIDs: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    // Groups we've already fetched metadata for this session, so we publish the group's
    // ConversationUpdate (name / isGroup / participants) at most once instead of on every event.
    private val knownGroups: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private val pollSecrets = ConcurrentHashMap<String, ByteArray>()
    // Cached media upload connection (host, auth, expiryEpochMs) from the w:m media_conn IQ.
    private var mediaConnCache: Triple<String, String, Long>? = null

    // Recently sent 1:1 messages (id -> padded plaintext + own-device DSM), kept so we can
    // re-encrypt and resend to a specific device when the peer asks for a retry
    // (<receipt type="retry">). Without this, a message the recipient can't decrypt is acked
    // locally (send "succeeds") but is never actually delivered.
    private data class SentDM(
        val to: String,
        val type: String,
        val msgPlaintextPadded: ByteArray,
        val dsmPlaintextPadded: ByteArray?,
    )
    private val recentSentDMs: MutableMap<String, SentDM> = Collections.synchronizedMap(
        object : LinkedHashMap<String, SentDM>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SentDM>?): Boolean = size > 200
        }
    )
    // Per-(messageId|deviceJid) resend cap so a stuck peer can't loop us forever.
    private val retryResendCounts = ConcurrentHashMap<String, Int>()

    // Whether this account has read receipts enabled (privacy setting). Default true (WhatsApp
    // default); refreshed from the server after <success>. When false we must not send read
    // receipts (the user turned them off).
    @Volatile private var readReceiptsEnabled = true

    private const val MAX_RECONNECT_ATTEMPTS = 10
    private const val INITIAL_RECONNECT_DELAY_MS = 1000L
    private const val MAX_RECONNECT_DELAY_MS = 60_000L
    private const val MAX_FILE_SIZE = 50L * 1024 * 1024

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        db = WhatsAppDatabase.getDatabase(appContext)
        Log.i(TAG, "init")
        runBlocking {
            val auth = WhatsAppAuthData.load(appContext)
            if (auth != null) {
                authData = auth
                _state.value = State.Connecting
            } else {
                _state.value = State.NeedsSetup
            }
        }
    }

    fun start() {
        if (!initialized.get()) return
        // Only skip if fully connected. (Connecting is intentionally NOT skipped: a stale/stuck
        // Connecting state must be able to retry; connect() calls teardownSocket() first so an
        // overlapping attempt can't leave two live sockets.)
        if (_state.value is State.Connected) return
        scope.launch {
            val auth = WhatsAppAuthData.load(appContext) ?: run {
                _state.value = State.NeedsSetup
                return@launch
            }
            authData = auth
            connect(auth)
        }
    }

    fun stop() {
        Log.i(TAG, "stop — clearing WhatsApp session")
        backfillJob?.cancel()
        qrJob?.cancel()
        qrRotateJob?.cancel()
        reconnectJob?.cancel()
        reconnectAttempts = 0
        webSocket?.disconnect()
        webSocket = null
        nameCache.clear()
        knownGroups.clear()
        scope.launch { WhatsAppAuthData.clear(appContext) }
        _state.value = State.NeedsSetup
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "Max reconnection attempts ($MAX_RECONNECT_ATTEMPTS) reached")
            _state.value = State.Disconnected("Max reconnection attempts reached")
            return
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayMs = minOf(
                INITIAL_RECONNECT_DELAY_MS * (1L shl reconnectAttempts),
                MAX_RECONNECT_DELAY_MS
            )
            Log.i(TAG, "Reconnecting in ${delayMs}ms (attempt ${reconnectAttempts + 1}/$MAX_RECONNECT_ATTEMPTS)")
            _state.value = State.Connecting
            delay(delayMs)
            reconnectAttempts++
            val auth = authData ?: return@launch
            try {
                connect(auth)
            } catch (e: Exception) {
                Log.e(TAG, "Reconnection failed", e)
                scheduleReconnect()
            }
        }
    }

    private class ProvisioningKeys(
        val noisePriv: ByteArray,
        val noisePub: ByteArray,
        val identityPriv: ByteArray,
        val identityPub: ByteArray,
        val advSecretKey: ByteArray,
        val registrationId: Int,
        val signedPreKeyId: Int,
        val signedPreKeyPriv: ByteArray,
        val signedPreKeyPub: ByteArray,
        val signedPreKeySig: ByteArray,
    )

    private var provisioning: ProvisioningKeys? = null

    fun startProvisioning() {
        _state.value = State.Connecting
        WhatsAppDiag.clear()
        WhatsAppDiag.log(TAG, "startProvisioning: generating keys, connecting…")
        qrRotateJob?.cancel()
        qrJob?.cancel()
        qrJob = scope.launch {
            try {
                // 1. Generate Noise + identity X25519 key pairs, signed prekey (with real
                //    signature), registration id and ADV secret — stable for the QR lifetime.
                val (noisePriv, noisePub) = WhatsAppProtocol.generateX25519KeyPair()
                val (identityPriv, identityPub) = WhatsAppProtocol.generateX25519KeyPair()
                val (spkPriv, spkPub) = WhatsAppProtocol.generateX25519KeyPair()
                val advSecretKey = ByteArray(32).apply { random.nextBytes(this) }
                val registrationId = random.nextInt(0x3FFF) + 1
                val signedPreKeySig = WhatsAppE2E.signSignedPreKey(identityPriv, spkPub)
                provisioning = ProvisioningKeys(
                    noisePriv, noisePub, identityPriv, identityPub, advSecretKey,
                    registrationId, 1, spkPriv, spkPub, signedPreKeySig,
                )

                // 2. Connect the provisioning socket. The server will send a pair-device IQ
                //    carrying the QR ref once the Noise handshake completes.
                teardownSocket()
                val ws = WebViewWebSocket(
                    appContext,
                    null,
                    WebViewWebSocket.RegistrationData(
                        noisePrivateKey = noisePriv,
                        noisePublicKey = noisePub,
                        registrationId = registrationId,
                        identityPublicKey = identityPub,
                        signedPreKeyId = 1,
                        signedPreKeyPublic = spkPub,
                        signedPreKeySignature = signedPreKeySig,
                    ),
                )
                webSocket = ws
                socketCollectorJobs.add(
                    scope.launch {
                        ws.connectionState.collect { connState ->
                            when (connState) {
                                is WebViewWebSocket.ConnectionState.Connecting ->
                                    WhatsAppDiag.log(TAG, "provisioning socket: Connecting")
                                is WebViewWebSocket.ConnectionState.Connected ->
                                    WhatsAppDiag.log(TAG, "provisioning socket: Connected, awaiting pair-device ref")
                                is WebViewWebSocket.ConnectionState.Disconnected -> {
                                    WhatsAppDiag.log(TAG, "provisioning socket: Disconnected (${connState.reason})")
                                    val pairedAuth = authData
                                    if (pairedAuth != null) {
                                        // Pairing completed: the server drops the provisioning
                                        // socket; reconnect as the registered device (login payload).
                                        WhatsAppDiag.log(TAG, "Provisioning closed post-pair; reconnecting as registered device")
                                        connect(pairedAuth)
                                    } else if (_state.value is State.AwaitingQrScan || _state.value is State.Connecting) {
                                        _state.value = State.Disconnected(connState.reason)
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                )
                socketCollectorJobs.add(
                    scope.launch {
                        ws.messages.collect { data -> handleProvisioningMessage(data) }
                    }
                )
                ws.connect()
            } catch (e: Exception) {
                Log.e(TAG, "Provisioning failed", e)
                _state.value = State.Disconnected("Provisioning failed: ${e.message}")
            }
        }
    }

    private fun handleProvisioningMessage(data: ByteArray) {
        scope.launch {
            try {
                val node = WhatsAppProtocol.decodeNode(data)
                WhatsAppDiag.log(TAG, "\u2190 stanza ${nodeSummary(node)}")
                if (node.tag != "iq") return@launch
                val child = node.getChildren().firstOrNull() ?: return@launch
                when (child.tag) {
                    "pair-device" -> handlePairDevice(node, child)
                    "pair-success" -> handlePairSuccess(node, child)
                }
            } catch (e: Exception) {
                WhatsAppDiag.log(TAG, "decode/handle provisioning stanza FAILED: ${e.javaClass.simpleName}: ${e.message} (raw=${WhatsAppDiag.preview(data)})")
            }
        }
    }

    /** Compact human-readable summary of a decoded binary-XML node for on-screen diagnostics. */
    private fun nodeSummary(node: WhatsAppProtocol.Node): String {
        val attrs = if (node.attrs.isEmpty()) "" else " " + node.attrs.entries.joinToString(" ") { "${it.key}=${it.value}" }
        val kids = node.getChildren()
        val kidPart = if (kids.isEmpty()) "" else " {" + kids.joinToString(",") { it.tag } + "}"
        return "<${node.tag}$attrs>$kidPart"
    }

    /** Ack the pair-device IQ and rotate through the QR refs. Ref whatsmeow pair.go + qrchan.go. */
    private fun handlePairDevice(node: WhatsAppProtocol.Node, pairDevice: WhatsAppProtocol.Node) {
        val keys = provisioning ?: return
        val from = (node.attrs["from"] ?: "s.whatsapp.net").removePrefix("@").ifEmpty { "s.whatsapp.net" }
        val id = node.attrs["id"] ?: return
        // Ack
        webSocket?.send(
            WhatsAppProtocol.encodeNode(
                WhatsAppProtocol.Node(
                    tag = "iq",
                    attrs = mapOf("to" to from, "id" to id, "type" to "result"),
                )
            )
        )
        val refs = pairDevice.getChildren()
            .filter { it.tag == "ref" }
            .mapNotNull { it.data?.toString(Charsets.UTF_8) }
        if (refs.isEmpty()) {
            WhatsAppDiag.log(TAG, "pair-device contained no refs")
            return
        }
        WhatsAppDiag.log(TAG, "pair-device: ${refs.size} ref(s) received, rotating QR")

        // Rotate through the refs the way whatsmeow does (qrchan.go emitQRs): the first code
        // gets 60s when the server sent the full batch (6), otherwise each gets 20s. Showing
        // only the first ref gives a single ~20s window, after which the server ends the stream.
        qrRotateJob?.cancel()
        qrRotateJob = scope.launch {
            refs.forEachIndexed { index, ref ->
                val qrData = "https://wa.me/settings/linked_devices#" + listOf(
                    ref,
                    Base64.encodeToString(keys.noisePub, Base64.NO_WRAP),
                    Base64.encodeToString(keys.identityPub, Base64.NO_WRAP),
                    Base64.encodeToString(keys.advSecretKey, Base64.NO_WRAP),
                    "1", // PairClientChrome (whatsmeow pair-code.go)
                ).joinToString(",")
                _state.value = State.AwaitingQrScan(qrData)
                WhatsAppDiag.log(TAG, "QR ${index + 1}/${refs.size} shown (ref=${ref.take(12)}…), scan now")
                val timeoutMs = if (index == 0 && refs.size >= 6) 60_000L else 20_000L
                delay(timeoutMs)
            }
            WhatsAppDiag.log(TAG, "All ${refs.size} QR refs expired without a scan")
            _state.value = State.Disconnected("QR code expired — tap Try Again")
        }
    }

    /**
     * Verify the ADV device identity, sign it, persist auth, and confirm with
     * pair-device-sign. Ref whatsmeow pair.go handlePairSuccess/handlePair.
     */
    private suspend fun handlePairSuccess(node: WhatsAppProtocol.Node, pairSuccess: WhatsAppProtocol.Node) {
        val keys = provisioning ?: return
        val reqId = node.attrs["id"] ?: return
        val from = (node.attrs["from"] ?: "s.whatsapp.net").removePrefix("@").ifEmpty { "s.whatsapp.net" }
        try {
            qrRotateJob?.cancel()
            WhatsAppDiag.log(TAG, "pair-success received, verifying device identity…")
            val deviceIdentityBytes = pairSuccess.getChildByTag("device-identity")?.data
                ?: throw IllegalStateException("missing device-identity")
            val deviceNode = pairSuccess.getChildByTag("device")
            val jid = deviceNode?.attrs?.get("jid") ?: throw IllegalStateException("missing device jid")
            val lid = deviceNode.attrs["lid"] ?: ""

            val container = com.vayunmathur.messages.whatsapp.proto.WhatsAppAdvProto
                .ADVSignedDeviceIdentityHMAC.parseFrom(deviceIdentityBytes)
            val details = container.details.toByteArray()

            // HMAC check (E2EE account type; hosted accounts use a different prefix).
            val expectedHmac = WhatsAppProtocol.hmacSha256(keys.advSecretKey, details)
            if (!expectedHmac.contentEquals(container.getHMAC().toByteArray())) {
                Log.e(TAG, "ADV HMAC mismatch")
                sendPairError(from, reqId, 401, "hmac-mismatch")
                _state.value = State.Disconnected("Pairing failed: HMAC mismatch")
                return
            }

            val deviceIdentity = com.vayunmathur.messages.whatsapp.proto.WhatsAppAdvProto
                .ADVSignedDeviceIdentity.parseFrom(details)
            val accountSignatureKey = deviceIdentity.accountSignatureKey.toByteArray()
            val accountSignature = deviceIdentity.accountSignature.toByteArray()
            val deviceDetails = com.vayunmathur.messages.whatsapp.proto.WhatsAppAdvProto
                .ADVDeviceIdentity.parseFrom(deviceIdentity.details.toByteArray())

            // Verify account signature over {6,0} || details || ownIdentityPub.
            val accountMsg = concat(byteArrayOf(6, 0), deviceIdentity.details.toByteArray(), keys.identityPub)
            val accountKey = org.signal.libsignal.protocol.ecc.ECPublicKey.fromPublicKeyBytes(accountSignatureKey)
            if (accountSignatureKey.size != 32 || accountSignature.size != 64 ||
                !accountKey.verifySignature(accountMsg, accountSignature)
            ) {
                Log.e(TAG, "ADV account signature invalid")
                sendPairError(from, reqId, 401, "signature-mismatch")
                _state.value = State.Disconnected("Pairing failed: signature mismatch")
                return
            }

            // Device signature over {6,1} || details || ownIdentityPub || accountSignatureKey.
            val deviceMsg = concat(byteArrayOf(6, 1), deviceIdentity.details.toByteArray(), keys.identityPub, accountSignatureKey)
            val deviceSignature = org.signal.libsignal.protocol.ecc.ECPrivateKey(keys.identityPriv)
                .calculateSignature(deviceMsg)

            // Self-verify our own device signature against the identity pubkey we advertised.
            // If our OWN library can't verify it, the phone can't either → "couldn't link device".
            val devSelfOk = try {
                org.signal.libsignal.protocol.ecc.ECPublicKey.fromPublicKeyBytes(keys.identityPub)
                    .verifySignature(deviceMsg, deviceSignature)
            } catch (e: Exception) { false }
            // Also re-verify the signed-prekey signature we generated at provisioning time.
            val spkMsg = ByteArray(33).also { it[0] = 0x05; System.arraycopy(keys.signedPreKeyPub, 0, it, 1, 32) }
            val spkSelfOk = try {
                org.signal.libsignal.protocol.ecc.ECPublicKey.fromPublicKeyBytes(keys.identityPub)
                    .verifySignature(spkMsg, keys.signedPreKeySig)
            } catch (e: Exception) { false }
            WhatsAppDiag.log(TAG, "self-verify: deviceSig=$devSelfOk signedPreKeySig=$spkSelfOk (identityPub=${keys.identityPub.size}B priv=${keys.identityPriv.size}B)")

            val signedIdentity = deviceIdentity.toBuilder()
                .setDeviceSignature(com.google.protobuf.ByteString.copyFrom(deviceSignature))
                .build()
            // Account (full, used in device-identity node for pkmsg sends).
            val accountFull = signedIdentity.toByteArray()
            // Self-signed (account signature key cleared) for the confirmation node.
            val selfSigned = signedIdentity.toBuilder().clearAccountSignatureKey().build().toByteArray()

            val deviceId = jid.substringBefore("@").substringAfter(":", "0").toIntOrNull() ?: 0
            val auth = WhatsAppAuthData(
                phoneNumber = jid.substringBefore("@").substringBefore(":"),
                pushName = pairSuccess.getChildByTag("platform")?.attrs?.get("name") ?: "User",
                wid = jid,
                noisePrivateKey = Base64.encodeToString(keys.noisePriv, Base64.NO_WRAP),
                noisePublicKey = Base64.encodeToString(keys.noisePub, Base64.NO_WRAP),
                identityPrivateKey = Base64.encodeToString(keys.identityPriv, Base64.NO_WRAP),
                identityPublicKey = Base64.encodeToString(keys.identityPub, Base64.NO_WRAP),
                registrationId = keys.registrationId,
                signedPreKeyId = keys.signedPreKeyId,
                signedPreKeyPublic = Base64.encodeToString(keys.signedPreKeyPub, Base64.NO_WRAP),
                signedPreKeyPrivate = Base64.encodeToString(keys.signedPreKeyPriv, Base64.NO_WRAP),
                signedPreKeySignature = Base64.encodeToString(keys.signedPreKeySig, Base64.NO_WRAP),
                advSecretKey = Base64.encodeToString(keys.advSecretKey, Base64.NO_WRAP),
                deviceId = deviceId,
                lid = lid,
                accountSignedDeviceIdentity = Base64.encodeToString(accountFull, Base64.NO_WRAP),
            )
            WhatsAppAuthData.save(appContext, auth)
            authData = auth
            WhatsAppDiag.log(TAG, "pairing verified & saved for $jid; sending pair-device-sign confirmation")

            // Confirm pairing: pair-device-sign with the self-signed identity.
            val keyIndex = if (deviceDetails.hasKeyIndex()) deviceDetails.keyIndex else 0
            val confirm = WhatsAppProtocol.Node(
                tag = "iq",
                attrs = mapOf("to" to from, "type" to "result", "id" to reqId),
                content = listOf(
                    WhatsAppProtocol.Node(
                        tag = "pair-device-sign",
                        content = listOf(
                            WhatsAppProtocol.Node(
                                tag = "device-identity",
                                attrs = mapOf("key-index" to keyIndex.toString()),
                                data = selfSigned,
                            )
                        ),
                    )
                ),
            )
            val encodedConfirm = WhatsAppProtocol.encodeNode(confirm)
            val sent = webSocket?.send(encodedConfirm) ?: false
            WhatsAppDiag.log(TAG, "pair-device-sign sent=$sent to=$from keyIndex=$keyIndex selfSigned=${selfSigned.size}B deviceSig=${deviceSignature.size}B encoded=${encodedConfirm.size}B")
            provisioning = null
            Log.i(TAG, "Pairing confirmed for $jid; awaiting reconnect with credentials")
            // The server disconnects after this; reconnect() / start() will connect as the
            // registered device and prekeys will be uploaded on the Connected event.
        } catch (e: Exception) {
            WhatsAppDiag.log(TAG, "pair-success handling FAILED: ${e.javaClass.simpleName}: ${e.message}")
            sendPairError(from, reqId, 500, "internal-error")
            _state.value = State.Disconnected("Pairing failed: ${e.message}")
        }
    }

    private fun sendPairError(to: String, id: String, code: Int, text: String) {
        webSocket?.send(
            WhatsAppProtocol.encodeNode(
                WhatsAppProtocol.Node(
                    tag = "iq",
                    attrs = mapOf("to" to to, "type" to "error", "id" to id),
                    content = listOf(
                        WhatsAppProtocol.Node(
                            tag = "error",
                            attrs = mapOf("code" to code.toString(), "text" to text),
                        )
                    ),
                )
            )
        )
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var off = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, off, p.size)
            off += p.size
        }
        return out
    }

    private fun ensureE2E(auth: WhatsAppAuthData): WhatsAppE2E? {
        val database = db ?: return null
        var inst = e2e
        if (inst == null) {
            inst = WhatsAppE2E(database, auth)
            inst.ensureSignedPreKeyStored()
            e2e = inst
        }
        return inst
    }

    /**
     * Send an <iq> stanza and await its result/error response by id.
     * Returns the response node, or null on timeout.
     */
    private suspend fun sendIqAndWait(node: WhatsAppProtocol.Node, timeoutMs: Long = 30_000): WhatsAppProtocol.Node? {
        val ws = webSocket ?: return null
        val id = node.attrs["id"] ?: return null
        val deferred = CompletableDeferred<WhatsAppProtocol.Node>()
        pendingIqs[id] = deferred
        return try {
            if (!ws.send(WhatsAppProtocol.encodeNode(node))) {
                pendingIqs.remove(id)
                return null
            }
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pendingIqs.remove(id)
        }
    }

    /**
     * Upload one-time + signed prekeys to the server.
     * Ref whatsmeow prekeys.go uploadPreKeys. [initialUpload] uploads a large batch after pair.
     */
    /**
     * Upload prekeys on connect if the server is likely low. Does an initial large batch
     * when the local prekey store is empty (freshly paired). Ref whatsmeow prekeys.go.
     */
    private suspend fun maybeUploadPreKeys() {
        val database = db ?: return
        val count = try { database.e2ePreKeyDao().getCount() } catch (e: Exception) { return }
        val unuploaded = try { database.e2ePreKeyDao().getUnuploaded().size } catch (e: Exception) { 0 }
        WhatsAppDiag.log(TAG, "prekeys: local count=$count unuploaded=$unuploaded")
        when {
            count == 0 -> uploadPreKeys(initialUpload = true)
            unuploaded > 0 -> uploadPreKeys(initialUpload = false)
            else -> WhatsAppDiag.log(TAG, "prekeys: nothing to upload")
        }
    }

    /**
     * Upload one-time + signed prekeys to the server.
     * Ref whatsmeow prekeys.go uploadPreKeys. [initialUpload] uploads a large batch after pair.
     */
    private suspend fun uploadPreKeys(initialUpload: Boolean) {
        val auth = authData ?: return
        val crypto = ensureE2E(auth) ?: return
        val content = crypto.buildPreKeyUploadContent(initialUpload)
        val iq = WhatsAppProtocol.Node(
            tag = "iq",
            attrs = mapOf(
                "id" to generateMessageId(),
                "type" to "set",
                "xmlns" to "encrypt",
                "to" to "s.whatsapp.net",
            ),
            content = content,
        )
        WhatsAppDiag.log(TAG, "prekeys: uploading (initial=$initialUpload, ${content.size} nodes)…")
        val resp = sendIqAndWait(iq)
        if (resp != null && resp.attrs["type"] != "error") {
            crypto.markPreKeysUploaded()
            WhatsAppDiag.log(TAG, "prekeys: upload OK (initial=$initialUpload)")
        } else {
            WhatsAppDiag.log(TAG, "prekeys: upload FAILED/timeout (resp=${resp?.attrs?.get("type") ?: "null"})")
        }
    }

    /**
     * Fetch a peer's prekey bundle and establish an outbound session if none exists.
     * Ref whatsmeow prekeys.go fetchPreKeys + send.go encryptMessageForDevice.
     */
    private suspend fun ensureSession(jid: String): Boolean {
        val auth = authData ?: return false
        val crypto = ensureE2E(auth) ?: return false
        if (crypto.hasSession(jid)) return true

        val device = jid.substringBefore("@").substringAfter(":", "0").toIntOrNull() ?: 0
        val iq = WhatsAppProtocol.Node(
            tag = "iq",
            attrs = mapOf(
                "id" to generateMessageId(),
                "type" to "get",
                "xmlns" to "encrypt",
                "to" to "s.whatsapp.net",
            ),
            content = listOf(
                WhatsAppProtocol.Node(
                    tag = "key",
                    content = listOf(
                        WhatsAppProtocol.Node(
                            tag = "user",
                            attrs = mapOf("jid" to jid, "reason" to "identity"),
                        )
                    ),
                )
            ),
        )
        val resp = sendIqAndWait(iq) ?: return false
        val list = resp.getChildByTag("list") ?: return false
        val userNode = list.getChildren().firstOrNull { it.tag == "user" } ?: return false
        val bundle: PreKeyBundle = crypto.parsePreKeyBundleNode(device, userNode) ?: return false
        return try {
            crypto.processPreKeyBundle(jid, bundle)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process prekey bundle for $jid", e)
            false
        }
    }

    /**
     * Resolve the device list for the given (bare) user JIDs via a usync query.
     * Ref whatsmeow user.go GetUserDevices. Returns wire device JIDs ("user@server" for device 0,
     * "user:N@server" otherwise). Returns empty on failure (callers fall back to the bare JID).
     */
    private suspend fun getUserDevices(userJids: List<String>): List<String> {
        if (userJids.isEmpty()) return emptyList()
        val bareUsers = userJids.map { it.substringBefore(":").let { u -> if (u.contains("@")) u else "$u@s.whatsapp.net" } }
        val iq = WhatsAppProtocol.buildUsyncDevicesQuery(bareUsers, generateMessageId(), generateMessageId())
        val resp = sendIqAndWait(iq, timeoutMs = 10_000) ?: run {
            WhatsAppDiag.log(TAG, "usync: no response for $bareUsers (timeout)")
            return emptyList()
        }
        val list = resp.getChildByTag("usync")?.getChildByTag("list") ?: run {
            WhatsAppDiag.log(TAG, "usync: malformed response (no list) for $bareUsers")
            return emptyList()
        }
        val devices = mutableListOf<String>()
        list.getChildren().filter { it.tag == "user" }.forEach { user ->
            val userJid = user.attrs["jid"] ?: return@forEach
            val bareUser = userJid.substringBefore("@").substringBefore(":")
            val server = userJid.substringAfter("@", "s.whatsapp.net")
            val deviceList = user.getChildByTag("devices")?.getChildByTag("device-list") ?: return@forEach
            deviceList.getChildren().filter { it.tag == "device" }.forEach { d ->
                if (d.attrs["is_hosted"] == "true") return@forEach
                val devId = d.attrs["id"]?.toIntOrNull() ?: return@forEach
                devices.add(if (devId == 0) "$bareUser@$server" else "$bareUser:$devId@$server")
            }
        }
        WhatsAppDiag.log(TAG, "usync: $bareUsers -> ${devices.size} device(s)")
        return devices
    }

    /**
     * Query a group's participant JIDs via an interactive w:g2 query.
     * Ref whatsmeow group.go getGroupInfo.
     */
    private suspend fun queryGroupParticipants(groupJid: String): List<String> {
        val iq = WhatsAppProtocol.buildGroupParticipantsQuery(groupJid, generateMessageId())
        val resp = sendIqAndWait(iq) ?: return emptyList()
        val group = resp.getChildByTag("group") ?: return emptyList()
        return group.getChildren().filter { it.tag == "participant" }.mapNotNull { it.attrs["jid"] }
    }

    /**
     * Fetch a group's subject + participants (w:g2 interactive query) and publish a
     * [GMEvent.ConversationUpdate] so the thread shows up named + flagged as a group. Freshly
     * joined groups aren't in history sync, so without this the conversation never appears (and
     * group messages, which only upsert a bare row, look nameless / like a 1:1). Fetched at most
     * once per group per session ([knownGroups]); does NOT touch the unread badge on re-entry.
     * Ref whatsmeow group.go GetGroupInfo.
     */
    private suspend fun fetchAndEmitGroupInfo(groupJid: String) {
        if (!groupJid.contains("@g.us")) return
        if (!knownGroups.add(groupJid)) return
        val iq = WhatsAppProtocol.buildGroupParticipantsQuery(groupJid, generateMessageId())
        val resp = sendIqAndWait(iq) ?: run { knownGroups.remove(groupJid); return }
        val group = resp.getChildByTag("group") ?: run { knownGroups.remove(groupJid); return }
        val subject = group.attrs["subject"]?.ifEmpty { null }
        val participantJids = group.getChildren()
            .filter { it.tag == "participant" }
            .mapNotNull { it.attrs["jid"] }
        val participantNames = participantJids.map { resolveName(it) }
        val serviceData = if (participantNames.isNotEmpty()) {
            org.json.JSONObject()
                .put("participantNames", org.json.JSONArray(participantNames))
                .toString()
        } else null
        _events.emit(GMEvent.ConversationUpdate(
            source = MessageSource.WHATSAPP,
            conversationId = "wa:$groupJid",
            peerName = subject,
            peerPhone = null,
            avatarUrl = null,
            lastPreview = null,
            lastTimestamp = 0L,
            unreadCount = 0,
            isGroup = true,
            participantCount = participantJids.size,
            serviceData = serviceData,
        ))
    }

    /**
     * Fetch (and cache) the media upload host + auth token via the w:m media_conn IQ.
     * Ref whatsmeow mediaconn.go queryMediaConn. Returns (host, auth) or null.
     */
    private suspend fun mediaConn(): Pair<String, String>? {
        mediaConnCache?.let { if (System.currentTimeMillis() < it.third) return it.first to it.second }
        val iq = WhatsAppProtocol.buildMediaConnQuery(generateMessageId())
        val resp = sendIqAndWait(iq) ?: return null
        val mc = resp.getChildByTag("media_conn") ?: return null
        val auth = mc.attrs["auth"] ?: return null
        val ttl = mc.attrs["ttl"]?.toLongOrNull() ?: 300L
        val host = mc.getChildren().firstOrNull { it.tag == "host" }?.attrs?.get("hostname") ?: return null
        mediaConnCache = Triple(host, auth, System.currentTimeMillis() + ttl * 1000)
        return host to auth
    }

    /**
     * Establish sessions and Signal-encrypt a (padded) plaintext for each device, skipping our own
     * current device. Own other devices get [dsmPlaintextPadded] (the DeviceSentMessage); everyone
     * else gets [msgPlaintextPadded]. Mirrors whatsmeow send.go encryptMessageForDevices.
     */
    private suspend fun encryptForDevices(
        crypto: WhatsAppE2E,
        devices: List<String>,
        ownUser: String,
        ownDeviceJid: String,
        msgPlaintextPadded: ByteArray,
        dsmPlaintextPadded: ByteArray?,
    ): Pair<List<WhatsAppProtocol.ParticipantEnc>, Boolean> {
        val encs = mutableListOf<WhatsAppProtocol.ParticipantEnc>()
        var includeIdentity = false
        // Our own device number (from wid "user.agent:device@..."). usync returns devices as
        // "user:device@..." (no agent), so comparing against the raw wid never matches and we'd
        // fan out to OURSELVES — which the server rejects with <conflict type=device_removed>.
        val ownDeviceNum = ownDeviceJid.substringBefore("@").substringAfter(":", "0").toIntOrNull() ?: 0
        for (dev in devices.distinct()) {
            val devUser = dev.substringBefore("@").substringBefore(":").substringBefore(".")
            val devNum = dev.substringBefore("@").substringAfter(":", "0").toIntOrNull() ?: 0
            if (devUser == ownUser && devNum == ownDeviceNum) continue // skip our own device
            val plaintext = if (devUser == ownUser && dsmPlaintextPadded != null) dsmPlaintextPadded else msgPlaintextPadded
            if (!ensureSession(dev)) {
                Log.w(TAG, "No session for device $dev; skipping in fan-out")
                continue
            }
            val enc = try {
                crypto.encryptDM(dev, plaintext)
            } catch (e: Exception) {
                Log.w(TAG, "Encrypt failed for device $dev", e)
                continue
            }
            if (enc.type == "pkmsg") includeIdentity = true
            encs.add(WhatsAppProtocol.ParticipantEnc(dev, enc.type, enc.data))
        }
        return encs to includeIdentity
    }

    /** Cancel the current socket's collectors and disconnect it, so only one socket is ever live. */
    private fun teardownSocket() {
        socketCollectorJobs.forEach { it.cancel() }
        socketCollectorJobs.clear()
        webSocket?.disconnect()
        webSocket = null
    }

    private suspend fun connect(auth: WhatsAppAuthData) {
        _state.value = State.Connecting
        suppressReconnect = false
        // Ensure no previous socket (provisioning or a prior login attempt) is still alive,
        // otherwise overlapping sessions make the server reject us with <stream:error><conflict>.
        teardownSocket()

        // Use WebView-based WebSocket to bypass TLS fingerprinting
        val ws = WebViewWebSocket(appContext, auth)
        webSocket = ws
        socketCollectorJobs.add(
            scope.launch {
                ws.connectionState.collect { state ->
                    when (state) {
                        is WebViewWebSocket.ConnectionState.Connected -> {
                            WhatsAppDiag.log(TAG, "login socket: Noise connected, awaiting <success>")
                            ensureE2E(auth)
                        }
                        is WebViewWebSocket.ConnectionState.Disconnected -> {
                            WhatsAppDiag.log(TAG, "login socket: Disconnected (${state.reason})")
                            if (authData != null && !suppressReconnect) {
                                scheduleReconnect()
                            } else {
                                _state.value = State.Disconnected(state.reason)
                            }
                        }
                        else -> {}
                    }
                }
            }
        )
        socketCollectorJobs.add(
            scope.launch {
                ws.messages.collect { data ->
                    handleIncomingMessage(data)
                }
            }
        )
        ws.connect()
    }

    private fun handleIncomingMessage(data: ByteArray) {
        scope.launch {
            try {
                val node = WhatsAppProtocol.decodeNode(data)
                WhatsAppDiag.log(TAG, "← login stanza ${nodeSummary(node)}")

                // Server ack for an outbound stanza. An ack with an `error` attr means the server
                // REJECTED the message (it will never be delivered) — surface it prominently so a
                // "sent but not delivered" report can be diagnosed from logs.
                if (node.tag == "ack") {
                    val ackErr = node.attrs["error"]
                    if (ackErr != null) {
                        WhatsAppDiag.log(TAG, "← ack REJECTED class=${node.attrs["class"]} id=${node.attrs["id"]} error=$ackErr")
                    }
                }

                // Ack messages, notifications, and receipts (whatsmeow/receipt.go)
                if (node.tag == "message" || node.tag == "notification" || node.tag == "receipt") {
                    val ack = WhatsAppProtocol.buildAck(
                        nodeClass = node.tag,
                        nodeId = node.attrs["id"] ?: "",
                        from = node.attrs["from"] ?: "",
                        participant = node.attrs["participant"],
                        recipient = node.attrs["recipient"],
                        type = if (node.tag != "message") node.attrs["type"] else null,
                    )
                    webSocket?.send(WhatsAppProtocol.encodeNode(ack))
                }

                // Complete any pending IQ request awaiting this response (prekey fetch/upload).
                if (node.tag == "iq") {
                    val iqId = node.attrs["id"]
                    val pending = if (iqId != null) pendingIqs.remove(iqId) else null
                    if (pending != null) {
                        pending.complete(node)
                        return@launch
                    }
                }

                // Handle connection failure events (Go handleWALogout, ClientOutdated, TemporaryBan)
                if (node.tag == "failure") {
                    val reason = node.attrs["reason"] ?: "unknown"
                    WhatsAppDiag.log(TAG, "login <failure> reason=$reason full=${nodeSummary(node)}")
                    when (reason) {
                        "401" -> {
                            _state.value = State.Disconnected("Logged out from WhatsApp")
                            scope.launch { WhatsAppAuthData.clear(appContext) }
                            authData = null
                        }
                        "405" -> _state.value = State.Disconnected("Client outdated — update required")
                        "503" -> _state.value = State.Disconnected("Temporarily banned")
                        else -> _state.value = State.Disconnected("Connection failed: $reason")
                    }
                    return@launch
                }

                if (node.tag == "stream:error") {
                    val errorNode = node.content.firstOrNull()
                    val code = node.attrs["code"]
                    val conflict = node.getChildByTag("conflict")
                    val conflictType = conflict?.attrs?.get("type")
                    WhatsAppDiag.log(TAG, "login stream:error code=$code child=${errorNode?.tag} conflictType=$conflictType")
                    when {
                        // Device removed from the account on the phone, or another session took over.
                        conflict != null || code == "401" -> {
                            suppressReconnect = true
                            if (conflictType == "device_removed" || code == "401") {
                                WhatsAppDiag.log(TAG, "device removed/logged out — clearing credentials")
                                scope.launch { WhatsAppAuthData.clear(appContext) }
                                authData = null
                                _state.value = State.NeedsSetup
                                scope.launch { _events.emit(GMEvent.SourceLoggedOut(source)) }
                            } else {
                                // "replaced" or other conflict: another WhatsApp session is active.
                                _state.value = State.Disconnected("Session replaced by another device")
                            }
                        }
                        // 515 = restart required (normal after first pair-login); allow reconnect.
                        code == "515" -> _state.value = State.Disconnected("Restart required")
                        else -> _state.value = State.Disconnected("Stream error${if (code != null) " $code" else ""}")
                    }
                    return@launch
                }

                // Handle the server <success> stanza — the real authentication signal
                // (Go connectionevents.go handleConnectSuccess). Until this arrives the Noise
                // transport is up but the login is not yet authenticated.
                if (node.tag == "success") {
                    handleConnectSuccess(node)
                    return@launch
                }

                // Handle receipts with per-sender batching (Go handleWAReceipt)
                if (node.tag == "receipt") {
                    handleReceipt(node)
                    return@launch
                }

                // Handle chat presence with media type differentiation (Go handleWAChatPresence)
                if (node.tag == "chatstate") {
                    handleChatPresence(node)
                    return@launch
                }

                // Handle notifications (group changes, mute/pin/archive)
                if (node.tag == "notification") {
                    handleNotification(node)
                    return@launch
                }

                // <ib> info blocks: respond to <dirty> with <clean> so the phone finishes "syncing".
                if (node.tag == "ib") {
                    node.getChildByTag("dirty")?.let { handleDirty(it) }
                    node.getChildByTag("offline_preview")?.let {
                        WhatsAppDiag.log(TAG, "offline preview: count=${it.attrs["count"]} msg=${it.attrs["message"]} notif=${it.attrs["notification"]} receipt=${it.attrs["receipt"]}")
                    }
                    node.getChildByTag("offline")?.let {
                        WhatsAppDiag.log(TAG, "offline sync completed: count=${it.attrs["count"]}")
                    }
                    return@launch
                }

                if (node.tag != "message") return@launch

                // Check for undecryptable messages (Go handleWAUndecryptableMessage)
                val encNode = node.getChildByTag("enc")
                if (encNode?.data == null && node.attrs["type"] == "text") {
                    // No ciphertext at all (a decrypt-fail placeholder). Track it AND ask the
                    // sender to re-encrypt, otherwise this first-of-session message is lost for
                    // good. sendRetryReceipt is self-capped at 5 attempts.
                    trackUndecryptable(node)
                    sendRetryReceipt(node)
                    return@launch
                }

                val crypto = authData?.let { ensureE2E(it) }
                var decryptFailed = false
                val message = WhatsAppProtocol.parseMessage(node) { senderJid, encType, data ->
                    if (crypto == null) {
                        decryptFailed = true
                        return@parseMessage null
                    }
                    val result = try {
                        when (encType) {
                            "pkmsg" -> crypto.decryptDM(senderJid, isPreKey = true, ciphertext = data)
                            "msg" -> crypto.decryptDM(senderJid, isPreKey = false, ciphertext = data)
                            "skmsg" -> crypto.decryptGroup(node.attrs["from"] ?: "", senderJid, data)
                            else -> null
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "E2E decrypt failed ($encType) from $senderJid", e)
                        null
                    }
                    if (result == null) decryptFailed = true
                    result
                } ?: return@launch

                // Decrypt failure: ask the sender to re-encrypt (Go decryptMessages -> sendRetryReceipt).
                if (decryptFailed) {
                    val encTypes = node.getChildren()
                        .filter { it.tag == "enc" }
                        .mapNotNull { it.attrs["type"] }
                    WhatsAppDiag.log(TAG, "recv: DECRYPT FAILED id=${node.attrs["id"]} type=${node.attrs["type"]} from=${node.attrs["from"]} encs=$encTypes")
                    sendRetryReceipt(node)
                    return@launch
                }

                if (node.attrs["type"] == "poll" || WhatsAppProtocol.pollCreation(message.e2eMessage) != null ||
                    message.e2eMessage?.hasPollUpdateMessage() == true
                ) {
                    WhatsAppDiag.log(
                        TAG,
                        "poll recv: id=${message.id} parsedType=${message.messageType} " +
                            "create=${WhatsAppProtocol.pollCreation(message.e2eMessage) != null} " +
                            "vote=${message.e2eMessage?.hasPollUpdateMessage()} " +
                            "q=${message.pollData?.question} opts=${message.pollData?.options?.size} " +
                            "fromMe=${message.isFromMe}",
                    )
                }

                // History sync: the phone pushes message history as a protocolMessage notification
                // (inline for the initial bootstrap, or a downloadable encrypted blob otherwise).
                val histNotif = message.e2eMessage
                    ?.takeIf { it.hasProtocolMessage() && it.protocolMessage.hasHistorySyncNotification() }
                    ?.protocolMessage?.historySyncNotification
                if (histNotif != null) {
                    scope.launch { handleHistorySync(histNotif, message.id) }
                    return@launch
                }

                // App-state sync keys, shared by our primary as a peer protocolMessage.
                val keyShare = message.e2eMessage
                    ?.takeIf { it.hasProtocolMessage() && it.protocolMessage.hasAppStateSyncKeyShare() }
                    ?.protocolMessage?.appStateSyncKeyShare
                if (keyShare != null) {
                    scope.launch { handleAppStateKeyShare(keyShare) }
                    return@launch
                }

                // Process inbound sender-key distribution so future group skmsg decrypt.
                // UNVERIFIED: group sender-key wire mapping not runtime-tested.
                val skdm = message.e2eMessage?.takeIf { it.hasSenderKeyDistributionMessage() }
                    ?.senderKeyDistributionMessage
                if (skdm != null && crypto != null) {
                    try {
                        crypto.processSenderKeyDistribution(
                            message.from,
                            message.participant ?: message.from,
                            skdm.axolotlSenderKeyDistributionMessage.toByteArray(),
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to process SKDM", e)
                    }
                    // A SKDM-only message carries no user-visible content (types as "ignore"); once
                    // the key is stored there's nothing to display, so stop before emitting a blank
                    // bubble. Messages that bundle the SKDM with real content fall through to render.
                    if (message.messageType == "ignore") return@launch
                }

                // Skip status broadcasts (Go handleWAMessage status@broadcast check)
                if (message.from.startsWith("status@broadcast")) {
                    Log.d(TAG, "Skipping status broadcast from ${message.participant}")
                    return@launch
                }

                // Pending message dedup (Go handleWAMessage pendingMessages check)
                if (pendingMessageIDs.remove(message.id)) {
                    Log.d(TAG, "Ignoring pending message ${message.id}")
                    return@launch
                }

                // LID routing (Go rerouteWAMessage)
                val sender = resolveJID(message.participant ?: message.from)

                // Cache push name from notify attr (Go syncGhost / PushName handling)
                val notifyName = node.attrs["notify"]
                if (!notifyName.isNullOrEmpty()) {
                    nameCache[sender] = notifyName
                }

                // Handle revoke (message deletion) from Go handleWAMessage/revoke case
                if (message.isRevoke && message.revokeTargetId != null) {
                    _events.emit(GMEvent.MessageDeleted(
                        source = MessageSource.WHATSAPP,
                        conversationId = "wa:${message.from}",
                        messageId = message.revokeTargetId,
                        timestamp = message.timestamp * 1000,
                    ))
                    return@launch
                }

                // Handle edit from Go handleWAMessage/edit case
                if (message.isEdit && message.editTargetId != null) {
                    // Edit dedup (Go events.go ConvertEdit meta.Edits check)
                    if (!processedEditIDs.add(message.id)) {
                        Log.d(TAG, "Ignoring duplicate edit ${message.id}")
                        return@launch
                    }
                    _events.emit(GMEvent.MessageEdited(
                        source = MessageSource.WHATSAPP,
                        conversationId = "wa:${message.from}",
                        messageId = message.editTargetId,
                        newBody = message.body,
                        timestamp = message.timestamp * 1000,
                    ))
                    return@launch
                }

                // Handle disappearing timer change (Go handleWAMessage/ephemeral case)
                if (message.disappearingTimer != null) {
                    _events.emit(GMEvent.IncomingMessage(
                        source = MessageSource.WHATSAPP,
                        conversationId = "wa:${message.from}",
                        messageId = message.id,
                        body = message.body,
                        peerName = resolveName(sender),
                        peerPhone = null,
                        timestamp = message.timestamp * 1000,
                    ))
                    return@launch
                }

                // Handle poll creation — store option hashes (Go handleWAMessage/poll case)
                if (message.pollData != null && !message.pollData.isPollVote) {
                    storePollOptions(message.id, message.pollData.options)
                }

                // Prepend forwarded indicator (Go from-whatsapp.go addForwardedFlag)
                val displayBody = buildString {
                    if (message.isViewOnce) append("\u26A0\uFE0F View once: ")
                    if (message.isHD) append("[HD] ")
                    if (message.isForwarded) {
                        append("↷ Forwarded\n")
                    }
                    append(message.body)
                }.let { resolveMentionsInBody(it, message.mentionedJids) }

                // Store poll secrets from incoming polls for vote encryption/decryption.
                if (message.pollData != null && !message.pollData.isPollVote && message.e2eMessage != null) {
                    val secret = message.e2eMessage.messageContextInfo?.messageSecret?.toByteArray()
                    if (secret != null) storePollSecret(message.id, secret)
                }

                // Handle incoming poll votes (PollUpdateMessage): decrypt the selected option
                // hashes, map them back to names, and emit a tally update. Ref whatsmeow
                // DecryptPollVote.
                if (message.e2eMessage?.hasPollUpdateMessage() == true) {
                    val update = message.e2eMessage.pollUpdateMessage
                    val key = update.pollCreationMessageKey
                    val pollId = key.id
                    val voter = if (message.isFromMe) (authData?.wid ?: "") else sender
                    val creator = when {
                        key.fromMe -> voter
                        key.participant.isNotEmpty() -> key.participant
                        else -> key.remoteJid
                    }
                    val secret = loadPollSecret(pollId)
                    if (secret != null) {
                        val hashes = WhatsAppProtocol.decryptPollVote(update, pollId, creator, voter, secret)
                        if (hashes != null) {
                            val dao = db?.pollOptionDao()
                            val names = hashes.mapNotNull { h ->
                                val hex = h.joinToString("") { "%02x".format(it) }
                                dao?.getByHash(pollId, hex)?.optionName
                            }
                            _events.emit(GMEvent.PollVote(
                                source = MessageSource.WHATSAPP,
                                conversationId = "wa:${message.from}",
                                pollMessageId = pollId,
                                voterId = if (message.isFromMe) "self" else sender,
                                optionNames = names,
                            ))
                        }
                    }
                    return@launch
                }

                // Handle reactions separately (Go handleWAMessage reaction case)
                if (message.e2eMessage?.hasReactionMessage() == true) {
                    val reaction = message.e2eMessage.reactionMessage
                    val emoji = reaction.text?.replace("\uFE0F", "") ?: ""
                    val targetId = reaction.key?.id ?: ""
                    // A reaction the user made on another device echoes back as fromMe;
                    // tag its sender "self" so it isn't misattributed to the chat peer.
                    val reactorId = if (message.isFromMe) "self" else sender
                    if (emoji.isEmpty()) {
                        _events.emit(GMEvent.ReactionRemoved(
                            source = MessageSource.WHATSAPP,
                            conversationId = "wa:${message.from}",
                            messageId = targetId,
                            senderId = reactorId,
                        ))
                    } else {
                        _events.emit(GMEvent.ReactionReceived(
                            source = MessageSource.WHATSAPP,
                            conversationId = "wa:${message.from}",
                            messageId = targetId,
                            senderId = reactorId,
                            emoji = emoji,
                        ))
                    }
                    return@launch
                }

                // For group chats, make sure the conversation exists (named + flagged as a
                // group) before we store the message — a freshly joined group isn't in history
                // sync, so otherwise it would only ever be a nameless bare row. Deduped +
                // awaited so the metadata lands before the message's unread bump.
                if (message.from.contains("@g.us")) {
                    fetchAndEmitGroupInfo(message.from)
                }

                // Download + decrypt any inline media so it renders in-thread instead of as a
                // bare "[Image]"/"[Video]" placeholder. Null for non-media or on failure.
                val mediaAttachment = message.e2eMessage?.let {
                    buildIncomingMediaAttachment(it, message.id)
                }
                val mediaAttachments = listOfNotNull(mediaAttachment)

                // Messages the user sent from another linked device (fromMe) are synced
                // to us as outgoing, not incoming — emit a MessageUpdate so they render on
                // the sent side instead of as an incoming (previously blank) bubble.
                if (message.isFromMe) {
                    _events.emit(GMEvent.MessageUpdate(
                        source = MessageSource.WHATSAPP,
                        conversationId = "wa:${message.from}",
                        messageId = message.id,
                        body = displayBody,
                        outgoing = true,
                        timestamp = message.timestamp * 1000,
                        senderName = null,
                        attachments = mediaAttachments,
                    ))
                    return@launch
                }

                // In group chats, attribute the message to the actual participant so
                // the UI can show who sent each bubble. `sender` is the participant JID
                // (resolveJID(participant ?: from)); for 1:1 chats it's the peer, so we
                // leave sender fields null and fall back to the conversation peer.
                val isGroupChat = message.from.contains("@g.us")
                _events.emit(GMEvent.IncomingMessage(
                    source = MessageSource.WHATSAPP,
                    conversationId = "wa:${message.from}",
                    messageId = message.id,
                    body = displayBody,
                    peerName = resolveName(sender),
                    peerPhone = null,
                    timestamp = message.timestamp * 1000,
                    senderName = if (isGroupChat) resolveName(sender) else null,
                    senderId = if (isGroupChat) sender else null,
                    attachments = mediaAttachments,
                    pollQuestion = message.pollData?.takeIf { !it.isPollVote }?.question,
                    pollOptions = message.pollData?.takeIf { !it.isPollVote }?.options ?: emptyList(),
                ))

                // Send delivery receipt
                val receiptAttrs = mutableMapOf(
                    "id" to message.id,
                    "to" to message.from
                )
                node.attrs["participant"]?.let { receiptAttrs["participant"] = it }
                node.attrs["recipient"]?.let { receiptAttrs["recipient"] = it }
                val receipt = WhatsAppProtocol.Node(tag = "receipt", attrs = receiptAttrs)
                webSocket?.send(WhatsAppProtocol.encodeNode(receipt))

            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle incoming message", e)
            }
        }
    }

    /**
     * Handle identity change notification (security code change).
     * From Go handleWAIdentityChange.
     */
    private suspend fun handleIdentityChange(node: WhatsAppProtocol.Node, from: String) {
        val identityNode = node.getChildByTag("identity")
        if (identityNode != null) {
            val jid = node.attrs["participant"] ?: from
            val ts = node.attrs["t"]?.toLongOrNull() ?: (System.currentTimeMillis() / 1000)
            Log.i(TAG, "Identity/security code changed for $jid")
            _events.emit(GMEvent.IncomingMessage(
                source = MessageSource.WHATSAPP,
                conversationId = "wa:$from",
                messageId = "idchange-$from-$jid-$ts",
                body = "\uD83D\uDD12 Security code changed for ${resolveName(jid)}",
                peerName = resolveName(jid),
                peerPhone = null,
                timestamp = ts * 1000,
            ))
        }
    }

    /**
     * Handle picture update notification (avatar changes).
     * From Go handleWAPictureUpdate.
     */
    private suspend fun handlePictureUpdate(node: WhatsAppProtocol.Node, from: String) {
        val pictureNode = node.getChildByTag("set") ?: node.getChildByTag("delete")
        if (pictureNode != null) {
            val isRemoved = pictureNode.tag == "delete"
            Log.d(TAG, "Picture ${if (isRemoved) "removed" else "updated"} for $from")
        }
    }

    /**
     * Handle account sync notification (push name updates).
     * From Go PushNameSetting / PushName events.
     */
    private suspend fun handleAccountSync(node: WhatsAppProtocol.Node) {
        node.content.filterIsInstance<WhatsAppProtocol.Node>().forEach { child ->
            when (child.tag) {
                "push" -> {
                    val pushName = child.attrs["name"]
                    val jid = child.attrs["jid"] ?: node.attrs["from"]
                    if (pushName != null && jid != null) {
                        nameCache[jid] = pushName
                        Log.d(TAG, "Push name updated: $jid -> $pushName")
                    }
                }
                "contact" -> {
                    val contactName = child.attrs["name"]
                    val jid = child.attrs["jid"] ?: node.attrs["from"]
                    if (contactName != null && jid != null) {
                        nameCache[jid] = contactName
                    }
                }
            }
        }
    }

    /**
     * Handle receipt with per-sender batching for group chats.
     * From Go handleWAReceipt — groups messages by sender.
     */
    private suspend fun handleReceipt(node: WhatsAppProtocol.Node) {
        val receiptType = node.attrs["type"]
        // The peer couldn't decrypt a message we sent and wants it re-encrypted. This is the
        // outbound counterpart of sendRetryReceipt and is required for delivery to actually
        // happen when a session is desynced.
        if (receiptType == "retry") {
            handleRetryReceipt(node)
            return
        }
        val isRead = receiptType == "read" || receiptType == "read-self"
        val isDelivered = receiptType == null || receiptType.isEmpty()
        if (!isRead && !isDelivered) return

        // Log delivery receipts (Go handleWAReceipt ReceiptTypeDelivered)
        if (isDelivered) {
            Log.d(TAG, "Delivery receipt from ${node.attrs["from"]} for ${node.attrs["id"]}")
            return
        }

        val from = node.attrs["from"] ?: return
        val participant = node.attrs["participant"]

        // Reroute LID sender
        val sender = resolveJID(participant ?: from)

        val messageId = node.attrs["id"] ?: return
        val timestamp = (node.attrs["t"]?.toLongOrNull() ?: System.currentTimeMillis() / 1000) * 1000

        _events.emit(GMEvent.ReadReceipt(
            source = MessageSource.WHATSAPP,
            conversationId = "wa:$from",
            messageId = messageId,
            timestamp = timestamp,
        ))

        // Handle additional message IDs in list node
        val listNode = node.getChildByTag("list")
        listNode?.content?.filterIsInstance<WhatsAppProtocol.Node>()?.forEach { item ->
            val itemId = item.attrs["id"] ?: return@forEach
            _events.emit(GMEvent.ReadReceipt(
                source = MessageSource.WHATSAPP,
                conversationId = "wa:$from",
                messageId = itemId,
                timestamp = timestamp,
            ))
        }
    }

    /**
     * Handle chat presence with media type differentiation.
     * From Go handleWAChatPresence — differentiates text/audio/media typing.
     */
    private suspend fun handleChatPresence(node: WhatsAppProtocol.Node) {
        val from = node.attrs["from"] ?: return
        val participant = node.attrs["participant"]
        val sender = resolveJID(participant ?: from)

        val composingNode = node.getChildByTag("composing")
        val pausedNode = node.getChildByTag("paused")

        val isTyping = composingNode != null
        val mediaAttr = composingNode?.attrs?.get("media")

        _events.emit(GMEvent.TypingIndicator(
            source = MessageSource.WHATSAPP,
            conversationId = "wa:$from",
            senderId = sender,
            isTyping = isTyping,
        ))
    }

    /**
     * Handle notification events (group changes, mute, pin, archive).
     * From Go handleWAGroupInfoChange, handleWAMute, handleWAArchive, handleWAPin.
     */
    private suspend fun handleNotification(node: WhatsAppProtocol.Node) {
        val notifType = node.attrs["type"] ?: return
        val from = node.attrs["from"] ?: return

        when (notifType) {
            "w:gp2" -> handleGroupNotification(node, from)
            "server_sync" -> handleServerSync(node, from)
            "call" -> handleCallNotification(node, from)
            "encrypt" -> handleIdentityChange(node, from)
            "picture" -> handlePictureUpdate(node, from)
            "account_sync" -> handleAccountSync(node)
            "devices" -> {} // Device list update — Go ignores silently
            "mediaretry" -> {} // Media retry — Go handles but requires bridge infra
        }
    }

    /**
     * Handle group info change notifications.
     * From Go handleWAGroupInfoChange.
     */
    private suspend fun handleGroupNotification(node: WhatsAppProtocol.Node, groupJid: String) {
        val timestamp = (node.attrs["t"]?.toLongOrNull() ?: System.currentTimeMillis() / 1000) * 1000
        val actor = resolveName(node.attrs["participant"] ?: groupJid)
        val msgId = node.attrs["id"] ?: ""

        // Any group notification (being added, subject/participant changes, …) implies the
        // group should exist locally. Fetch + publish its metadata so the conversation appears
        // named and flagged as a group even when we were just added and have no history for it.
        fetchAndEmitGroupInfo(groupJid)

        node.content?.filterIsInstance<WhatsAppProtocol.Node>()?.forEach { child ->
            val body = when (child.tag) {
                "subject" -> {
                    val newName = child.attrs["subject"] ?: child.data?.let { String(it, Charsets.UTF_8) } ?: return@forEach
                    "[Group name changed to: $newName]"
                }
                "description" -> {
                    val newDesc = child.data?.let { String(it, Charsets.UTF_8) } ?: ""
                    "[Group description changed: $newDesc]"
                }
                "add", "remove", "promote", "demote" -> {
                    val participants = child.content?.filterIsInstance<WhatsAppProtocol.Node>()
                        ?.mapNotNull { it.attrs["jid"] } ?: emptyList()
                    "[Group: ${participants.joinToString()} ${child.tag}ed]"
                }
                // Go wrapGroupInfoChange: ephemeral setting
                "ephemeral" -> {
                    val expiration = child.attrs["expiration"]?.toLongOrNull() ?: 0
                    if (expiration > 0) {
                        val duration = when {
                            expiration >= 86400 * 7 -> "${expiration / (86400 * 7)} week(s)"
                            expiration >= 86400 -> "${expiration / 86400} day(s)"
                            expiration >= 3600 -> "${expiration / 3600} hour(s)"
                            else -> "$expiration seconds"
                        }
                        "[Disappearing messages set to $duration]"
                    } else {
                        "[Disappearing messages turned off]"
                    }
                }
                // Go wrapGroupInfoChange: announce mode
                "announce" -> {
                    val isAnnounce = child.attrs["announce"] == "true" || child.attrs["value"] == "on"
                    if (isAnnounce) "[Only admins can send messages now]"
                    else "[All participants can send messages now]"
                }
                // Go wrapGroupInfoChange: locked (restrict edit to admins)
                "locked" -> {
                    val isLocked = child.attrs["locked"] == "true" || child.attrs["value"] == "on"
                    if (isLocked) "[Only admins can edit group info now]"
                    else "[All participants can edit group info now]"
                }
                // Go wrapGroupInfoChange: link/unlink community
                "link" -> {
                    val linkedGroup = child.attrs["link_type"]
                    "[Group linked: $linkedGroup]"
                }
                "unlink" -> {
                    val unlinkedGroup = child.attrs["unlink_type"]
                    "[Group unlinked: $unlinkedGroup]"
                }
                else -> null
            }

            if (body != null) {
                _events.emit(GMEvent.IncomingMessage(
                    source = MessageSource.WHATSAPP,
                    conversationId = "wa:$groupJid",
                    messageId = msgId,
                    body = body,
                    peerName = actor,
                    peerPhone = null,
                    timestamp = timestamp,
                ))
            }
        }
    }

    /**
     * Handle server sync notifications (mute, pin, archive).
     * From Go handleWAMute, handleWAArchive, handleWAPin.
     */
    /**
     * Respond to a server <dirty> info block with <clean> (MarkNotDirty) so the primary phone
     * stops showing "syncing / keep WhatsApp open". Ref whatsmeow appstate.go MarkNotDirty.
     * <iq to=s.whatsapp.net type=set xmlns=urn:xmpp:whatsapp:dirty><clean type=.. timestamp=../></iq>
     */
    private suspend fun handleDirty(dirty: WhatsAppProtocol.Node) {
        val type = dirty.attrs["type"] ?: return
        val ts = dirty.attrs["timestamp"]
        // account_sync dirty is cleared automatically by the server once we've synced; whatsmeow
        // only explicitly cleans non-account_sync types, but cleaning is harmless and idempotent.
        val cleanAttrs = mutableMapOf("type" to type)
        if (ts != null) cleanAttrs["timestamp"] = ts
        val iq = WhatsAppProtocol.Node(
            tag = "iq",
            attrs = mapOf(
                "id" to generateMessageId(), "type" to "set",
                "xmlns" to "urn:xmpp:whatsapp:dirty", "to" to "s.whatsapp.net",
            ),
            content = listOf(WhatsAppProtocol.Node(tag = "clean", attrs = cleanAttrs)),
        )
        webSocket?.send(WhatsAppProtocol.encodeNode(iq))
        WhatsAppDiag.log(TAG, "cleaned dirty state: $type")
    }

    private suspend fun handleServerSync(node: WhatsAppProtocol.Node, from: String) {
        node.getChildren().filter { it.tag == "collection" }.forEach { coll ->
            val name = coll.attrs["name"] ?: return@forEach
            if (name in APP_STATE_COLLECTIONS) {
                scope.launch { fetchAppStateCollection(name, fullSync = appStateVersion(name) == 0L) }
            }
        }
    }

    /**
     * Handle incoming call notification.
     * From Go handleWACallStart.
     */
    private suspend fun handleCallNotification(node: WhatsAppProtocol.Node, from: String) {
        val offer = node.getChildByTag("offer")
        if (offer != null) {
            // Go handleWACallStart: ignore calls older than 15 minutes
            val callTimestamp = node.attrs["t"]?.toLongOrNull() ?: (System.currentTimeMillis() / 1000)
            val ageSeconds = (System.currentTimeMillis() / 1000) - callTimestamp
            if (ageSeconds > 15 * 60) {
                Log.d(TAG, "Ignoring old call notification (${ageSeconds}s old)")
                return
            }

            val caller = node.attrs["participant"] ?: from
            val callId = node.attrs["id"] ?: ""
            val callType = offer.attrs["call-type"] ?: "voice"
            val callLabel = when {
                callType.contains("video") -> "video call"
                callType.contains("group") -> "group call"
                else -> "voice call"
            }
            _events.emit(GMEvent.IncomingMessage(
                source = MessageSource.WHATSAPP,
                conversationId = "wa:$from",
                messageId = "call-$callId",
                body = "\u260E Incoming $callLabel",
                peerName = resolveName(caller),
                peerPhone = null,
                timestamp = callTimestamp * 1000,
            ))
        }
    }

    /**
     * Handle app state patch mutations (mute, pin, archive, unread).
     * From Go handleWAMute / handleWAArchive / handleWAPin / handleWAMarkChatAsRead.
     */
    // App-state collections to sync (ref whatsmeow appstate AllPatchNames).
    private val APP_STATE_COLLECTIONS = listOf(
        "critical_block", "critical_unblock_low", "regular_high", "regular", "regular_low",
    )
    private val appStateCollectionsFetching = java.util.Collections.synchronizedSet(HashSet<String>())
    private val appStatePrefs by lazy {
        appContext.getSharedPreferences("wa_appstate", android.content.Context.MODE_PRIVATE)
    }

    private fun appStateKeyId64(keyId: ByteArray) = Base64.encodeToString(keyId, Base64.NO_WRAP)
    private fun storeAppStateKey(keyId: ByteArray, keyData: ByteArray) {
        appStatePrefs.edit().putString("key_${appStateKeyId64(keyId)}", Base64.encodeToString(keyData, Base64.NO_WRAP)).apply()
    }
    private fun getAppStateKey(keyId: ByteArray): ByteArray? {
        val s = appStatePrefs.getString("key_${appStateKeyId64(keyId)}", null) ?: return null
        return try { Base64.decode(s, Base64.NO_WRAP) } catch (e: Exception) { null }
    }
    private fun appStateVersion(name: String): Long = appStatePrefs.getLong("ver_$name", 0L)
    private fun setAppStateVersion(name: String, v: Long) { appStatePrefs.edit().putLong("ver_$name", v).apply() }

    /** Store app-state sync keys shared by our primary, then fetch all collections. */
    private suspend fun handleAppStateKeyShare(share: WhatsAppE2EProto.AppStateSyncKeyShare) {
        var stored = 0
        for (k in share.keysList) {
            val id = k.keyId.keyId.toByteArray()
            val data = k.keyData.keyData.toByteArray()
            if (id.isNotEmpty() && data.isNotEmpty()) { storeAppStateKey(id, data); stored++ }
        }
        WhatsAppDiag.log(TAG, "app-state: stored $stored sync key(s); fetching collections")
        for (name in APP_STATE_COLLECTIONS) {
            fetchAppStateCollection(name, fullSync = appStateVersion(name) == 0L)
        }
    }

    /**
     * Fetch + decode + apply one app-state collection (snapshot then incremental patches).
     * Ref whatsmeow appstate.go fetchAppState. MAC/LTHash verification is skipped.
     */
    private suspend fun fetchAppStateCollection(name: String, fullSync: Boolean) {
        if (!appStateCollectionsFetching.add(name)) return
        try {
            var version = if (fullSync) 0L else appStateVersion(name)
            var wantSnapshot = fullSync || version == 0L
            var more = true
            var guard = 0
            while (more && guard++ < 12) {
                val collAttrs = mutableMapOf("name" to name, "return_snapshot" to wantSnapshot.toString())
                if (!wantSnapshot) collAttrs["version"] = version.toString()
                val iq = WhatsAppProtocol.Node(
                    tag = "iq",
                    attrs = mapOf(
                        "id" to generateMessageId(), "type" to "set",
                        "xmlns" to "w:sync:app:state", "to" to "s.whatsapp.net",
                    ),
                    content = listOf(
                        WhatsAppProtocol.Node(
                            tag = "sync",
                            content = listOf(WhatsAppProtocol.Node(tag = "collection", attrs = collAttrs)),
                        )
                    ),
                )
                val resp = sendIqAndWait(iq) ?: break
                val coll = resp.getChildByTag("sync")?.getChildByTag("collection") ?: break

                val snapNode = coll.getChildByTag("snapshot")
                var recCount = 0
                snapNode?.data?.let { snapData ->
                    val ext = WhatsAppAppStateProto.ExternalBlobReference.parseFrom(snapData)
                    val blob = downloadAppStateBlob(ext)
                    if (blob == null) {
                        WhatsAppDiag.log(TAG, "app-state $name: snapshot blob download FAILED (path=${ext.directPath.take(40)})")
                    } else {
                        val snap = WhatsAppAppStateProto.SyncdSnapshot.parseFrom(blob)
                        recCount = snap.recordsCount
                        for (rec in snap.recordsList) applyAppStateRecord(name, rec, isSet = true)
                        if (snap.hasVersion()) version = snap.version.version
                    }
                }
                val patchNodes = coll.getChildByTag("patches")?.getChildren()?.filter { it.tag == "patch" } ?: emptyList()
                patchNodes.forEach { p ->
                    p.data?.let { pd ->
                        val patch = WhatsAppAppStateProto.SyncdPatch.parseFrom(pd)
                        val muts = if (patch.hasExternalMutations()) {
                            downloadAppStateBlob(patch.externalMutations)?.let {
                                WhatsAppAppStateProto.SyncdMutations.parseFrom(it).mutationsList
                            } ?: emptyList()
                        } else patch.mutationsList
                        for (m in muts) applyAppStateRecord(
                            name, m.record,
                            isSet = m.operation == WhatsAppAppStateProto.SyncdMutation.SyncdOperation.SET,
                        )
                        if (patch.hasVersion()) version = patch.version.version
                    }
                }
                WhatsAppDiag.log(TAG, "app-state $name: snapshot=${snapNode != null} records=$recCount patches=${patchNodes.size} more=${coll.attrs["has_more_patches"]} -> v$version")
                more = coll.attrs["has_more_patches"] == "true"
                wantSnapshot = false
            }
            setAppStateVersion(name, version)
            WhatsAppDiag.log(TAG, "app-state: $name synced to v$version")
        } catch (e: Exception) {
            WhatsAppDiag.log(TAG, "app-state $name failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            appStateCollectionsFetching.remove(name)
        }
    }

    /** Download + decrypt an app-state external blob (snapshot/mutations) via the media CDN. */
    private suspend fun downloadAppStateBlob(ext: WhatsAppAppStateProto.ExternalBlobReference): ByteArray? {
        val directPath = ext.directPath
        if (directPath.isEmpty()) return null
        val host = mediaConn()?.first ?: return null
        val hash = Base64.encodeToString(ext.fileEncSha256.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        val url = "https://$host$directPath&hash=$hash&mms-type=md-app-state&__wa-mms="
        return downloadMedia(url, ext.mediaKey.toByteArray(), WhatsAppProtocol.MEDIA_KEY_APP_STATE)
    }

    /** Decrypt one app-state record and apply it (contact names, mute/pin/archive). */
    private suspend fun applyAppStateRecord(
        collection: String,
        record: WhatsAppAppStateProto.SyncdRecord,
        isSet: Boolean,
    ) {
        if (!isSet) return
        val keyId = record.keyId.id.toByteArray()
        val keyData = getAppStateKey(keyId) ?: run {
            requestAppStateKey(keyId); return
        }
        val expanded = WhatsAppProtocol.expandAppStateKeys(keyData)
        val plain = WhatsAppProtocol.decryptAppStateValue(record.value.blob.toByteArray(), expanded[1]) ?: return
        val sad = try { WhatsAppAppStateProto.SyncActionData.parseFrom(plain) } catch (e: Exception) { return }
        val index = try { org.json.JSONArray(String(sad.index.toByteArray(), Charsets.UTF_8)) } catch (e: Exception) { return }
        if (index.length() == 0) return
        val action = index.optString(0)
        val jid = if (index.length() > 1) index.optString(1) else ""
        val value = sad.value
        when (action) {
            "contact" -> {
                val name = value.contactAction.fullName.ifEmpty { value.contactAction.firstName }
                if (name.isNotEmpty() && jid.isNotEmpty()) {
                    // Cache name for live messages / future conversations. We do NOT emit a
                    // ConversationUpdate here because the consumer upserts (would create empty rows
                    // for every contact). History rows already use device-contact lookup for names.
                    nameCache[resolveJID(jid)] = name
                    nameCache[jid] = name
                }
            }
            "mute" -> if (jid.isNotEmpty()) db?.conversationDao()?.updateMuteEndTime(jid, value.muteAction.muteEndTimestamp)
            "pin_v1" -> if (jid.isNotEmpty()) db?.conversationDao()?.updatePinned(jid, value.pinAction.pinned)
            "archive" -> if (jid.isNotEmpty()) db?.conversationDao()?.updateArchived(jid, value.archiveChatAction.archived)
            "markChatAsRead" -> if (jid.isNotEmpty()) db?.conversationDao()?.updateMarkedAsUnread(jid, !value.markChatAsReadAction.read)
        }
    }

    /** Request a missing app-state sync key from our primary (deduped). */
    private suspend fun requestAppStateKey(keyId: ByteArray) {
        val id64 = appStateKeyId64(keyId)
        if (!appStateKeyRequested.add(id64)) return
        val auth = authData ?: return
        try {
            val req = WhatsAppE2EProto.AppStateSyncKeyRequest.newBuilder()
                .addKeyIds(
                    WhatsAppE2EProto.AppStateSyncKeyId.newBuilder()
                        .setKeyId(com.google.protobuf.ByteString.copyFrom(keyId))
                )
            val proto = WhatsAppE2EProto.ProtocolMessage.newBuilder()
                .setType(WhatsAppE2EProto.ProtocolMessage.Type.APP_STATE_SYNC_KEY_REQUEST)
                .setAppStateSyncKeyRequest(req)
            val msg = WhatsAppE2EProto.Message.newBuilder().setProtocolMessage(proto).build()
            val ownUser = auth.wid.substringBefore("@").substringBefore(":").substringBefore(".")
            val node = buildEncryptedMessageNode("$ownUser@s.whatsapp.net", generateMessageId(), msg, "text") ?: return
            webSocket?.send(WhatsAppProtocol.encodeNode(node))
            WhatsAppDiag.log(TAG, "app-state: requested missing key $id64")
        } catch (e: Exception) {
            WhatsAppDiag.log(TAG, "app-state key request failed: ${e.message}")
        }
    }

    private val appStateKeyRequested = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Send a retry receipt asking the sender to re-encrypt an undecryptable message.
     * Ref whatsmeow retry.go sendRetryReceipt. Retries are capped at 5; the identity/prekey <keys>
     * node is included from the 2nd retry onward.
     */
    private suspend fun sendRetryReceipt(node: WhatsAppProtocol.Node) {
        val auth = authData ?: return
        val crypto = ensureE2E(auth) ?: return
        val ws = webSocket ?: return
        val msgId = node.attrs["id"] ?: return
        val count = undecryptableTracker.merge("retry:$msgId", 1) { a, b -> a + b } ?: 1
        if (count > 5) {
            Log.w(TAG, "Not sending more retry receipts for $msgId")
            return
        }
        val keysNode = if (count > 1) {
            try { crypto.buildRetryReceiptKeysNode(accountDeviceIdentity()) } catch (e: Exception) {
                Log.w(TAG, "Failed to build retry keys node", e); null
            }
        } else null
        val receipt = WhatsAppProtocol.buildRetryReceipt(node, auth.registrationId, count, keysNode)
        ws.send(WhatsAppProtocol.encodeNode(receipt))
        Log.d(TAG, "Sent retry receipt #$count for $msgId")
    }

    /**
     * Handle an inbound <receipt type="retry">: the peer failed to decrypt a message we sent and
     * is asking us to re-encrypt and resend it. Rebuild the session from the fresh keys included
     * in the receipt (present from the 2nd retry), then re-encrypt the cached plaintext for just
     * the requesting device and resend with the same message id. Ref whatsmeow retry.go
     * handleRetryReceipt. 1:1 only — group skmsg resends are not cached.
     */
    private suspend fun handleRetryReceipt(node: WhatsAppProtocol.Node) {
        val auth = authData ?: return
        val crypto = ensureE2E(auth) ?: return
        val ws = webSocket ?: return
        val msgId = node.attrs["id"] ?: return
        val from = node.attrs["from"] ?: return
        // The specific device that couldn't decrypt: participant if present, else the chat peer.
        val deviceJid = node.attrs["participant"] ?: from
        if (deviceJid.contains("@g.us")) {
            WhatsAppDiag.log(TAG, "retry: ignoring group retry for $msgId (not cached)")
            return
        }
        val cached = recentSentDMs[msgId] ?: run {
            WhatsAppDiag.log(TAG, "retry: no cached message for $msgId; cannot resend")
            return
        }
        val resendKey = "$msgId|$deviceJid"
        val resendCount = retryResendCounts.merge(resendKey, 1) { a, b -> a + b } ?: 1
        if (resendCount > 5) {
            WhatsAppDiag.log(TAG, "retry: giving up on $msgId for $deviceJid after $resendCount attempts")
            return
        }

        // Rebuild the session from the keys the peer attached (identity + prekeys), if any.
        val deviceNum = deviceJid.substringBefore("@").substringAfter(":", "0").toIntOrNull() ?: 0
        if (node.getChildByTag("keys") != null) {
            try {
                // parsePreKeyBundleNode reads <registration> + <keys> from the node it is given.
                val bundle = crypto.parsePreKeyBundleNode(deviceNum, node)
                if (bundle != null) {
                    crypto.deleteSession(deviceJid)
                    crypto.processPreKeyBundle(deviceJid, bundle)
                    WhatsAppDiag.log(TAG, "retry: rebuilt session for $deviceJid from receipt keys")
                }
            } catch (e: Exception) {
                WhatsAppDiag.log(TAG, "retry: failed to process receipt keys for $deviceJid: ${e.message}")
            }
        }
        if (!ensureSession(deviceJid)) {
            WhatsAppDiag.log(TAG, "retry: no session for $deviceJid; cannot resend $msgId")
            return
        }

        val ownUser = auth.wid.substringBefore("@").substringBefore(":").substringBefore(".")
        val devUser = deviceJid.substringBefore("@").substringBefore(":").substringBefore(".")
        val plaintext = if (devUser == ownUser && cached.dsmPlaintextPadded != null)
            cached.dsmPlaintextPadded else cached.msgPlaintextPadded
        val enc = try {
            crypto.encryptDM(deviceJid, plaintext)
        } catch (e: Exception) {
            WhatsAppDiag.log(TAG, "retry: re-encrypt failed for $deviceJid: ${e.message}")
            return
        }
        val includeIdentity = enc.type == "pkmsg"
        val resend = WhatsAppProtocol.buildFanOutMessageNode(
            to = cached.to,
            id = msgId,
            type = cached.type,
            participantEncs = listOf(WhatsAppProtocol.ParticipantEnc(deviceJid, enc.type, enc.data)),
            includeDeviceIdentity = includeIdentity,
            deviceIdentity = if (includeIdentity) accountDeviceIdentity() else null,
        )
        val sent = ws.send(WhatsAppProtocol.encodeNode(resend))
        WhatsAppDiag.log(TAG, "retry: resent $msgId to $deviceJid (attempt $resendCount type=${enc.type}) sent=$sent")
    }

    /**
     * Track undecryptable messages.
     * From Go handleWAUndecryptableMessage / trackUndecryptable.
     */
    private fun trackUndecryptable(node: WhatsAppProtocol.Node) {
        val from = node.attrs["from"] ?: return
        val count = undecryptableTracker.merge(from, 1) { old, new -> old + new } ?: 1
        Log.w(TAG, "Undecryptable message from $from (count: $count)")
    }

    /**
     * Resolve JID: convert LID JIDs to phone number JIDs.
     * Handles DM sender LID, own message LID, broadcast, and bot cases.
     * From Go resolveJID / rerouteWAMessage — 5 distinct rerouting cases.
     */
    private fun resolveJID(jid: String): String {
        // Case 1: Not a LID — no rerouting needed
        if (!jid.contains("@lid")) return jid

        // Case 2: Direct LID→phone mapping from cache
        lidToPhoneMap[jid]?.let { return it }

        // Case 3: Own LID matches
        val ownLid = authData?.lid
        if (ownLid != null && ownLid.isNotEmpty() && jid == ownLid) {
            return authData?.wid ?: jid
        }

        // Case 4: Bot server JIDs (Go rerouteWAMessage bot server case)
        if (jid.contains("@bot")) {
            return jid // Bot JIDs are valid as-is
        }

        // Case 5: Extract user part and check LID map without server suffix
        val userPart = jid.substringBefore("@")
        lidToPhoneMap.entries.find { it.key.startsWith("$userPart@") }?.let { return it.value }

        return jid
    }

    /**
     * Store poll option hashes for later vote resolution.
     * From Go wadb.PollOption.
     */
    private suspend fun storePollOptions(messageId: String, options: List<String>) {
        val dao = db?.pollOptionDao() ?: return
        val pollOptions = options.map { option ->
            val hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(option.toByteArray(Charsets.UTF_8))
            WhatsAppPollOption(
                msgId = messageId,
                optionHash = hash.joinToString("") { "%02x".format(it) },
                optionName = option,
            )
        }
        dao.upsertAll(pollOptions)
    }

    private fun kickoffBackfill() {
        // History is PUSHED by the phone after linking as encrypted <message> stanzas carrying a
        // protocolMessage.historySyncNotification (gated by DeviceProps.HistorySyncConfig sent at
        // pairing). There is no w:web "initial" request in the multidevice protocol, so we just
        // wait for those messages here. Ref whatsmeow appstate/history sync.
        WhatsAppDiag.log(TAG, "awaiting pushed history sync messages from phone")
    }

    /**
     * Download (or read inline), decompress and parse a HistorySync blob, then emit the contained
     * messages as backfill. Ref whatsmeow message.go DownloadHistorySync + download.go.
     */
    private suspend fun handleHistorySync(
        notif: WhatsAppE2EProto.HistorySyncNotification,
        msgId: String,
    ) {
        try {
            val raw: ByteArray = if (notif.hasInitialHistBootstrapInlinePayload() &&
                !notif.initialHistBootstrapInlinePayload.isEmpty
            ) {
                // Initial bootstrap chunk is inlined in the notification (DeviceProps requested it).
                notif.initialHistBootstrapInlinePayload.toByteArray()
            } else {
                val host = mediaConn()?.first ?: run {
                    WhatsAppDiag.log(TAG, "history sync: no media host"); sendHistorySyncReceipt(msgId); return
                }
                val directPath = notif.directPath
                if (directPath.isEmpty()) { WhatsAppDiag.log(TAG, "history sync: no directPath"); sendHistorySyncReceipt(msgId); return }
                val hash = Base64.encodeToString(
                    notif.fileEncSha256.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP
                )
                val url = "https://$host$directPath&hash=$hash&mms-type=md-msg-hist&__wa-mms="
                downloadMedia(url, notif.mediaKey.toByteArray(), WhatsAppProtocol.MEDIA_KEY_HISTORY)
                    ?: run { WhatsAppDiag.log(TAG, "history sync: download failed"); sendHistorySyncReceipt(msgId); return }
            }

            val inflated = inflateZlib(raw)
            val hs = WhatsAppE2EProto.HistorySync.parseFrom(inflated)
            // Populate LID->phone mappings so conversations addressed by LID resolve to a phone JID.
            for (m in hs.phoneNumberToLidMappingsList) {
                if (m.lidJid.isNotEmpty() && m.pnJid.isNotEmpty()) lidToPhoneMap[m.lidJid] = m.pnJid
            }
            WhatsAppDiag.log(
                TAG,
                "history sync: type=${hs.syncType} chunk=${hs.chunkOrder} conversations=${hs.conversationsCount} lidMaps=${hs.phoneNumberToLidMappingsCount} (blob=${raw.size}B inflated=${inflated.size}B)",
            )

            var emitted = 0
            for (conv in hs.conversationsList) {
                val chatJid = conv.newJid.ifEmpty { conv.id }
                WhatsAppDiag.log(TAG, "  conv '${conv.name}' ${chatJid} msgs=${conv.messagesCount}")
                if (chatJid.isEmpty() || chatJid.startsWith("status@broadcast")) continue
                emitted += emitHistoryConversation(conv, chatJid, hs.syncType == 0)
            }
            WhatsAppDiag.log(TAG, "history sync: emitted $emitted message(s)")
        } catch (e: Exception) {
            WhatsAppDiag.log(TAG, "history sync failed: ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "history sync failed", e)
        }
        // Acknowledge the chunk so the phone advances to the next one and finishes "syncing".
        sendHistorySyncReceipt(msgId)
    }

    /**
     * Send the history-sync receipt so the phone stops waiting ("syncing, keep WhatsApp open") and
     * sends the next chunk. Ref whatsmeow SendProtocolMessageReceipt(id, ReceiptTypeHistorySync).
     * <receipt id="{notifMsgId}" type="hist_sync" to="{ownUserJID}"/>
     */
    private fun sendHistorySyncReceipt(msgId: String) {
        if (msgId.isEmpty()) return
        val ownUser = (authData?.wid ?: return).substringBefore("@").substringBefore(":").substringBefore(".")
        if (ownUser.isEmpty()) return
        val receipt = WhatsAppProtocol.Node(
            tag = "receipt",
            attrs = mapOf("id" to msgId, "type" to "hist_sync", "to" to "$ownUser@s.whatsapp.net"),
        )
        webSocket?.send(WhatsAppProtocol.encodeNode(receipt))
        WhatsAppDiag.log(TAG, "history sync: sent hist_sync receipt for $msgId")
    }

    /** Emit one backfilled history message as an IncomingMessage. Returns true if emitted. */
    /**
     * Backfill one conversation: emit a MessageUpdate (outgoing-aware) for each message with a
     * body, then a ConversationUpdate so the chat row appears with its last preview. Returns the
     * number of messages emitted. MessageUpdate is the backfill path (no notifications); IncomingMessage
     * is reserved for live messages.
     */
    private suspend fun emitHistoryConversation(
        conv: WhatsAppE2EProto.HsConversation,
        rawChatJid: String,
        requestMore: Boolean,
    ): Int {
        // Resolve LID-addressed chats to a phone JID so they match live conversations and have a
        // displayable number/name (instead of "unknown").
        val chatJid = resolveJID(rawChatJid)
        val convId = "wa:$chatJid"
        // E.164 (with +) so device-contact lookup (ContactsContract.PhoneLookup) matches.
        val phone = if (chatJid.endsWith("@s.whatsapp.net"))
            "+" + chatJid.substringBefore("@").substringBefore(":").substringBefore(".") else null
        val contactName = resolveDeviceContactName(phone)
        // Collect displayable messages first so we can register the conversation row BEFORE the
        // messages (the message table has a FK to the conversation).
        data class HMsg(val id: String, val body: String, val outgoing: Boolean, val ts: Long, val sender: String?)
        val msgs = ArrayList<HMsg>()
        var lastBody = ""
        var lastTs = conv.conversationTimestamp * 1000
        var peerPush = ""
        for (hsMsg in conv.messagesList) {
            if (!hsMsg.hasMessage()) continue
            val wmi = hsMsg.message
            if (!wmi.hasMessage()) continue
            val body = WhatsAppProtocol.extractMessageBody(wmi.message)
            if (body.isEmpty()) continue
            val key = wmi.key
            val tsMs = wmi.messageTimestamp * 1000
            if (!key.fromMe && wmi.pushName.isNotEmpty()) peerPush = wmi.pushName
            val senderName = if (key.fromMe) null
            else wmi.pushName.ifEmpty { conv.name.ifEmpty { contactName ?: phone } }
            msgs.add(HMsg(key.id, body, key.fromMe, tsMs, senderName))
            if (tsMs >= lastTs) { lastTs = tsMs; lastBody = body }
        }
        if (msgs.isEmpty()) return 0

        // Register the conversation row first.
        _events.emit(
            GMEvent.ConversationUpdate(
                source = MessageSource.WHATSAPP,
                conversationId = convId,
                peerName = conv.name.ifEmpty { contactName ?: peerPush.ifEmpty { phone } },
                peerPhone = phone,
                avatarUrl = null,
                lastPreview = lastBody,
                lastTimestamp = lastTs,
                unreadCount = conv.unreadCount,
            )
        )
        // Then backfill its messages.
        for (m in msgs) {
            _events.emit(
                GMEvent.MessageUpdate(
                    source = MessageSource.WHATSAPP,
                    conversationId = convId,
                    messageId = m.id,
                    body = m.body,
                    outgoing = m.outgoing,
                    timestamp = m.ts,
                    senderName = m.sender,
                )
            )
        }
        // Pull older messages on demand (only from the initial bootstrap, to avoid request loops).
        // Fire-and-forget: the request fans out via usync (a slow IQ) and must NOT block the
        // conversation backfill loop, or only the first chat would appear promptly.
        if (requestMore) {
            val oldest = msgs.minByOrNull { it.ts }
            if (oldest != null) {
                scope.launch {
                    sendHistoryOnDemandRequest(rawChatJid, oldest.id, oldest.outgoing, oldest.ts / 1000)
                }
            }
        }
        return msgs.size
    }

    private val onDemandRequested = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Look up a device contact display name for an E.164 number (null if none / no permission). */
    private fun resolveDeviceContactName(phoneE164: String?): String? {
        if (phoneE164.isNullOrEmpty()) return null
        return try {
            val uri = android.net.Uri.withAppendedPath(
                android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(phoneE164),
            )
            appContext.contentResolver.query(
                uri,
                arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Ask our own account to stream older history for a chat. Ref whatsmeow BuildHistorySyncRequest
     * — a HISTORY_SYNC_ON_DEMAND peer-data-operation message sent E2E to self. Deduped per chat so
     * the ON_DEMAND responses don't trigger further requests. Best-effort.
     */
    private suspend fun sendHistoryOnDemandRequest(
        chatJid: String,
        oldestMsgId: String,
        oldestFromMe: Boolean,
        oldestTsSec: Long,
    ) {
        if (oldestMsgId.isEmpty() || chatJid.isEmpty()) return
        if (!onDemandRequested.add(chatJid)) return
        val auth = authData ?: return
        try {
            val ownUser = auth.wid.substringBefore("@").substringBefore(":").substringBefore(".")
            val ownJid = "$ownUser@s.whatsapp.net"
            val msg = WhatsAppProtocol.buildHistoryOnDemandRequest(
                chatJid, oldestMsgId, oldestFromMe, oldestTsSec, 50,
            )
            val id = WhatsAppProtocol.generateMessageId(auth.wid)
            val node = buildEncryptedMessageNode(ownJid, id, msg, "text") ?: return
            webSocket?.send(WhatsAppProtocol.encodeNode(node))
            WhatsAppDiag.log(TAG, "on-demand history requested for $chatJid (oldest=$oldestMsgId)")
        } catch (e: Exception) {
            WhatsAppDiag.log(TAG, "on-demand request failed: ${e.message}")
        }
    }

    /** Inflate a zlib (RFC 1950) compressed buffer. WhatsApp history blobs are zlib-compressed. */
    private fun inflateZlib(data: ByteArray): ByteArray {
        val inflater = java.util.zip.Inflater()
        inflater.setInput(data)
        val out = java.io.ByteArrayOutputStream(maxOf(64, data.size * 4))
        val buf = ByteArray(16384)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    if (inflater.finished() || inflater.needsDictionary()) break
                    if (inflater.needsInput()) break
                }
                out.write(buf, 0, n)
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    suspend fun sendMessage(conversationId: String, body: String): Boolean {
        if (_state.value !is State.Connected) { WhatsAppDiag.log(TAG, "send: not connected"); return false }
        val ws = webSocket ?: return false

        val to = extractJid(conversationId) ?: run { WhatsAppDiag.log(TAG, "send: bad convId $conversationId"); return false }
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)
        WhatsAppDiag.log(TAG, "send: building message to $to")

        val node = buildEncryptedTextNode(to, id, body) ?: run { WhatsAppDiag.log(TAG, "send: build FAILED (no enc)"); return false }
        pendingMessageIDs.add(id)
        val sent = ws.send(WhatsAppProtocol.encodeNode(node))
        if (!sent) pendingMessageIDs.remove(id)
        WhatsAppDiag.log(TAG, "send: stanza sent=$sent id=$id")
        return sent
    }

    /**
     * Build a Signal-encrypted text message node for a recipient, fanning out to all of the
     * recipient's devices and our own other devices (via usync), establishing sessions as needed.
     * Group recipients (@g.us) use the sender-key (skmsg) path. Returns null on failure.
     */
    private suspend fun buildEncryptedTextNode(to: String, id: String, body: String): WhatsAppProtocol.Node? {
        if (to.contains("@g.us")) {
            return buildEncryptedGroupTextNode(to, id, body)
        }
        return buildEncryptedMessageNode(to, id, WhatsAppProtocol.buildConversationMessage(body), "text")
    }

    /** Encrypt+fan-out an arbitrary Message proto to a 1:1 recipient (and our own devices). */
    private suspend fun buildEncryptedMessageNode(
        to: String,
        id: String,
        msg: WhatsAppE2EProto.Message,
        type: String,
        extraEncAttrs: Map<String, String> = emptyMap(),
    ): WhatsAppProtocol.Node? {
        val auth = authData ?: return null
        val crypto = ensureE2E(auth) ?: return null
        val msgPlaintext = WhatsAppProtocol.padMessage(msg.toByteArray())
        val dsmPlaintext = WhatsAppProtocol.padMessage(WhatsAppProtocol.deviceSentPlaintext(to, msg))
        val ownUser = auth.wid.substringBefore("@").substringBefore(":").substringBefore(".")
        // Cache so we can re-encrypt+resend to a single device on a retry receipt.
        recentSentDMs[id] = SentDM(to, type, msgPlaintext, dsmPlaintext)

        val recipientDevices = getUserDevices(listOf(to)).ifEmpty { listOf(to) }
        val ownDevices = getUserDevices(listOf("$ownUser@s.whatsapp.net"))
        val allDevices = (recipientDevices + ownDevices).distinct()
        // Log the actual device JIDs so device tests reveal whether the recipient is addressed by
        // phone (…@s.whatsapp.net) or LID (…@lid). A phone-addressed <message to=..> carrying LID
        // participant <to jid=…@lid> without addressing_mode="lid" can be dropped by the server.
        WhatsAppDiag.log(TAG, "send: fanout to ${allDevices.size} device(s) [${allDevices.joinToString()}] (msg to=$to)")

        val (encs, includeIdentity) = encryptForDevices(
            crypto, allDevices, ownUser, auth.wid, msgPlaintext, dsmPlaintext,
        )
        WhatsAppDiag.log(TAG, "send: encrypted for ${encs.size} device(s) includeIdentity=$includeIdentity")
        if (encs.isEmpty()) {
            Log.e(TAG, "No devices could be encrypted for $to")
            return null
        }
        val deviceIdentity = if (includeIdentity) accountDeviceIdentity() else null
        return WhatsAppProtocol.buildFanOutMessageNode(
            to = to,
            id = id,
            type = type,
            participantEncs = encs,
            includeDeviceIdentity = includeIdentity,
            deviceIdentity = deviceIdentity,
            extraEncAttrs = extraEncAttrs,
        )
    }

    /**
     * Build a group text message. Delegates to [buildEncryptedGroupMessageNode] with a plain
     * conversation proto.
     */
    private suspend fun buildEncryptedGroupTextNode(groupJid: String, id: String, body: String): WhatsAppProtocol.Node? =
        buildEncryptedGroupMessageNode(groupJid, id, WhatsAppProtocol.buildConversationMessage(body))

    /**
     * Build an arbitrary group message: sender-key encrypt the content (skmsg) and 1:1 fan out the
     * SenderKeyDistributionMessage to every member device. Mirrors whatsmeow send.go sendGroup.
     * UNVERIFIED: libsignal-client's sender-key wire format (distribution UUID + versioned
     * SenderKeyMessage) differs from WhatsApp's legacy libsignal sender-key format, so group
     * skmsg crypto interop is not runtime-verified.
     */
    private suspend fun buildEncryptedGroupMessageNode(
        groupJid: String,
        id: String,
        msg: WhatsAppE2EProto.Message,
        type: String = "text",
        extraEncAttrs: Map<String, String> = emptyMap(),
    ): WhatsAppProtocol.Node? {
        val auth = authData ?: return null
        val crypto = ensureE2E(auth) ?: return null
        val contentPadded = WhatsAppProtocol.padMessage(msg.toByteArray())

        val skmsgCiphertext = try {
            crypto.encryptGroup(groupJid, contentPadded)
        } catch (e: Exception) {
            Log.e(TAG, "Group sender-key encrypt failed for $groupJid", e)
            return null
        }
        val skdmBytes = crypto.createSenderKeyDistribution(groupJid).serialize()
        val skdmPlaintext = WhatsAppProtocol.padMessage(
            WhatsAppProtocol.senderKeyDistributionPlaintext(groupJid, skdmBytes)
        )

        // Meta AI / bots participate in a group via a special "hosted"/"bot" server and are NOT
        // part of the group's end-to-end sender-key encryption — you literally cannot send them
        // the group sender key, and including them aborts the whole send. The reference
        // (whatsmeow prepareMessageNode) deletes hosted/hosted.lid devices for group sends for
        // exactly this reason; the bot instead receives @mentions over its own message-secret
        // channel. Keep every real user (phone + LID); drop only bot/hosted servers.
        val participants = queryGroupParticipants(groupJid).filter { jid ->
            val server = jid.substringAfter("@", "")
            server != "bot" && server != "hosted" && server != "hosted.lid"
        }
        if (participants.isEmpty()) {
            Log.w(TAG, "No user participants resolved for group $groupJid; cannot fan out SKDM")
            return null
        }
        val devices = getUserDevices(participants).ifEmpty { participants }
        val ownUser = auth.wid.substringBefore("@").substringBefore(":").substringBefore(".")
        val (encs, includeIdentity) = encryptForDevices(
            crypto, devices, ownUser, auth.wid, skdmPlaintext, null,
        )
        if (encs.isEmpty()) {
            Log.e(TAG, "No group devices could be encrypted for $groupJid")
            return null
        }
        val skMsg = WhatsAppProtocol.Node(
            tag = "enc",
            attrs = mapOf("v" to "2", "type" to "skmsg"),
            data = skmsgCiphertext,
        )
        val deviceIdentity = if (includeIdentity) accountDeviceIdentity() else null
        return WhatsAppProtocol.buildFanOutMessageNode(
            to = groupJid,
            id = id,
            type = type,
            participantEncs = encs,
            includeDeviceIdentity = includeIdentity,
            deviceIdentity = deviceIdentity,
            extraEnc = skMsg,
            extraEncAttrs = extraEncAttrs,
        )
    }

    // The self-signed ADV device identity sent in the device-identity node for pkmsg sends.
    // UNVERIFIED: stored at pair time; null until paired with the new pairing flow.
    private fun accountDeviceIdentity(): ByteArray? {
        val b64 = authData?.accountSignedDeviceIdentity?.takeIf { it.isNotEmpty() } ?: return null
        return try { Base64.decode(b64, Base64.NO_WRAP) } catch (e: Exception) { null }
    }

    private data class MediaUploadResult(
        val url: String,
        val directPath: String,
    )

    private suspend fun uploadMedia(
        encryptedData: ByteArray,
        mediaType: String,
        token: String,
    ): MediaUploadResult = withContext(Dispatchers.IO) {
        val conn = mediaConn() ?: throw Exception("media_conn unavailable (no upload host/auth)")
        val (host, auth) = conn
        // whatsmeow upload.go: mmsType "image"/"video"/"audio"/"document"; stickers use the image bucket.
        val mmsType = if (mediaType == "sticker") "image" else mediaType
        val encAuth = java.net.URLEncoder.encode(auth, "UTF-8")
        val encToken = java.net.URLEncoder.encode(token, "UTF-8")
        val uploadUrl = "https://$host/mms/$mmsType/$token?auth=$encAuth&token=$encToken"
        val requestBody = encryptedData.toRequestBody(null)
        // WhatsApp's rupload endpoint expects POST (whatsmeow upload.go rawUpload). A PUT here
        // returns 404, which is why media send was failing.
        val request = Request.Builder()
            .url(uploadUrl)
            .post(requestBody)
            .header("Origin", "https://web.whatsapp.com")
            .header("Referer", "https://web.whatsapp.com/")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Media upload failed: HTTP ${response.code}")
        }
        val json = JSONObject(response.body?.string() ?: throw Exception("Empty upload response"))
        MediaUploadResult(
            url = json.getString("url"),
            directPath = json.getString("direct_path"),
        )
    }

    suspend fun sendMedia(
        conversationId: String,
        bytes: ByteArray,
        mimeType: String,
        fileName: String?
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false
        val to = extractJid(conversationId) ?: return false
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)

        val mediaType = when {
            mimeType == "image/webp" -> "sticker"
            mimeType.startsWith("image/") -> "image"
            mimeType.startsWith("video/") -> "video"
            mimeType.startsWith("audio/") -> "audio"
            else -> "document"
        }
        val mediaKeyStr = when (mediaType) {
            "sticker" -> WhatsAppProtocol.MEDIA_KEY_STICKER
            "image" -> WhatsAppProtocol.MEDIA_KEY_IMAGE
            "video" -> WhatsAppProtocol.MEDIA_KEY_VIDEO
            "audio" -> WhatsAppProtocol.MEDIA_KEY_AUDIO
            else -> WhatsAppProtocol.MEDIA_KEY_DOCUMENT
        }

        pendingMessageIDs.add(id)
        if (bytes.size > MAX_FILE_SIZE) {
            Log.e(TAG, "File too large: ${bytes.size} bytes (max $MAX_FILE_SIZE)")
            pendingMessageIDs.remove(id)
            return false
        }
        return try {
            WhatsAppDiag.log(TAG, "media: start type=$mediaType size=${bytes.size} to=$to")
            val enc = WhatsAppProtocol.encryptMedia(bytes, mediaKeyStr)
            val token = Base64.encodeToString(enc.fileEncSha256, Base64.URL_SAFE or Base64.NO_WRAP)
            WhatsAppDiag.log(TAG, "media: encrypted, uploading…")
            val upload = uploadMedia(enc.encryptedData, mediaType, token)
            WhatsAppDiag.log(TAG, "media: uploaded url=${upload.url.take(40)}")
            val proto = WhatsAppProtocol.buildMediaProto(
                upload.url, upload.directPath,
                enc.mediaKey, enc.fileSha256, enc.fileEncSha256, enc.fileLength,
                mimeType, fileName, mediaType
            )
            // Media must be Signal-encrypted + fanned out like any other message (an
            // unencrypted node is dropped) and carry stanza type "media" + a "mediatype"
            // enc attr. Ref whatsmeow send.go getTypeFromMessage / prepareMessageNode.
            val encAttrs = mapOf("mediatype" to mediaType)
            val node = if (to.contains("@g.us")) {
                buildEncryptedGroupMessageNode(to, id, proto, type = "media", extraEncAttrs = encAttrs)
            } else {
                buildEncryptedMessageNode(to, id, proto, "media", extraEncAttrs = encAttrs)
            } ?: run {
                WhatsAppDiag.log(TAG, "media: build node FAILED (encryption produced no recipients)")
                pendingMessageIDs.remove(id); return false
            }
            val sent = ws.send(WhatsAppProtocol.encodeNode(node))
            WhatsAppDiag.log(TAG, "media: stanza sent=$sent id=$id")
            if (!sent) pendingMessageIDs.remove(id)
            sent
        } catch (e: Exception) {
            WhatsAppDiag.log(TAG, "media: FAILED ${e.message}")
            Log.e(TAG, "Failed to send media", e)
            pendingMessageIDs.remove(id)
            false
        }
    }

    /**
     * Mark messages as read with per-sender batching for group chats.
     * From Go HandleMatrixReadReceipt — groups messages by sender.
     */
    suspend fun markRead(
        conversationId: String,
        messageIds: List<String> = emptyList(),
        senderJids: Map<String, String> = emptyMap(),
    ) {
        val to = extractJid(conversationId) ?: return
        val ws = webSocket ?: return
        if (messageIds.isEmpty()) return

        // Filter out own messages by checking both JID and LID (Issue 4)
        val ownJid = authData?.wid ?: ""
        val ownLid = authData?.lid ?: ""
        val filteredIds = messageIds.filter { msgId ->
            val sender = senderJids[msgId] ?: ""
            sender != ownJid && (ownLid.isEmpty() || sender != ownLid)
        }
        if (filteredIds.isEmpty()) return

        val isGroup = to.contains("@g.us")
        if (isGroup && senderJids.isNotEmpty()) {
            // Batch by sender for group chats (Go HandleMatrixReadReceipt)
            val bySender = mutableMapOf<String, MutableList<String>>()
            filteredIds.forEach { msgId ->
                val sender = senderJids[msgId] ?: ""
                bySender.getOrPut(sender) { mutableListOf() }.add(msgId)
            }
            bySender.forEach { (sender, ids) ->
                val node = WhatsAppProtocol.buildReadReceipt(
                    chatJid = to,
                    messageIds = ids,
                    senderJid = sender.ifEmpty { null },
                )
                ws.send(WhatsAppProtocol.encodeNode(node))
            }
        } else {
            val node = WhatsAppProtocol.buildReadReceipt(chatJid = to, messageIds = filteredIds)
            ws.send(WhatsAppProtocol.encodeNode(node))
        }
    }

    /**
     * Send a WhatsApp read receipt (<receipt type="read">) up to [lastMessageId] when the user
     * marks a thread read. No-op (returns true) when the account has read receipts disabled in
     * privacy settings — so we honor the user's choice rather than leaking read state. Integrator
     * broadcast contract. [lastTimestamp] is epoch ms (GMEvent convention) or s; both accepted.
     */
    suspend fun sendReadReceipt(
        conversationId: String,
        lastMessageId: String?,
        lastTimestamp: Long,
        senderJid: String? = null,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false
        val to = extractJid(conversationId) ?: return false
        if (lastMessageId.isNullOrEmpty()) return false
        // Message ids arrive DB-prefixed ("wa:RAWID"); WhatsApp only matches the raw id.
        val rawMessageId = extractMessageId(lastMessageId)
        if (!readReceiptsEnabled) {
            WhatsAppDiag.log(TAG, "read receipt suppressed (privacy off) for $to")
            return true
        }
        val tSec = when {
            lastTimestamp <= 0 -> System.currentTimeMillis() / 1000
            lastTimestamp > 100_000_000_000L -> lastTimestamp / 1000 // ms → s
            else -> lastTimestamp
        }
        val node = WhatsAppProtocol.buildReadReceipt(
            chatJid = to,
            messageIds = listOf(rawMessageId),
            senderJid = senderJid?.ifEmpty { null },
            timestamp = tSec,
        )
        val sent = ws.send(WhatsAppProtocol.encodeNode(node))
        WhatsAppDiag.log(TAG, "read receipt to=$to id=$rawMessageId sent=$sent")
        return sent
    }

    /**
     * Query the account privacy settings and cache whether read receipts are enabled. WhatsApp
     * returns <privacy><category name="readreceipts" value="all|none"/>…</privacy>; "none" means
     * the user turned read receipts off (and then cannot see others' either). Ref whatsmeow
     * privacysettings.go GetPrivacySettings. Failures leave the default (enabled) in place.
     */
    private suspend fun refreshPrivacySettings() {
        val iq = WhatsAppProtocol.Node(
            tag = "iq",
            attrs = mapOf(
                "id" to generateMessageId(),
                "type" to "get",
                "xmlns" to "privacy",
                "to" to "s.whatsapp.net",
            ),
            content = listOf(WhatsAppProtocol.Node(tag = "privacy")),
        )
        val resp = sendIqAndWait(iq, timeoutMs = 10_000) ?: return
        val privacy = resp.getChildByTag("privacy") ?: return
        privacy.getChildren()
            .firstOrNull { it.tag == "category" && it.attrs["name"] == "readreceipts" }
            ?.let { readReceiptsEnabled = it.attrs["value"] != "none" }
        WhatsAppDiag.log(TAG, "privacy: readReceipts=${if (readReceiptsEnabled) "on" else "off"}")
    }

    /**
     * Send (or, with an empty [emoji], clear) a reaction on [messageId]. Routed through the same
     * Signal encryption + multi-device fan-out as a normal message — an unencrypted reaction node
     * is silently dropped by WhatsApp. The reaction key must point at the target message, so
     * [targetFromMe] / [targetSenderJid] describe the ORIGINAL message being reacted to.
     */
    suspend fun sendReaction(
        conversationId: String,
        messageId: String,
        emoji: String,
        targetFromMe: Boolean,
        targetSenderJid: String?,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false
        val chatJid = extractJid(conversationId) ?: return false
        val rawTargetId = extractMessageId(messageId)
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)

        val strippedEmoji = emoji.replace("\uFE0F", "")
        val proto = WhatsAppProtocol.buildReactionProto(
            chatJid = chatJid,
            targetMessageId = rawTargetId,
            emoji = strippedEmoji,
            targetFromMe = targetFromMe,
            targetSenderJid = targetSenderJid?.let { extractMessageId(it) }?.ifEmpty { null },
        )
        val node = if (chatJid.contains("@g.us")) {
            buildEncryptedGroupMessageNode(chatJid, id, proto, type = "reaction")
        } else {
            buildEncryptedMessageNode(chatJid, id, proto, "reaction")
        } ?: run { WhatsAppDiag.log(TAG, "reaction: build FAILED (no enc) for $chatJid"); return false }

        pendingMessageIDs.add(id)
        val sent = ws.send(WhatsAppProtocol.encodeNode(node))
        if (!sent) pendingMessageIDs.remove(id)
        WhatsAppDiag.log(TAG, "reaction to=$chatJid target=$rawTargetId emoji=${strippedEmoji.ifEmpty { "<remove>" }} sent=$sent")
        return sent
    }

    /**
     * Remove a reaction from a message by sending an empty emoji.
     * From Go HandleMatrixReactionRemove.
     */
    suspend fun removeReaction(
        conversationId: String,
        messageId: String,
        targetFromMe: Boolean,
        targetSenderJid: String?,
    ): Boolean = sendReaction(conversationId, messageId, "", targetFromMe, targetSenderJid)

    /**
     * Send a typing indicator (chat presence) with media type differentiation.
     * From whatsmeow HandleMatrixTyping / SendChatPresence.
     * Supports: text typing, audio recording, media uploading.
     */
    enum class TypingType { TEXT, RECORDING_AUDIO, UPLOADING_MEDIA }

    suspend fun sendTyping(
        conversationId: String,
        isTyping: Boolean,
        typingType: TypingType = TypingType.TEXT,
    ) {
        if (_state.value !is State.Connected) return
        val ws = webSocket ?: return
        val chatJid = extractJid(conversationId) ?: return

        // Go HandleMatrixTyping: UploadingMedia returns nil (not sent)
        if (typingType == TypingType.UPLOADING_MEDIA) return

        val isAudio = typingType == TypingType.RECORDING_AUDIO
        val node = WhatsAppProtocol.buildChatPresence(chatJid, isTyping, isAudio, authData?.wid ?: "")
        ws.send(WhatsAppProtocol.encodeNode(node))
    }

    /**
     * Handle the server <success> stanza: the point at which login is actually authenticated.
     * Mirrors whatsmeow connectionevents.go handleConnectSuccess — persists the server-assigned
     * LID, sends SetPassive(false) so the companion leaves passive mode and receives the full
     * event stream, then uploads prekeys if low, sends unavailable presence and starts backfill.
     */
    private suspend fun handleConnectSuccess(node: WhatsAppProtocol.Node) {
        WhatsAppDiag.log(TAG, "AUTHENTICATED (<success>) — login complete")
        reconnectAttempts = 0
        _state.value = State.Connected
        val lid = node.attrs["lid"]
        val current = authData
        if (!lid.isNullOrEmpty() && current != null && current.lid.isEmpty()) {
            val updated = current.copy(lid = lid)
            authData = updated
            WhatsAppAuthData.save(appContext, updated)
        }
        scope.launch { maybeUploadPreKeys() }
        scope.launch { refreshPrivacySettings() }
        val pas = setPassiveActive()
        WhatsAppDiag.log(TAG, "post-success: SetPassive(active) ${if (pas) "OK" else "FAILED"}")
        sendUnavailablePresence()
        kickoffBackfill()
    }

    /**
     * Tell the server this device is active (not passive) so it pushes the full event stream.
     * Ref whatsmeow connectionevents.go SetPassive(false):
     * <iq xmlns="passive" type="set"><active/></iq>. Login sends passive=true, so this is
     * required after <success> or the companion never receives messages.
     */
    private suspend fun setPassiveActive(): Boolean {
        val node = WhatsAppProtocol.Node(
            tag = "iq",
            attrs = mapOf(
                "id" to generateMessageId(),
                "type" to "set",
                "xmlns" to "passive",
                "to" to "s.whatsapp.net",
            ),
            content = listOf(WhatsAppProtocol.Node(tag = "active")),
        )
        val resp = sendIqAndWait(node, timeoutMs = 10_000)
        return resp != null && resp.attrs["type"] != "error"
    }

    /**
     * Send unavailable presence on connect.
     * Go client.go Connected handler sends PresenceUnavailable.
     */
    private fun sendUnavailablePresence() {
        val ws = webSocket ?: return
        val node = WhatsAppProtocol.Node(
            tag = "presence",
            attrs = mapOf("type" to "unavailable"),
        )
        ws.send(WhatsAppProtocol.encodeNode(node))
    }

    /**
     * Edit a previously sent message.
     * From whatsmeow HandleMatrixEdit / BuildEdit
     */
    suspend fun sendEdit(conversationId: String, targetMessageId: String, newBody: String): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false
        val chatJid = extractJid(conversationId) ?: return false
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)
        val ownJid = authData?.wid ?: ""

        val node = WhatsAppProtocol.buildEditMessage(chatJid, targetMessageId, newBody, ownJid, id)
        return ws.send(WhatsAppProtocol.encodeNode(node))
    }

    /**
     * Revoke (delete) a previously sent message.
     * From whatsmeow HandleMatrixMessageRemove / BuildRevoke
     */
    suspend fun sendRevoke(conversationId: String, targetMessageId: String, senderJid: String = ""): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false
        val chatJid = extractJid(conversationId) ?: return false
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)
        val ownJid = authData?.wid ?: ""

        val node = WhatsAppProtocol.buildRevokeMessage(chatJid, senderJid, targetMessageId, ownJid, id)
        return ws.send(WhatsAppProtocol.encodeNode(node))
    }

    /**
     * Send a poll creation message.
     * From Go HandleMatrixPollStart / PollStartToWhatsApp.
     */
    /**
     * Send a native WhatsApp poll (PollCreationMessage), routed through the normal multi-device
     * fan-out (1:1) or sender-key path (groups). A fresh 32-byte messageSecret is generated and
     * stored keyed by the message id so later votes can derive their encryption key. Mirrors
     * whatsmeow BuildPollCreation.
     */
    suspend fun sendPollCreation(
        conversationId: String,
        question: String,
        options: List<String>,
        selectableCount: Int = 0,
    ): String? {
        WhatsAppDiag.log(TAG, "poll: sendPollCreation entry conv=$conversationId opts=${options.size}")
        if (_state.value !is State.Connected) { WhatsAppDiag.log(TAG, "poll: not connected"); return null }
        val ws = webSocket ?: run { WhatsAppDiag.log(TAG, "poll: no websocket"); return null }
        val to = extractJid(conversationId) ?: run { WhatsAppDiag.log(TAG, "poll: bad convId"); return null }
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)

        val messageSecret = ByteArray(32).also { random.nextBytes(it) }
        val msg = WhatsAppProtocol.buildPollCreationProto(question, options, selectableCount, messageSecret)
        // Polls use stanza type "poll" (not "text") — the server drops a poll sent as text.
        // Ref whatsmeow send.go getTypeFromMessage.
        WhatsAppDiag.log(TAG, "poll: start to=$to options=${options.size} group=${to.contains("@g.us")}")
        val node = if (to.contains("@g.us")) {
            buildEncryptedGroupMessageNode(to, id, msg, type = "poll")
        } else {
            buildEncryptedMessageNode(to, id, msg, "poll")
        } ?: run {
            WhatsAppDiag.log(TAG, "poll: build node FAILED (encryption produced no recipients)")
            return null
        }

        pendingMessageIDs.add(id)
        val sent = ws.send(WhatsAppProtocol.encodeNode(node))
        WhatsAppDiag.log(TAG, "poll: stanza sent=$sent id=$id")
        if (sent) {
            storePollOptions(id, options)
            storePollSecret(id, messageSecret)
        } else {
            pendingMessageIDs.remove(id)
        }
        // Return the real message id so the caller can reconcile its optimistic pending row —
        // votes (and stored secret/options) are keyed by this id.
        return if (sent) id else null
    }

    /**
     * Shared media-send contract poll entrypoint (called by MessagesSessionManager). Returns the
     * real poll message id on success (null on failure) so the pending row can be reconciled.
     * Maps [allowMultiple] to selectableCount (whatsmeow PollCreationMessage semantics).
     */
    suspend fun sendPoll(
        conversationId: String,
        question: String,
        options: List<String>,
        allowMultiple: Boolean,
    ): String? = sendPollCreation(
        conversationId = conversationId,
        question = question,
        options = options,
        selectableCount = if (allowMultiple) options.size else 1,
    )

    /**
     * Send a poll vote.
     * From Go HandleMatrixPollVote / PollVoteToWhatsApp.
     */
    /**
     * Cast (or change) a poll vote. Encrypts the selected option hashes with the poll's
     * messageSecret and sends a PollUpdateMessage via the normal fan-out (stanza type "poll").
     * [pollCreatorJid]/[pollFromMe] describe the ORIGINAL poll message (needed for key derivation
     * + the vote's pollCreationMessageKey). Ref whatsmeow BuildPollVote.
     */
    suspend fun sendPollVote(
        conversationId: String,
        pollMessageId: String,
        pollCreatorJid: String,
        pollFromMe: Boolean,
        selectedOptionNames: List<String>,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false
        val chatJid = extractJid(conversationId) ?: return false
        val rawPollId = extractMessageId(pollMessageId)
        val secret = loadPollSecret(rawPollId)
            ?: run { WhatsAppDiag.log(TAG, "poll vote: no secret for $rawPollId"); return false }
        val ownJid = authData?.wid ?: ""
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)

        val optionHashes = selectedOptionNames.map { option ->
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(option.toByteArray(Charsets.UTF_8))
        }
        val creator = if (pollFromMe) ownJid else pollCreatorJid.ifEmpty { chatJid }
        val proto = WhatsAppProtocol.buildPollVoteMessage(
            chatJid = chatJid,
            pollMessageId = rawPollId,
            pollCreatorJid = creator,
            pollFromMe = pollFromMe,
            voterJid = ownJid,
            optionHashes = optionHashes,
            pollSecret = secret,
        )
        val node = if (chatJid.contains("@g.us")) {
            buildEncryptedGroupMessageNode(chatJid, id, proto, type = "poll")
        } else {
            buildEncryptedMessageNode(chatJid, id, proto, "poll")
        } ?: return false
        pendingMessageIDs.add(id)
        val sent = ws.send(WhatsAppProtocol.encodeNode(node))
        if (!sent) pendingMessageIDs.remove(id)
        return sent
    }

    /** Persist a poll's shared secret (in-memory + DB) so votes can be encrypted/decrypted later. */
    private suspend fun storePollSecret(msgId: String, secret: ByteArray) {
        pollSecrets[msgId] = secret
        db?.pollSecretDao()?.upsert(
            WhatsAppPollSecret(msgId, secret.joinToString("") { "%02x".format(it) })
        )
    }

    private suspend fun loadPollSecret(msgId: String): ByteArray? {
        pollSecrets[msgId]?.let { return it }
        val hex = db?.pollSecretDao()?.get(msgId) ?: return null
        return runCatching {
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }.getOrNull()?.also { pollSecrets[msgId] = it }
    }

    /**
     * Send a location message natively (LocationMessage proto), routed through the normal
     * multi-device Signal fan-out so it actually encrypts/delivers like a text message.
     * From whatsmeow from-matrix.go location handling.
     */
    suspend fun sendLocation(
        conversationId: String,
        latitude: Double,
        longitude: Double,
        name: String? = null,
        address: String? = null,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false
        val to = extractJid(conversationId) ?: return false
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)

        val msg = WhatsAppProtocol.buildLocationProto(latitude, longitude, name, address)
        val node = buildEncryptedMessageNode(to, id, msg, "text") ?: return false
        pendingMessageIDs.add(id)
        val sent = ws.send(WhatsAppProtocol.encodeNode(node))
        if (!sent) pendingMessageIDs.remove(id)
        return sent
    }

    /**
     * Send a contact/vCard message.
     * From Go wa-contact.go convertContactMessage.
     */
    suspend fun sendContact(
        conversationId: String,
        displayName: String,
        vcard: String,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false
        val chatJid = extractJid(conversationId) ?: return false
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)

        val node = WhatsAppProtocol.buildContactMessage(chatJid, displayName, vcard, id)
        return ws.send(WhatsAppProtocol.encodeNode(node))
    }

    /**
     * Set disappearing messages timer.
     * From Go HandleMatrixDisappearingTimer.
     * Allowed values: 0 (off), 86400 (24h), 604800 (7d), 7776000 (90d).
     */
    suspend fun setDisappearingTimer(conversationId: String, timerSeconds: Long): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false
        val chatJid = extractJid(conversationId) ?: return false

        val allowedValues = setOf(0L, 86400L, 604800L, 7776000L)
        if (timerSeconds !in allowedValues) {
            Log.w(TAG, "Invalid disappearing timer value: $timerSeconds")
            return false
        }

        val id = WhatsAppProtocol.generateMessageId(authData?.wid)
        val node = WhatsAppProtocol.buildDisappearingTimerMessage(chatJid, timerSeconds, id)
        return ws.send(WhatsAppProtocol.encodeNode(node))
    }

    /**
     * Set group name.
     * From Go HandleMatrixRoomName / SetGroupName.
     */
    suspend fun setGroupName(conversationId: String, name: String): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false
        val groupJid = extractJid(conversationId) ?: return false
        if (!groupJid.contains("@g.us")) return false

        val id = WhatsAppProtocol.generateMessageId(authData?.wid)
        val node = WhatsAppProtocol.buildGroupInfoChange(groupJid, "subject", name, id)
        return ws.send(WhatsAppProtocol.encodeNode(node))
    }

    /**
     * Set group topic/description with old/new ID tracking.
     * From Go HandleMatrixRoomTopic / SetGroupTopic.
     */
    suspend fun setGroupTopic(
        conversationId: String,
        topic: String,
        previousTopicId: String? = null,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false
        val groupJid = extractJid(conversationId) ?: return false
        if (!groupJid.contains("@g.us")) return false

        val id = WhatsAppProtocol.generateMessageId(authData?.wid)
        val node = WhatsAppProtocol.buildSetGroupTopic(groupJid, topic, id, previousTopicId)
        return ws.send(WhatsAppProtocol.encodeNode(node))
    }

    /**
     * Set group avatar with crop/resize/JPEG conversion.
     * Crops to square, scales between 190-720px, encodes as JPEG quality 75.
     * From Go HandleMatrixRoomAvatar / convertRoomAvatar.
     */
    suspend fun setGroupAvatar(conversationId: String, imageBytes: ByteArray): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false
        val groupJid = extractJid(conversationId) ?: return false
        if (!groupJid.contains("@g.us")) return false

        val processed = withContext(Dispatchers.Default) {
            cropResizeAvatar(imageBytes)
        } ?: return false

        val id = WhatsAppProtocol.generateMessageId(authData?.wid)
        val node = WhatsAppProtocol.Node(
            tag = "iq",
            attrs = mapOf(
                "id" to id,
                "type" to "set",
                "xmlns" to "w:profile:picture",
                "to" to groupJid,
            ),
            content = listOf(
                WhatsAppProtocol.Node(
                    tag = "picture",
                    attrs = mapOf("type" to "image"),
                    data = processed,
                )
            ),
        )
        return ws.send(WhatsAppProtocol.encodeNode(node))
    }

    private fun cropResizeAvatar(imageBytes: ByteArray): ByteArray? {
        val original = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null
        val size = minOf(original.width, original.height)
        val x = (original.width - size) / 2
        val y = (original.height - size) / 2
        val cropped = Bitmap.createBitmap(original, x, y, size, size)
        // Go: min=190, max=720, BiLinear scaling
        val minDim = 190
        val maxDim = 720
        val targetDim = size.coerceIn(minDim, maxDim)
        val scaled = if (size != targetDim) {
            Bitmap.createScaledBitmap(cropped, targetDim, targetDim, true).also {
                if (it !== cropped) cropped.recycle()
            }
        } else cropped
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
        if (scaled !== original) scaled.recycle()
        if (original !== cropped && original !== scaled) original.recycle()
        return out.toByteArray()
    }

    /**
     * Add or remove group members.
     * From Go HandleMatrixMembership / UpdateGroupParticipants.
     */
    suspend fun updateGroupParticipants(
        conversationId: String,
        participantJids: List<String>,
        action: String,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false
        val groupJid = extractJid(conversationId) ?: return false
        if (!groupJid.contains("@g.us")) return false

        val validActions = setOf("add", "remove", "promote", "demote")
        if (action !in validActions) return false

        val id = WhatsAppProtocol.generateMessageId(authData?.wid)
        val node = WhatsAppProtocol.buildGroupParticipantChange(groupJid, participantJids, action, id)
        return ws.send(WhatsAppProtocol.encodeNode(node))
    }

    /**
     * Delete a chat, optionally leaving group, with AppState mutations.
     * From Go HandleMatrixDeleteChat.
     */
    suspend fun deleteChat(conversationId: String, leaveGroup: Boolean = true): Boolean {
        val jid = extractJid(conversationId) ?: return false
        val ws = webSocket

        if (leaveGroup && jid.contains("@g.us") && _state.value is State.Connected && ws != null) {
            val id = WhatsAppProtocol.generateMessageId(authData?.wid)
            val node = WhatsAppProtocol.buildLeaveGroup(jid, id)
            ws.send(WhatsAppProtocol.encodeNode(node))
        }

        // Query last message timestamp for the delete anchor
        val conversation = db?.conversationDao()?.getConversation(jid)
        val lastMsgTimestamp = conversation?.lastMessageTimestamp ?: 0L

        // Push AppState delete mutation (Go HandleMatrixDeleteChat PatchDelete)
        if (_state.value is State.Connected && ws != null) {
            val patchAttrs = mutableMapOf("jid" to jid, "action" to "delete")
            if (lastMsgTimestamp > 0) patchAttrs["messageTimestamp"] = lastMsgTimestamp.toString()

            val patchNode = WhatsAppProtocol.Node(
                tag = "iq",
                attrs = mapOf(
                    "id" to WhatsAppProtocol.generateMessageId(authData?.wid),
                    "type" to "set",
                    "xmlns" to "w:sync:app:state",
                    "to" to "s.whatsapp.net",
                ),
                content = listOf(
                    WhatsAppProtocol.Node(
                        tag = "sync",
                        content = listOf(
                            WhatsAppProtocol.Node(
                                tag = "collection",
                                attrs = mapOf("name" to "regular"),
                                content = listOf(
                                    WhatsAppProtocol.Node(
                                        tag = "patch",
                                        attrs = patchAttrs,
                                    )
                                ),
                            )
                        ),
                    )
                ),
            )
            ws.send(WhatsAppProtocol.encodeNode(patchNode))
        }

        db?.conversationDao()?.delete(jid)
        _events.emit(GMEvent.ConversationDeleted(source, conversationId))
        return true
    }

    suspend fun sendNewThread(recipientJid: String, body: String): String? {
        if (_state.value !is State.Connected) return null
        val ws = webSocket ?: return null
        val jid = if (recipientJid.contains("@")) recipientJid else "$recipientJid@s.whatsapp.net"
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)
        val node = buildEncryptedTextNode(jid, id, body) ?: return null
        pendingMessageIDs.add(id)
        val sent = ws.send(WhatsAppProtocol.encodeNode(node))
        if (!sent) pendingMessageIDs.remove(id)
        return if (sent) "wa:$jid" else null
    }

    suspend fun deleteThread(conversationId: String): Boolean {
        val jid = extractJid(conversationId) ?: return false
        db?.conversationDao()?.delete(jid)
        _events.emit(GMEvent.ConversationDeleted(source, conversationId))
        return true
    }

    suspend fun markChatUnread(conversationId: String, unread: Boolean) {
        if (_state.value !is State.Connected) return
        val ws = webSocket ?: return
        val chatJid = extractJid(conversationId) ?: return

        val patchNode = WhatsAppProtocol.Node(
            tag = "iq",
            attrs = mapOf(
                "id" to WhatsAppProtocol.generateMessageId(authData?.wid),
                "type" to "set",
                "xmlns" to "w:sync:app:state",
                "to" to "s.whatsapp.net",
            ),
            content = listOf(
                WhatsAppProtocol.Node(
                    tag = "sync",
                    content = listOf(
                        WhatsAppProtocol.Node(
                            tag = "collection",
                            attrs = mapOf("name" to "regular"),
                            content = listOf(
                                WhatsAppProtocol.Node(
                                    tag = "patch",
                                    attrs = mapOf(
                                        "jid" to chatJid,
                                        "action" to "markRead",
                                    ),
                                    data = if (unread) "true".toByteArray() else "false".toByteArray(),
                                )
                            ),
                        )
                    ),
                )
            ),
        )
        ws.send(WhatsAppProtocol.encodeNode(patchNode))
    }

    suspend fun setMute(conversationId: String, muteUntilMs: Long) {
        if (_state.value !is State.Connected) return
        val ws = webSocket ?: return
        val chatJid = extractJid(conversationId) ?: return

        val patchNode = WhatsAppProtocol.Node(
            tag = "iq",
            attrs = mapOf(
                "id" to WhatsAppProtocol.generateMessageId(authData?.wid),
                "type" to "set",
                "xmlns" to "w:sync:app:state",
                "to" to "s.whatsapp.net",
            ),
            content = listOf(
                WhatsAppProtocol.Node(
                    tag = "sync",
                    content = listOf(
                        WhatsAppProtocol.Node(
                            tag = "collection",
                            attrs = mapOf("name" to "regular"),
                            content = listOf(
                                WhatsAppProtocol.Node(
                                    tag = "patch",
                                    attrs = mapOf(
                                        "jid" to chatJid,
                                        "action" to "mute",
                                    ),
                                    data = muteUntilMs.toString().toByteArray(),
                                )
                            ),
                        )
                    ),
                )
            ),
        )
        ws.send(WhatsAppProtocol.encodeNode(patchNode))
        val conv = db?.conversationDao()?.getConversation(chatJid)
        if (conv != null) {
            db?.conversationDao()?.upsert(conv.copy(muteEndTime = muteUntilMs))
        }
    }

    suspend fun togglePin(conversationId: String, pinned: Boolean) {
        if (_state.value !is State.Connected) return
        val ws = webSocket ?: return
        val chatJid = extractJid(conversationId) ?: return

        val patchNode = WhatsAppProtocol.Node(
            tag = "iq",
            attrs = mapOf(
                "id" to WhatsAppProtocol.generateMessageId(authData?.wid),
                "type" to "set",
                "xmlns" to "w:sync:app:state",
                "to" to "s.whatsapp.net",
            ),
            content = listOf(
                WhatsAppProtocol.Node(
                    tag = "sync",
                    content = listOf(
                        WhatsAppProtocol.Node(
                            tag = "collection",
                            attrs = mapOf("name" to "regular"),
                            content = listOf(
                                WhatsAppProtocol.Node(
                                    tag = "patch",
                                    attrs = mapOf(
                                        "jid" to chatJid,
                                        "action" to "pin",
                                    ),
                                    data = if (pinned) "true".toByteArray() else "false".toByteArray(),
                                )
                            ),
                        )
                    ),
                )
            ),
        )
        ws.send(WhatsAppProtocol.encodeNode(patchNode))
        val conv = db?.conversationDao()?.getConversation(chatJid)
        if (conv != null) {
            db?.conversationDao()?.upsert(conv.copy(pinned = pinned))
        }
    }

    /**
     * Download and decrypt media from a WhatsApp media message.
     * Integrates WhatsAppProtocol.decryptMedia() for incoming media.
     * From Go whatsmeow.Download.
     */
    suspend fun downloadMedia(
        url: String,
        mediaKey: ByteArray,
        mediaType: String,
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("Origin", "https://web.whatsapp.com")
                .header("Referer", "https://web.whatsapp.com/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Media download failed: HTTP ${response.code}")
                return@withContext null
            }
            val encrypted = response.body?.bytes() ?: return@withContext null
            WhatsAppProtocol.decryptMedia(encrypted, mediaKey, mediaType)
        } catch (e: Exception) {
            Log.e(TAG, "Media download/decrypt failed", e)
            null
        }
    }

    fun isLoggedIn(): Boolean {
        return authData != null && _state.value is State.Connected
    }

    /**
     * Download + decrypt an incoming media message and cache it locally, returning a
     * [com.vayunmathur.messages.data.MessageAttachment] pointing at the decrypted file (file://
     * URL) so the UI renders it inline. Returns null for non-media messages or on
     * download/decrypt failure (the "[Image]"/"[Video]" placeholder body then remains).
     * Ref whatsmeow Download.
     */
    private suspend fun buildIncomingMediaAttachment(
        e2e: WhatsAppE2EProto.Message,
        msgId: String,
    ): com.vayunmathur.messages.data.MessageAttachment? {
        val url: String; val directPath: String; val mediaKey: ByteArray; val encSha: ByteArray
        val mime: String?; val keyInfo: String; val type: String
        val width: Int; val height: Int; val fileName: String?; val ext: String
        when {
            e2e.hasImageMessage() -> e2e.imageMessage.let {
                url = it.url; directPath = it.directPath; mediaKey = it.mediaKey.toByteArray()
                encSha = it.fileEncSha256.toByteArray(); mime = it.mimetype.ifEmpty { "image/jpeg" }
                keyInfo = WhatsAppProtocol.MEDIA_KEY_IMAGE; type = "image"
                width = it.width; height = it.height; fileName = null; ext = "jpg"
            }
            e2e.hasStickerMessage() -> e2e.stickerMessage.let {
                url = it.url; directPath = it.directPath; mediaKey = it.mediaKey.toByteArray()
                encSha = it.fileEncSha256.toByteArray(); mime = it.mimetype.ifEmpty { "image/webp" }
                keyInfo = WhatsAppProtocol.MEDIA_KEY_STICKER; type = "sticker"
                width = it.width; height = it.height; fileName = null; ext = "webp"
            }
            e2e.hasVideoMessage() -> e2e.videoMessage.let {
                url = it.url; directPath = it.directPath; mediaKey = it.mediaKey.toByteArray()
                encSha = it.fileEncSha256.toByteArray(); mime = it.mimetype.ifEmpty { "video/mp4" }
                keyInfo = WhatsAppProtocol.MEDIA_KEY_VIDEO; type = "video"
                width = it.width; height = it.height; fileName = null; ext = "mp4"
            }
            e2e.hasAudioMessage() -> e2e.audioMessage.let {
                url = it.url; directPath = it.directPath; mediaKey = it.mediaKey.toByteArray()
                encSha = it.fileEncSha256.toByteArray(); mime = it.mimetype.ifEmpty { "audio/ogg" }
                keyInfo = WhatsAppProtocol.MEDIA_KEY_AUDIO; type = "audio"
                width = 0; height = 0; fileName = null; ext = "ogg"
            }
            e2e.hasDocumentMessage() -> e2e.documentMessage.let {
                url = it.url; directPath = it.directPath; mediaKey = it.mediaKey.toByteArray()
                encSha = it.fileEncSha256.toByteArray(); mime = it.mimetype.ifEmpty { null }
                keyInfo = WhatsAppProtocol.MEDIA_KEY_DOCUMENT; type = "file"
                width = 0; height = 0
                fileName = it.fileName.ifEmpty { it.title.ifEmpty { null } }
                ext = it.fileName.substringAfterLast('.', "").ifEmpty { "bin" }
            }
            else -> return null
        }
        if (mediaKey.isEmpty()) return null
        val downloadUrl = url.ifEmpty {
            if (directPath.isEmpty()) return null
            val host = mediaConn()?.first ?: return null
            val mmsType = when (type) {
                "sticker", "image" -> "image"
                "video" -> "video"
                "audio" -> "audio"
                else -> "document"
            }
            val hash = Base64.encodeToString(encSha, Base64.URL_SAFE or Base64.NO_WRAP)
            "https://$host$directPath&hash=$hash&mms-type=$mmsType&__wa-mms="
        }
        val bytes = downloadMedia(downloadUrl, mediaKey, keyInfo) ?: return null
        return try {
            val dir = java.io.File(appContext.cacheDir, "whatsapp_media")
            dir.mkdirs()
            val safeName = msgId.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val file = java.io.File(dir, "$safeName.$ext")
            if (!(file.exists() && file.length() > 0)) file.writeBytes(bytes)
            com.vayunmathur.messages.data.MessageAttachment(
                url = "file://${file.absolutePath}",
                mimeType = mime,
                attachmentType = type,
                fileName = fileName,
                width = width,
                height = height,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache incoming media for $msgId", e)
            null
        }
    }

    /**
     * Logout from WhatsApp server and clear local data.
     * From whatsmeow LogoutRemote
     */
    fun logoutRemote() {
        scope.launch {
            val ws = webSocket
            val ownJid = authData?.wid ?: ""
            if (ws != null && ownJid.isNotEmpty()) {
                val logoutNode = WhatsAppProtocol.Node(
                    tag = "iq",
                    attrs = mapOf(
                        "to" to "s.whatsapp.net",
                        "type" to "set",
                        "xmlns" to "md",
                        "id" to WhatsAppProtocol.generateMessageId(authData?.wid),
                    ),
                    content = listOf(
                        WhatsAppProtocol.Node(
                            tag = "remove-companion-device",
                            attrs = mapOf("jid" to ownJid, "reason" to "user_initiated")
                        )
                    )
                )
                try {
                    ws.send(WhatsAppProtocol.encodeNode(logoutNode))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send logout", e)
                }
            }
            stop()
        }
    }

    private fun extractJid(conversationId: String): String? {
        // Conversation ID arrives double-prefixed: the bridge prepends the source idPrefix ("wa")
        // to our already-"wa:"-prefixed id, giving "wa:wa:{jid}". Strip all leading "wa:".
        var s = conversationId
        while (s.startsWith("wa:")) s = s.removePrefix("wa:")
        return s.ifEmpty { null }
    }

    /** Strip the DB source prefix ("wa:") from a stored message id to recover the raw
     *  WhatsApp message id that the wire protocol (receipts, reaction keys) expects. */
    private fun extractMessageId(messageId: String): String {
        var s = messageId
        while (s.startsWith("wa:")) s = s.removePrefix("wa:")
        return s
    }

    private fun generateMessageId(): String {
        return WhatsAppProtocol.generateMessageId(authData?.wid)
    }

    private fun generateRef(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun generateClientId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun generateToken(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private suspend fun resolveName(jid: String): String {
        // Bot accounts (Meta AI) have no phone number — give them a friendly name.
        if (jid.contains("@bot")) return "Meta AI"
        return nameCache.getOrPut(jid) {
            // Display the phone number, stripping any device/agent/group suffixes
            // ("1234:1@…", "1234.5@…", "1234-1620@g.us").
            val phone = jid.substringBefore("@")
                .substringBefore(":")
                .substringBefore(".")
                .substringBefore("-")
            "+$phone"
        }
    }

    /**
     * Replace the raw "@<number>" mention tokens WhatsApp embeds in message text with the
     * mentioned contact's display name (or "You" for the local user, "Meta AI" for the bot),
     * using [WhatsAppMessage.mentionedJids] to know who each token refers to.
     */
    private suspend fun resolveMentionsInBody(body: String, mentionedJids: List<String>): String {
        if (mentionedJids.isEmpty() || !body.contains("@")) return body
        var result = body
        for (jid in mentionedJids) {
            val userPart = jid.substringBefore("@").substringBefore(":").substringBefore(".")
            if (userPart.isEmpty()) continue
            val resolved = resolveJID(jid)
            val name = when {
                resolved == authData?.wid || resolved == authData?.lid -> "You"
                else -> resolveName(resolved)
            }
            result = result.replace("@$userPart", "@$name")
        }
        return result
    }

    fun getContactSuggestions(query: String): List<ContactSuggestion> {
        // Return cached contacts matching query
        return nameCache.entries
            .filter { it.value.contains(query, ignoreCase = true) }
            .map { ContactSuggestion(it.value, null, null, MessageSource.WHATSAPP) }
            .take(10)
    }
}
