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
import org.signal.libsignal.protocol.state.PreKeyBundle
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
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private val pollSecrets = ConcurrentHashMap<String, ByteArray>()
    // Cached media upload connection (host, auth, expiryEpochMs) from the w:m media_conn IQ.
    private var mediaConnCache: Triple<String, String, Long>? = null

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
        val from = node.attrs["from"] ?: "s.whatsapp.net"
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
        val from = node.attrs["from"] ?: "s.whatsapp.net"
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
            webSocket?.send(WhatsAppProtocol.encodeNode(confirm))
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
        val resp = sendIqAndWait(iq) ?: return emptyList()
        val list = resp.getChildByTag("usync")?.getChildByTag("list") ?: return emptyList()
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
        for (dev in devices.distinct()) {
            if (dev == ownDeviceJid) continue
            val devUser = dev.substringBefore("@").substringBefore(":")
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
                            if (authData != null) {
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
                    WhatsAppDiag.log(TAG, "login stream:error code=${node.attrs["code"]} child=${errorNode?.tag} full=${nodeSummary(node)}")
                    _state.value = State.Disconnected("Stream error")
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

                if (node.tag != "message") return@launch

                // Check for undecryptable messages (Go handleWAUndecryptableMessage)
                val encNode = node.getChildByTag("enc")
                if (encNode?.data == null && node.attrs["type"] == "text") {
                    trackUndecryptable(node)
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
                            "skmsg" -> crypto.decryptGroup(senderJid, data)
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
                    sendRetryReceipt(node)
                    return@launch
                }

                // Process inbound sender-key distribution so future group skmsg decrypt.
                // UNVERIFIED: group sender-key wire mapping not runtime-tested.
                val skdm = message.e2eMessage?.takeIf { it.hasSenderKeyDistributionMessage() }
                    ?.senderKeyDistributionMessage
                if (skdm != null && crypto != null) {
                    try {
                        crypto.processSenderKeyDistribution(
                            message.participant ?: message.from,
                            skdm.axolotlSenderKeyDistributionMessage.toByteArray(),
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to process SKDM", e)
                    }
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
                }

                // Store poll secrets from incoming polls for vote encryption
                if (message.pollData != null && !message.pollData.isPollVote && message.e2eMessage != null) {
                    val secret = message.e2eMessage.messageContextInfo?.messageSecret?.toByteArray()
                    if (secret != null) pollSecrets[message.id] = secret
                }

                // Handle reactions separately (Go handleWAMessage reaction case)
                if (message.e2eMessage?.hasReactionMessage() == true) {
                    val reaction = message.e2eMessage.reactionMessage
                    val emoji = reaction.text?.replace("\uFE0F", "") ?: ""
                    val targetId = reaction.key?.id ?: ""
                    if (emoji.isEmpty()) {
                        _events.emit(GMEvent.ReactionRemoved(
                            source = MessageSource.WHATSAPP,
                            conversationId = "wa:${message.from}",
                            messageId = targetId,
                            senderId = sender,
                        ))
                    } else {
                        _events.emit(GMEvent.ReactionReceived(
                            source = MessageSource.WHATSAPP,
                            conversationId = "wa:${message.from}",
                            messageId = targetId,
                            senderId = sender,
                            emoji = emoji,
                        ))
                    }
                    return@launch
                }

                _events.emit(GMEvent.IncomingMessage(
                    source = MessageSource.WHATSAPP,
                    conversationId = "wa:${message.from}",
                    messageId = message.id,
                    body = displayBody,
                    peerName = resolveName(sender),
                    peerPhone = null,
                    timestamp = message.timestamp * 1000,
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
    private suspend fun handleServerSync(node: WhatsAppProtocol.Node, from: String) {
        node.content?.filterIsInstance<WhatsAppProtocol.Node>()?.forEach { child ->
            when (child.tag) {
                "collection" -> {
                    val collectionType = child.attrs["name"] ?: return@forEach
                    child.content?.filterIsInstance<WhatsAppProtocol.Node>()?.forEach { patch ->
                        handleAppStatePatch(collectionType, patch)
                    }
                }
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
    private suspend fun handleAppStatePatch(collectionType: String, patch: WhatsAppProtocol.Node) {
        val chatJid = patch.attrs["jid"] ?: return
        val action = patch.attrs["action"]
        val value = patch.data?.let { String(it, Charsets.UTF_8) }
        val convId = "wa:$chatJid"
        when {
            collectionType == "regular" && action == "mute" -> {
                val muteEnd = value?.toLongOrNull() ?: 0L
                Log.d(TAG, "AppState: mute $chatJid until $muteEnd")
                db?.conversationDao()?.updateMuteEndTime(chatJid, muteEnd)
                _events.emit(GMEvent.ConversationUpdate(
                    source = source, conversationId = convId,
                    peerName = null, peerPhone = null, avatarUrl = null,
                    lastPreview = null, lastTimestamp = 0, unreadCount = 0,
                ))
            }
            collectionType == "regular" && action == "pin" -> {
                val pinned = value == "true" || value == "1"
                Log.d(TAG, "AppState: pin $chatJid = $pinned")
                db?.conversationDao()?.updatePinned(chatJid, pinned)
            }
            collectionType == "regular" && action == "archive" -> {
                val archived = value == "true" || value == "1"
                Log.d(TAG, "AppState: archive $chatJid = $archived")
                db?.conversationDao()?.updateArchived(chatJid, archived)
            }
            collectionType == "regular" && action == "markRead" -> {
                val unread = value == "true" || value == "1"
                Log.d(TAG, "AppState: markedAsUnread $chatJid = $unread")
                db?.conversationDao()?.updateMarkedAsUnread(chatJid, unread)
            }
            collectionType == "regular" && action == "star" -> {
                Log.d(TAG, "AppState: star/favorite $chatJid")
            }
            collectionType == "regular" && action == "delete" -> {
                Log.d(TAG, "AppState: delete chat $chatJid")
                db?.conversationDao()?.delete(chatJid)
                _events.emit(GMEvent.ConversationDeleted(source, convId))
            }
        }
    }

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
        backfillJob?.cancel()
        backfillJob = scope.launch {
            Log.i(TAG, "Starting history sync")
            try {
                // Request initial history sync from server (Go handleWAAppStateSyncComplete)
                val ws = webSocket ?: return@launch
                val id = WhatsAppProtocol.generateMessageId(authData?.wid)
                val syncNode = WhatsAppProtocol.Node(
                    tag = "iq",
                    attrs = mapOf(
                        "id" to id,
                        "type" to "set",
                        "xmlns" to "w:web",
                        "to" to "s.whatsapp.net",
                    ),
                    content = listOf(
                        WhatsAppProtocol.Node(
                            tag = "web",
                            attrs = mapOf("type" to "initial")
                        )
                    )
                )
                ws.send(WhatsAppProtocol.encodeNode(syncNode))
                Log.i(TAG, "History sync request sent")
            } catch (e: Exception) {
                Log.e(TAG, "History sync failed", e)
            }
        }
    }

    suspend fun sendMessage(conversationId: String, body: String): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false

        val to = extractJid(conversationId) ?: return false
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)

        val node = buildEncryptedTextNode(to, id, body) ?: return false
        pendingMessageIDs.add(id)
        val sent = ws.send(WhatsAppProtocol.encodeNode(node))
        if (!sent) pendingMessageIDs.remove(id)
        return sent
    }

    /**
     * Build a Signal-encrypted text message node for a recipient, fanning out to all of the
     * recipient's devices and our own other devices (via usync), establishing sessions as needed.
     * Group recipients (@g.us) use the sender-key (skmsg) path. Returns null on failure.
     */
    private suspend fun buildEncryptedTextNode(to: String, id: String, body: String): WhatsAppProtocol.Node? {
        val auth = authData ?: return null
        val crypto = ensureE2E(auth) ?: return null
        if (to.contains("@g.us")) {
            return buildEncryptedGroupTextNode(to, id, body)
        }
        val msg = WhatsAppProtocol.buildConversationMessage(body)
        val msgPlaintext = WhatsAppProtocol.padMessage(msg.toByteArray())
        val dsmPlaintext = WhatsAppProtocol.padMessage(WhatsAppProtocol.deviceSentPlaintext(to, msg))
        val ownUser = auth.wid.substringBefore("@").substringBefore(":")

        // Fan out to the recipient's devices + our own other devices; fall back to the bare JID
        // if usync is unavailable so 1:1 to the primary device still works.
        val recipientDevices = getUserDevices(listOf(to)).ifEmpty { listOf(to) }
        val ownDevices = getUserDevices(listOf("$ownUser@s.whatsapp.net"))
        val allDevices = (recipientDevices + ownDevices).distinct()

        val (encs, includeIdentity) = encryptForDevices(
            crypto, allDevices, ownUser, auth.wid, msgPlaintext, dsmPlaintext,
        )
        if (encs.isEmpty()) {
            Log.e(TAG, "No devices could be encrypted for $to")
            return null
        }
        val deviceIdentity = if (includeIdentity) accountDeviceIdentity() else null
        return WhatsAppProtocol.buildFanOutMessageNode(
            to = to,
            id = id,
            type = "text",
            participantEncs = encs,
            includeDeviceIdentity = includeIdentity,
            deviceIdentity = deviceIdentity,
        )
    }

    /**
     * Build a group text message: sender-key encrypt the content (skmsg) and 1:1 fan out the
     * SenderKeyDistributionMessage to every member device. Mirrors whatsmeow send.go sendGroup.
     * UNVERIFIED: libsignal-client's sender-key wire format (distribution UUID + versioned
     * SenderKeyMessage) differs from WhatsApp's legacy libsignal sender-key format, so group
     * skmsg crypto interop is not runtime-verified.
     */
    private suspend fun buildEncryptedGroupTextNode(groupJid: String, id: String, body: String): WhatsAppProtocol.Node? {
        val auth = authData ?: return null
        val crypto = ensureE2E(auth) ?: return null
        val msg = WhatsAppProtocol.buildConversationMessage(body)
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

        val participants = queryGroupParticipants(groupJid)
        if (participants.isEmpty()) {
            Log.w(TAG, "No participants resolved for group $groupJid; cannot fan out SKDM")
            return null
        }
        val devices = getUserDevices(participants).ifEmpty { participants }
        val ownUser = auth.wid.substringBefore("@").substringBefore(":")
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
            type = "text",
            participantEncs = encs,
            includeDeviceIdentity = includeIdentity,
            deviceIdentity = deviceIdentity,
            extraEnc = skMsg,
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
        val uploadUrl = "https://$host/mms/$mmsType/$token?auth=$encAuth&token=$token"
        val requestBody = encryptedData.toRequestBody(null)
        val request = Request.Builder()
            .url(uploadUrl)
            .put(requestBody)
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
            val enc = WhatsAppProtocol.encryptMedia(bytes, mediaKeyStr)
            val token = Base64.encodeToString(enc.fileEncSha256, Base64.URL_SAFE or Base64.NO_WRAP)
            val upload = uploadMedia(enc.encryptedData, mediaType, token)
            val node = WhatsAppProtocol.buildMediaMessage(
                to, id, upload.url, upload.directPath,
                enc.mediaKey, enc.fileSha256, enc.fileEncSha256, enc.fileLength,
                mimeType, fileName, mediaType
            )
            val sent = ws.send(WhatsAppProtocol.encodeNode(node))
            if (!sent) pendingMessageIDs.remove(id)
            sent
        } catch (e: Exception) {
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

    suspend fun sendReaction(conversationId: String, messageId: String, emoji: String) {
        if (_state.value !is State.Connected) return
        val ws = webSocket ?: return
        val chatJid = extractJid(conversationId) ?: return
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)
        val ownJid = authData?.wid ?: ""
        pendingMessageIDs.add(id)

        val strippedEmoji = emoji.replace("\uFE0F", "")
        val node = WhatsAppProtocol.buildReactionMessage(
            chatJid = chatJid,
            senderJid = "",
            targetMessageId = messageId,
            emoji = strippedEmoji,
            ownJid = ownJid,
            id = id,
        )
        val sent = ws.send(WhatsAppProtocol.encodeNode(node))
        if (!sent) pendingMessageIDs.remove(id)
    }

    /**
     * Remove a reaction from a message by sending empty emoji.
     * From Go HandleMatrixReactionRemove.
     */
    suspend fun removeReaction(conversationId: String, messageId: String, senderJid: String = "") {
        if (_state.value !is State.Connected) return
        val ws = webSocket ?: return
        val chatJid = extractJid(conversationId) ?: return
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)
        val ownJid = authData?.wid ?: ""
        pendingMessageIDs.add(id)

        val node = WhatsAppProtocol.buildReactionMessage(
            chatJid = chatJid,
            senderJid = senderJid,
            targetMessageId = messageId,
            emoji = "",
            ownJid = ownJid,
            id = id,
        )
        val sent = ws.send(WhatsAppProtocol.encodeNode(node))
        if (!sent) pendingMessageIDs.remove(id)
    }

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
    suspend fun sendPollCreation(
        conversationId: String,
        question: String,
        options: List<String>,
        selectableCount: Int = 0,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false
        val chatJid = extractJid(conversationId) ?: return false
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)

        val messageSecret = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val node = WhatsAppProtocol.buildPollCreationMessage(chatJid, question, options, selectableCount, id, messageSecret)
        pendingMessageIDs.add(id)
        val sent = ws.send(WhatsAppProtocol.encodeNode(node))
        if (sent) {
            storePollOptions(id, options)
            pollSecrets[id] = messageSecret
        } else {
            pendingMessageIDs.remove(id)
        }
        return sent
    }

    /**
     * Send a poll vote.
     * From Go HandleMatrixPollVote / PollVoteToWhatsApp.
     */
    suspend fun sendPollVote(
        conversationId: String,
        pollMessageId: String,
        pollSenderJid: String,
        selectedOptionNames: List<String>,
    ): Boolean {
        if (_state.value !is State.Connected) return false
        val ws = webSocket ?: return false
        val chatJid = extractJid(conversationId) ?: return false
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)
        val ownJid = authData?.wid ?: ""

        val optionHashes = selectedOptionNames.map { option ->
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(option.toByteArray(Charsets.UTF_8))
        }

        val node = WhatsAppProtocol.buildPollVoteMessage(
            chatJid = chatJid,
            pollMessageId = pollMessageId,
            pollSenderJid = pollSenderJid,
            optionHashes = optionHashes,
            ownJid = ownJid,
            id = id,
            pollSecret = pollSecrets[pollMessageId],
        )
        pendingMessageIDs.add(id)
        val sent = ws.send(WhatsAppProtocol.encodeNode(node))
        if (!sent) pendingMessageIDs.remove(id)
        return sent
    }

    /**
     * Send a location message.
     * From Go from-matrix.go parseGeoURI / location handling.
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
        val chatJid = extractJid(conversationId) ?: return false
        val id = WhatsAppProtocol.generateMessageId(authData?.wid)

        val node = WhatsAppProtocol.buildLocationMessage(chatJid, latitude, longitude, name, address, id)
        return ws.send(WhatsAppProtocol.encodeNode(node))
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
        // Conversation ID format: "wa:{jid}"
        return conversationId.removePrefix("wa:")
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
        return nameCache.getOrPut(jid) {
            // Extract phone number from JID for display
            // Format: "1234567890@s.whatsapp.net" or "1234567890-1234567890@g.us"
            val phone = jid.substringBefore("@").substringBefore("-")
            "+$phone"
        }
    }

    fun getContactSuggestions(query: String): List<ContactSuggestion> {
        // Return cached contacts matching query
        return nameCache.entries
            .filter { it.value.contains(query, ignoreCase = true) }
            .map { ContactSuggestion(it.value, null, null, MessageSource.WHATSAPP) }
            .take(10)
    }
}
