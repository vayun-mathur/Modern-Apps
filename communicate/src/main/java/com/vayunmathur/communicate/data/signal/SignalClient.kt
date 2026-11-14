package com.vayunmathur.communicate.data.signal

import android.content.Context
import android.util.Base64 as AndroidBase64
import com.vayunmathur.library.log.Log
import com.vayunmathur.communicate.data.signal.e2e.SignalE2E
import com.vayunmathur.communicate.data.signal.e2e.callIdentityKey
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.call.CallCapabilities
import com.vayunmathur.communicate.data.call.InAppCallController
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import com.vayunmathur.communicate.data.call.InAppCallVideoController
import com.vayunmathur.communicate.data.signal.call.IdentityKeyPairBytes
import com.vayunmathur.communicate.data.signal.call.SignalCallManager
import com.vayunmathur.communicate.data.signal.call.SignalCallSignaling
import com.vayunmathur.communicate.data.signal.call.SignalGroupCallManager
import com.vayunmathur.communicate.data.signal.call.accept
import com.vayunmathur.communicate.data.signal.call.eglBaseForGroupCalls
import com.vayunmathur.communicate.data.signal.call.eglContext
import com.vayunmathur.communicate.data.signal.call.flipCamera
import com.vayunmathur.communicate.data.signal.call.hangup
import com.vayunmathur.communicate.data.signal.call.ringRtcCallManager
import com.vayunmathur.communicate.data.signal.call.setAudioEnabled
import com.vayunmathur.communicate.data.signal.call.setScreenShareEnabled
import com.vayunmathur.communicate.data.signal.call.setVideoEnabled
import com.vayunmathur.communicate.data.signal.call.SignalCallMessage
import com.vayunmathur.communicate.data.signal.call.toContent
import com.vayunmathur.communicate.data.signal.call.toRingRtc
import com.vayunmathur.communicate.data.signal.transport.SignalAttachmentUpload
import com.vayunmathur.communicate.data.signal.transport.SignalCallingApi
import com.vayunmathur.communicate.data.signal.transport.SignalGroupsApi
import com.vayunmathur.communicate.data.signal.transport.SignalKeysApi
import com.vayunmathur.communicate.data.signal.transport.SignalPayload
import com.vayunmathur.communicate.data.signal.transport.SignalSocket
import com.vayunmathur.communicate.data.signal.transport.SignalTrust
import com.vayunmathur.communicate.telephony.InAppCallTelecom
import org.signal.libsignal.metadata.certificate.SenderCertificate
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.message.DecryptionErrorMessage
import org.signal.libsignal.protocol.message.PlaintextContent
import org.signal.ringrtc.CallManager
import org.signal.storageservice.storage.protos.groups.GroupChange
import org.webrtc.PeerConnection
import com.vayunmathur.library.network.NetworkClient
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
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketMessage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Singleton facade for the Signal primary client.
 *
 * Stable public API that repository and ui compile against — signatures are identical to the
 * foundation stub. Internals are wired to new transport/crypto/auth (SignalSocket binary
 * WebSocketMessage, SignalProtocol protobuf Envelope/Content, SignalE2E PQXDH/SealedSessionCipher,
 * dual ACI/PNI SignalAuthData).
 *
 * All chat sends go through single PUT /v1/messages/{aci} (or /multi) as encrypted Content
 * (SignalPayload.buildPutMessagesRequest + SignalSocket.sendRequestAwaitingResponse). No per-action sub-paths.
 * Receipts/typing/edit/reactions are Content peers via SignalProtocol/SignalPayload builders.
 * GroupsV2 via SignalGroups (GroupMasterKey 32B -> GroupSecretParams, PUT /v2/groups/).
 * CDSIv2 via SignalContactSync (POST https://cdsi.signal.org/v1/{mrenclave}/discovery).
 */
object SignalClient {

    internal const val TAG = "SignalClient"
    private const val PHONE_SUFFIX_LENGTH = 4

    /**
     * Production sealed-sender trust roots, mirroring the official client's
     * `UNIDENTIFIED_SENDER_TRUST_ROOTS` build constant. Two entries so the server can rotate.
     */
    internal val TRUST_ROOTS_B64 = listOf(
        "BXu6QIKVz5MA8gstzfOgRQGqyLqOwNKHL6INkv3IHWMF",
        "BUkY0I+9+oPgDCn4+Ac6Iu813yvqkDr/ga8DzLxFxuk6",
    )

    internal val ACI_REGEX =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /** Attempts allowed to reconcile a recipient's device set before a send is abandoned. */
    internal const val SEND_ATTEMPTS = 4

    /** Every Signal account has device 1; linked devices get higher ids. */
    internal const val PRIMARY_DEVICE_ID = 1

    /** Wire prefix distinguishing a phone-number identity from an ACI, which is a bare UUID. */
    internal const val PNI_PREFIX = "PNI:"

    /** Lifecycle state of the Signal line. */
    sealed interface State {
        /** Not yet connected. */
        data object Idle : State
        /** The line has no registered account yet. */
        data object NeedsSetup : State
        /** The socket is connecting / logging in. */
        data object Connecting : State
        /** Connected and logged in. */
        data object Connected : State
        /** Disconnected, with the reason. */
        data class Disconnected(val reason: String) : State
    }

    val source: SignalSource = SignalSource.SIGNAL

    fun isConnected(): Boolean = stateMutable.value is State.Connected

    internal val stateMutable = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = stateMutable.asStateFlow()

    internal val eventsMutable = MutableSharedFlow<SignalEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<SignalEvent> = eventsMutable.asSharedFlow()

    private val initialized = AtomicBoolean(false)
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal var appContext: Context? = null
    internal var authData: SignalAuthData? = null
    internal var db: SignalDatabase? = null
    internal var e2e: SignalE2E? = null
    internal var socket: SignalSocket? = null

    /** Credential-free socket used only for sealed-sender sends. */
    internal var unauthSocket: SignalSocket? = null

    /**
     * Identity keys peers are presenting that differ from the ones on record, awaiting the user's
     * decision. Deliberately in memory: if the process dies the key is re-presented on the next message,
     * and a stale pending key should not outlive the session.
     */
    internal val pendingIdentityChanges = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    internal var processor: SignalEventProcessor? = null
    internal var socketJobs: MutableList<Job> = mutableListOf()
    private var reconnectJob: Job? = null

    fun get(context: Context): SignalClient = apply { init(context) }

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        val auth = SignalAuthData.load(context.applicationContext)
        authData = auth
        try {
            db = SignalDatabase.getDatabase(context.applicationContext)
            if (auth != null) e2e = SignalE2E(db!!, auth)
        } catch (expected: Throwable) {
            Log.status(TAG, "db/e2e init failed", expected)
        }
        stateMutable.value = if (auth?.registered == true) State.Connecting else State.NeedsSetup
        try {
            val database = db
            if (database != null) {
                processor = SignalEventProcessor(database).also { it.start(events) }
            }
        } catch (expected: Throwable) {
            Log.status(TAG, "processor start failed", expected)
        }
    }

    fun start() {
        if (!initialized.get()) return
        if (stateMutable.value is State.Connected) return
        val ctx = appContext ?: return
        val auth = authData ?: SignalAuthData.load(ctx)?.also { authData = it }
        if (auth == null || !auth.registered) {
            stateMutable.value = State.NeedsSetup
            return
        }
        if (!ensureStore(ctx, auth)) return
        if (processor == null && db != null) try {
            processor = SignalEventProcessor(db!!).also { it.start(events) }
        } catch (_: Exception) {}

        stateMutable.value = State.Connecting
        // Tear down anything from a previous start(); otherwise the old instances keep their own
        // reconnect loops running with no reference left to stop them.
        disconnectSockets()
        connectSockets(ctx, auth)
    }

    /** Build the protocol store; false when unavailable (caller disconnects). */
    private fun ensureStore(ctx: android.content.Context, auth: SignalAuthData): Boolean {
        if (db == null) try { db = SignalDatabase.getDatabase(ctx) } catch (_: Exception) {}
        if (e2e == null && db != null) try {
            e2e = SignalE2E(db!!, auth) { peerAci, newKey -> reportIdentityChange(peerAci, newKey) }
        } catch (expected: Throwable) {
            Log.error(TAG, "could not build the protocol store", expected)
        }
        if (e2e == null) {
            // Connecting without a protocol store would pull messages we cannot decrypt and, since
            // they are only acked once handled, leave them cycling on the server queue.
            stateMutable.value = State.Disconnected("no protocol store")
            return false
        }
        return true
    }

    /** Connect the authenticated + sealed-sender sockets and their collectors. */
    private fun connectSockets(ctx: android.content.Context, auth: SignalAuthData) {
        val sock = SignalSocket(ctx, auth)
        socket = sock
        // Sealed-sender sends go over a second socket with no credentials. It carries no inbound queue,
        // so its frames are not collected — only its request/response pairs matter.
        val unauthSock = SignalSocket(ctx, auth, authenticated = false)
        unauthSocket = unauthSock

        socketJobs.forEach { it.cancel() }
        socketJobs.clear()

        sock.connect()
        unauthSock.connect()

        socketJobs.add(scope.launch {
            sock.connectionState.collect { cs ->
                when (cs) {
                    is SignalSocket.ConnectionState.Connected -> {
                        stateMutable.value = State.Connected
                        eventsMutable.emit(SignalEvent.StateChanged(state = SignalState.Connected))
                    }
                    is SignalSocket.ConnectionState.Connecting -> {
                        stateMutable.value = State.Connecting
                        eventsMutable.emit(SignalEvent.StateChanged(state = SignalState.Connecting))
                    }
                    is SignalSocket.ConnectionState.Disconnected -> {
                        stateMutable.value = State.Disconnected(cs.reason)
                        eventsMutable.emit(SignalEvent.StateChanged(
                            state = SignalState.Disconnected,
                            detail = cs.reason))
                    }
                }
            }
        })
        socketJobs.add(scope.launch {
            sock.messages.collect { raw ->
                handleInboundFrame(raw)
            }
        })
        // The unauthenticated socket must not drive client state, but a permanently failing one silently
        // degrades every sealed send to the authenticated path, so make that visible.
        socketJobs.add(scope.launch {
            unauthSock.connectionState.collect { cs ->
                if (cs is SignalSocket.ConnectionState.Disconnected) {
                    Log.status(TAG, "unauthenticated socket down (${cs.reason}); sealed sends will go authenticated")
                }
            }
        })
        // Our own pre-keys must be in the protocol store or inbound pre-key messages cannot decrypt
        // ("no signed pre-key <id>"). Registration only wrote them to preferences.
        scope.launch { ensureLocalPreKeys() }

        // Discover which address-book numbers are on Signal. Without this, phone-addressed conversations
        // have no ACI and cannot be sent to at all. Deliberately not in socketJobs: those are cancelled on
        // every reconnect, which would kill a discovery request mid-flight.
        scope.launch { runContactDiscovery(ctx) }
        Log.status(TAG, "start: socket connecting for ${auth.phoneNumber.takeLast(PHONE_SUFFIX_LENGTH)}" +
            " host=${SignalSocket.DEFAULT_HOST}")
    }

    fun stop() {
        if (!initialized.get()) return
        socketJobs.forEach { it.cancel() }
        socketJobs.clear()
        disconnectSockets()
        stateMutable.value = State.NeedsSetup
        scope.launch { eventsMutable.emit(SignalEvent.StateChanged(
            state = SignalState.Disconnected,
            detail = "client stop")) }
    }

    fun forceResync() {
        if (!initialized.get()) return
        socketJobs.forEach { it.cancel() }
        socketJobs.clear()
        disconnectSockets()
        start()
    }

    private fun disconnectSockets() {
        try { socket?.disconnect() } catch (_: Exception) {}
        socket = null
        try { unauthSocket?.disconnect() } catch (_: Exception) {}
        unauthSocket = null
    }

    // ---- send path (envelopeTimestampFor/groupContextFor/sendContent): see SignalSendPipeline.kt (split for file
    // length) ----

    /**
     * Encrypt [padded] for every device of [aci] we have a session with and PUT them as one request.
     * Returns false rather than falling back to plaintext: an unencrypted Content is a protocol
     * violation that real clients discard, so sending one would disclose the message and forfeit
     * sender authentication without even being delivered.
     */
    /**
     * The ACI to address on the wire for [destination], which the app may hand us as a phone number.
     *
     * Signal addresses messages by ACI, never by phone number, so an E.164 has to be resolved first. We
     * only ever learn a real ACI from an inbound message or from CDSI contact discovery, so a number
     * belonging to someone who has never messaged us cannot be resolved and the send has to fail.
     */
    /**
     * Seed our own pre-keys into the protocol store and register anything the server does not yet have.
     * Runs once per start; without it a peer's first message cannot be decrypted.
     */
    // ---- pre-keys/discovery (ensureLocalPreKeys/signedPreKeyMatchesServer): see SignalSendPipeline.kt (split for
    // file length) ----

    /** Guards against overlapping discovery runs; `start()` can fire more than once. */
    internal val discoveryRunning = AtomicBoolean(false)

    /**
     * The inbound call awaiting an answer. RingRTC reports `Ringing` for both directions, so this is what
     * distinguishes an incoming call and supplies the id [InAppCallController.answer] needs.
     */
    @Volatile
    internal var pendingIncomingCallAci: String? = null

    @Volatile
    internal var pendingIncomingCallId: Long? = null

    /** Same reason as [discoveryRunning]: without it, each start generates and uploads another batch. */
    internal val preKeysPrepared = AtomicBoolean(false)

    // ---- discovery/resolution (runContactDiscovery/resolveDestinationAci/knownServiceIdFor): see
    // SignalSendPipeline.kt (split for file length) ----

    // ---- encrypt/PUT (sendEncryptedTo/sealedSenderFor/putMessages/reconcileDevices/establishSession): see
    // SignalSendPipeline.kt (split for file length) ----

    internal sealed interface SendOutcome {
        data object Success : SendOutcome
        data class Failed(val status: Int) : SendOutcome
        data class DeviceSetChanged(val status: Int, val body: ByteArray) : SendOutcome
    }

    // ---- identity (profileKeyFrom/reportIdentityChange/pendingIdentityChange/safetyNumber/acceptIdentityChange): see
    // SignalIdentity.kt (split for file length) ----

    internal data class SealedSenderAccess(val certificate: SenderCertificate, val accessKey: ByteArray)

    // ---- sealed/PUT helpers continued: see SignalSendPipeline.kt (split for file length) ----

    /** TLS factory that trusts Signal's private service CA (chat/cdn/storage/cdsi); null before init. */
    internal fun signalTls() = appContext?.let { SignalTrust.sslSocketFactory(it) }

    internal fun basicAuthHeader(): String {
        val auth = authData ?: return ""
        val login = if (auth.aci.isNotEmpty()) "${auth.aci}.${auth.deviceId}" else auth.phoneNumber
        val password = auth.password
        val creds = "$login:$password"
        return AndroidBase64.encodeToString(creds.toByteArray(Charsets.UTF_8), AndroidBase64.NO_WRAP)
    }

    /** The stored master key for a group conversation, or null for a 1:1 or an unknown group. */
    internal suspend fun groupMasterKeyForConversation(conversationId: String): ByteArray? {
        if (!SignalProtocol.isGroupConversation(conversationId)) return null
        return try {
            db?.conversationDao()?.getConversation(conversationId)?.groupMasterKey
        } catch (_: Exception) {
            null
        }
    }

    internal suspend fun groupRevisionForConversation(conversationId: String): Int = try {
        db?.conversationDao()?.getConversation(conversationId)?.groupRevision ?: 0
    } catch (_: Exception) {
        0
    }

    // ---- Messaging (sendMessage): see SignalMessaging.kt (split for file length) ----

    /**
     * Encrypt, upload, and send an attachment. Returns null on any failure — the plaintext never leaves
     * the device unencrypted, so a failed upload simply means no message.
     */
    // ---- Messaging (sendMedia): see SignalMessaging.kt (split for file length) ----

    // ---- Inbound attachments (attachmentsFrom/attachmentUrl): see SignalInbound.kt (split for file length) ----

    // ---- Messaging (sendMediaFailed): see SignalMessaging.kt (split for file length) ----

    // ---- Messaging (sendReaction/removeReaction/editMessage/revoke/poll/sendPollVote/readReceipt/markRead): see
    // SignalMessaging.kt (split for file length) ----

    // ---- Groups (createGroup/setGroupName/groupChange/updateGroupParticipants): see SignalGroupOps.kt (split for
    // file length) ----

    // ---- Messaging (sendTyping/isLoggedIn/downloadMedia/refreshPresence): see SignalMessaging.kt (split for file
    // length) ----

    /**
     * The RingRTC bridge. Created lazily because it loads native libraries and only matters once a call
     * is actually placed or received.
     */
    internal val callManager: SignalCallManager? by lazy {
        val ctx = appContext ?: return@lazy null
        SignalCallManager(
            appContext = ctx,
            signaling = object : SignalCallSignaling {
                override suspend fun sendCallMessage(
                    aci: String,
                    deviceId: Int?,
                    message: SignalCallMessage,
                    urgent: Boolean,
                ): Boolean = sendContent(aci, message.toContent(deviceId), urgent = urgent)

                override suspend fun identityKeys(aci: String): IdentityKeyPairBytes? {
                    val e = e2e ?: return null
                    // RingRTC binds the SRTP key derivation to both identity keys, so a call cannot be
                    // set up before a session with this peer exists.
                    // Raw 32-byte keys, matching official's WebRtcUtil.getPublicKeyBytes; the serialized
                    // 33-byte form derives different SRTP keys than the peer.
                    val remote = e.callIdentityKey(aci) ?: return null
                    val local = e.ownIdentityPublicKey
                    // Logged because a size or encoding mismatch here yields SRTP keys that differ from the
                    // peer's, which looks like a connected call that never progresses.
                    Log.status(
                        TAG,
                        "call identity keys for $aci:" +
                            "local=${local.size}B(0x${"%02x".format(local.firstOrNull() ?: 0)}) " +
                            "remote=${remote.size}B(0x${"%02x".format(remote.firstOrNull() ?: 0)})",
                    )
                    return IdentityKeyPairBytes(local = local, remote = remote)
                }

                override suspend fun iceServers(): List<PeerConnection.IceServer> =
                    SignalCallingApi.fetchIceServers(basicAuthHeader(), signalTls())

                override fun onRemoteVideo(enabled: Boolean) {
                    InAppCallRegistry.onRemoteVideo(enabled)
                }

                override fun onRemoteScreenShare(enabled: Boolean) {
                    InAppCallRegistry.onRemoteScreenShare(enabled)
                }

                override fun onCallStateChanged(
                    aci: String,
                    callId: Long,
                    state: SignalCallManager.CallState,
                    isVideo: Boolean,
                ) {
                    scope.launch {
                        publishCallState(aci, state, isVideo)
                        emitCallState(callId, state, isVideo)
                    }
                }
            },
            sslSocketFactory = { signalTls() },
        )
    }

    // ---- Calls (handleCallMessage/publishCallState): see SignalCalls.kt (split for file length) ----

    /** Video capability for the shared call screen. */
    internal val signalVideoController = object : InAppCallVideoController {
        override fun setVideoEnabled(enabled: Boolean) {
            callManager?.setVideoEnabled(enabled)
        }

        override fun flipCamera() {
            callManager?.flipCamera()
        }

        override fun eglContext(): org.webrtc.EglBase.Context? = callManager?.eglContext()

        override fun attachLocalRenderer(sink: org.webrtc.VideoSink?) {
            callManager?.localVideoSink?.attach(sink)
        }

        override fun attachRemoteRenderer(sink: org.webrtc.VideoSink?) {
            callManager?.remoteVideoSink?.attach(sink)
        }

        override fun setScreenShareEnabled(enabled: Boolean, permission: android.content.Intent?) {
            callManager?.setScreenShareEnabled(enabled, permission)
        }
    }

    /** Bridges the shared call UI and the system call surface onto RingRTC. */
    internal val signalCallController = object : InAppCallController {
        override fun answer() {
            val id = pendingIncomingCallId
            if (id == null) {
                Log.status(TAG, "answer with no pending call id; the caller will keep ringing")
                return
            }
            val accepted = callManager?.accept(id)
            Log.status(TAG, "accepted call $id: $accepted")
        }

        override fun reject() {
            callManager?.hangup()
        }

        override fun hangup() {
            callManager?.hangup()
        }

        override fun setMuted(muted: Boolean) {
            // RingRTC's flag is "audio enabled", the inverse of muted.
            callManager?.setAudioEnabled(!muted)
        }

        override fun setSpeaker(on: Boolean) {
            // Routing belongs to Telecom while a self-managed connection is active; nothing to do here.
        }
    }

    // ---- Calls (emitCallState/placeCall/acceptCall/rejectCall/endCall/setCallAudioEnabled): see SignalCalls.kt
    // (split for file length) ----

    // ---- Inbound (handleInboundFrame/ackEnvelope): see SignalInbound.kt (split for file length) ----

    // ---- Inbound (processEnvelope head): see SignalInbound.kt (split for file length) ----

    // ---- Inbound (processEnvelope dispatch): see SignalInbound.kt (split for file length) ----

    // ---- Inbound (sendRetryReceipt): see SignalInbound.kt (split for file length) ----

    // ---- Identity/contacts (linkPniToAci/contactFor/displayNameFor/conversationIdFor): see SignalIdentity.kt (split
    // for file length) ----

    // ---- Groups (refreshGroup): see SignalGroupOps.kt (split for file length) ----

    /**
     * Group calling, created on demand. Shares the 1:1 manager's RingRTC instance and EGL context — RingRTC
     * keeps one factory for all group calls, so a second instance would fight it for the audio device.
     */
    internal val groupCallManager: SignalGroupCallManager? by lazy {
        val manager = callManager ?: return@lazy null
        val egl = manager.eglBaseForGroupCalls() ?: return@lazy null
        val ctx = appContext ?: return@lazy null
        SignalGroupCallManager(
            appContext = ctx,
            callManager = manager.ringRtcCallManager() ?: return@lazy null,
            eglBase = egl,
            signaling = object : SignalGroupCallManager.Signaling {
                override suspend fun membershipProof(groupId: ByteArray): ByteArray? =
                    groupAuthorization(groupId)?.let { SignalGroupsApi.fetchMembershipProof(it, signalTls()) }

                override suspend fun groupMembers(groupId: ByteArray): List<Pair<java.util.UUID, ByteArray>> {
                    val conversation = conversationForGroupId(groupId) ?: return emptyList()
                    val masterKey = conversation.groupMasterKey ?: return emptyList()
                    val secretParams = org.signal.libsignal.zkgroup.groups.GroupSecretParams.deriveFromMasterKey(
                        org.signal.libsignal.zkgroup.groups.GroupMasterKey(masterKey),
                    )
                    val acis = conversation.participants.split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    return SignalGroupsApi.groupMemberInfo(secretParams, acis)
                }

                override fun onGroupCallStateChanged(groupId: ByteArray, joined: Boolean, participants: Int) {
                    scope.launch {
                        val conversationId = SignalProtocol.groupConversationId(groupId)
                        InAppCallRegistry.onPhase(
                            if (joined) InAppCallPhase.Active else InAppCallPhase.Connecting,
                        )
                        Log.status(TAG, "group call $conversationId joined=$joined participants=$participants")
                    }
                }

                override fun onGroupCallEnded(groupId: ByteArray, reason: CallManager.CallEndReason?) {
                    scope.launch { InAppCallRegistry.onEnded(reason?.toString()) }
                }
            },
        )
    }

    // ---- Calls (placeGroupCall): see SignalCalls.kt (split for file length) ----

    // ---- Groups (groupAuthorization/conversationForGroupId): see SignalGroupOps.kt (split for file length) ----

    // ---- Messaging (sendContactCard): see SignalMessaging.kt (split for file length) ----

    // ---- Groups (rememberInboundGroup): see SignalGroupOps.kt (split for file length) ----

    // ---- Inbound (emitReadSync/emitDecryptionError/unidentifiedSenderTrustRoots/groupIdFor): see SignalInbound.kt
    // (split for file length) ----

    // ---- Messaging helper (uuidStringToBytes): see SignalMessaging.kt (split for file length) ----
}
