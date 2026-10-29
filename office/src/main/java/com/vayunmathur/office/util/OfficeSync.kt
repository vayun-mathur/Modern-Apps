@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.office.util

import kotlin.uuid.Uuid
import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.e2ee.E2ee
import com.vayunmathur.e2ee.E2eeKeyStore
import com.vayunmathur.e2ee.Pqc
import com.vayunmathur.e2ee.PqcIdentity
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.WebSocketClient
import com.vayunmathur.library.network.WsSession
import com.vayunmathur.library.network.webSocket
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

object OfficeSync {
    // Host migrated from findfamily.cc to api.maproj.org; findfamily.cc remains valid
    // for backward compatibility during the transition. Same Cloudflare FIRST_PARTY pins.
    private const val URL = "https://api.maproj.org/office"
    private const val TAG = "OfficeSync"
    private const val HTTP_OK = 200
    private val HTTP_SUCCESS = 200..299
    private const val MAX_BACKOFF_MS = 15_000L
    private const val INITIAL_BACKOFF_MS = 1000L
    internal val json = Json { ignoreUnknownKeys = true }

    internal lateinit var identity: PqcIdentity
    var deviceId: String = ""
        private set
    private var initialized = false
    private val initMutex = Mutex()

    private class DataStoreKeyStore(private val ds: DataStoreUtils) : E2eeKeyStore {
        override suspend fun getBytes(name: String): ByteArray? = ds.getByteArrayAwait(name)
        override suspend fun setBytes(name: String, value: ByteArray, onlyIfAbsent: Boolean) =
            ds.setByteArray(name, value, onlyIfAbsent)
    }

    suspend fun init(context: Context): Boolean {
        if (initialized) return true
        return initMutex.withLock {
            if (initialized) return@withLock true
            val ds = DataStoreUtils.getInstance(context)
            identity = PqcIdentity.loadOrCreate(DataStoreKeyStore(ds), "office")
            // Use suspend hydration variant to avoid cold-start empty race.
            var id = ds.getStringAwait("officeDeviceId")
            if (id == null) {
                id = Uuid.random().toString()
                ds.setString("officeDeviceId", id, true)
            }
            deviceId = ds.getStringAwait("officeDeviceId") ?: id
            val registered = register()
            if (registered) initialized = true
            registered
        }
    }

    private suspend fun register(): Boolean =
        post("/register", RegisterReq(deviceId, Base64.encode(identity.publicBundle)))

    suspend fun getKey(id: String): ByteArray? {
        val r = raw("/getkey", IdReq(id)) ?: return null
        return if (r.status == HTTP_OK) Base64.decode(r.body) else null
    }

    fun newDocumentKey(): ByteArray = E2ee.newContentKey()
    fun newDocumentId(): String = Uuid.random().toString()

    suspend fun appendDocActions(docId: String, key: ByteArray, items: List<String>): Int? {
        val blobs = items.map { Base64.encode(E2ee.aesEncrypt(key, it.encodeToByteArray())) }
        return append(docId, blobs)
    }

    suspend fun pullDocActions(docId: String, key: ByteArray, since: Int): DocActionsResult {
        val p = pull(docId, since) ?: return DocActionsResult(emptyList(), since)
        val items = p.actions.mapNotNull { b ->
            runCatching { E2ee.aesDecrypt(key, Base64.decode(b)).decodeToString() }
                .getOrNull()
        }
        return DocActionsResult(items, p.seq)
    }

    suspend fun sendInvite(
        recipientId: String,
        docId: String,
        key: ByteArray,
        title: String,
        charMode: Boolean,
        role: String,
        ownerKeyB64: String,
        charKind: String,
    ): Boolean {
        val peerBundle = getKey(recipientId) ?: return false
        val invite = json.encodeToString(
            Invite(docId, Base64.encode(key), title, charMode, role, ownerKeyB64, charKind))
        val blob = Base64.encode(Pqc.encryptTo(peerBundle, invite.encodeToByteArray()))
        return append("inbox:$recipientId", listOf(blob)) != null
    }

    /**
     * Sends a PQC-sealed join request to a document owner's inbox. Carries only public
     * ids (never the content key); the owner approves with one tap, which triggers the
     * existing seal-to-recipient [sendInvite]. Rides the same `inbox:<ownerId>` channel
     * as invites — distinguished by the `kind:"req"` discriminator (see [pullInbox]).
     */
    suspend fun sendJoinRequest(ownerId: String, docId: String, requesterId: String, name: String): Boolean {
        val ownerBundle = getKey(ownerId) ?: return false
        val req = json.encodeToString(JoinRequest(docId = docId, requesterId = requesterId, name = name))
        val blob = Base64.encode(Pqc.encryptTo(ownerBundle, req.encodeToByteArray()))
        return append("inbox:$ownerId", listOf(blob)) != null
    }

    suspend fun pullInvites(since: Int): InvitesResult {
        val inbox = pullInbox(since)
        return InvitesResult(inbox.invites, inbox.seq)
    }

    /**
     * Tolerant inbox reader: decrypts each blob and sorts it into invites (default kind,
     * for backward compatibility with existing installs) vs join requests (`kind:"req"`).
     * Unparseable/foreign blobs are skipped so one bad item never drops the batch.
     */
    suspend fun pullInbox(since: Int): InboxResult {
        val p = pull("inbox:$deviceId", since) ?: return InboxResult(emptyList(), emptyList(), since)
        val invites = mutableListOf<Invite>()
        val requests = mutableListOf<JoinRequest>()
        for (b in p.actions) {
            val plain = runCatching { identity.decrypt(Base64.decode(b)) }.getOrNull() ?: continue
            val text = plain.decodeToString()
            val kind = runCatching { json.decodeFromString<InboxEnvelope>(text).kind }.getOrNull().orEmpty()
            if (kind == "req") {
                runCatching { json.decodeFromString<JoinRequest>(text) }.getOrNull()?.let { requests.add(it) }
            } else {
                runCatching { json.decodeFromString<Invite>(text) }.getOrNull()?.let { invites.add(it) }
            }
        }
        return InboxResult(invites, requests, p.seq)
    }

    val publicBundle: ByteArray get() = identity.publicBundle

    // --- Live sync + presence over WebSocket (Android-only WebSocketClient) ---

    private const val WS_URL = "wss://api.maproj.org/office/ws"
    @Volatile private var wsSession: WsSession? = null
    private var liveJob: Job? = null
    private var liveChannel: String? = null

    fun startLive(
        scope: CoroutineScope,
        channel: String,
        onConnected: suspend () -> Unit,
        onMessage: (String) -> Unit,
    ) {
        if (liveChannel == channel && liveJob?.isActive == true) return
        stopLive()
        liveChannel = channel
        liveJob = scope.launch(Dispatchers.IO) {
            var backoff = INITIAL_BACKOFF_MS
            while (isActive) {
                runCatching {
                    webSocket(WS_URL) {
                        wsSession = this
                        send(json.encodeToString(SubMsg("sub", channel)))
                        backoff = INITIAL_BACKOFF_MS
                        runCatching { onConnected() }
                        incoming.collect { frame ->
                            when (frame) {
                                is WebSocketClient.WsFrame.Text -> onMessage(frame.text)
                                else -> Unit
                            }
                        }
                    }
                }
                wsSession = null
                if (!isActive) break
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    fun stopLive() {
        liveJob?.cancel(); liveJob = null; wsSession = null; liveChannel = null
    }

    suspend fun sendPresence(channel: String, key: ByteArray, plaintext: String) {
        val data = Base64.encode(E2ee.aesEncrypt(key, plaintext.encodeToByteArray()))
        runCatching { wsSession?.send(json.encodeToString(PresenceMsg("presence", channel, data))) }
    }

    suspend fun liveAppend(channel: String, key: ByteArray, items: List<String>): Boolean {
        val session = wsSession ?: return false
        val blobs = items.map { Base64.encode(E2ee.aesEncrypt(key, it.encodeToByteArray())) }
        return runCatching {
            session.send(json.encodeToString(AppendMsg("append", channel, blobs)))
            true
        }.getOrDefault(false)
    }

    internal suspend fun append(channel: String, blobs: List<String>): Int? {
        val r = raw("/append", AppendReq(channel, blobs)) ?: return null
        if (r.status != HTTP_OK) return null
        return runCatching { json.decodeFromString<SeqResp>(r.body).seq }.getOrNull()
    }

    internal suspend fun pull(channel: String, since: Int): PullResp? {
        val r = raw("/pull", PullReq(channel, since)) ?: return null
        if (r.status != HTTP_OK) return null
        return runCatching { json.decodeFromString<PullResp>(r.body) }.getOrNull()
    }

    private suspend inline fun <reified T> post(path: String, body: T): Boolean {
        val r = raw(path, body) ?: return false
        return r.status in HTTP_SUCCESS
    }

    private suspend inline fun <reified T> raw(path: String, body: T) =
        try {
            NetworkClient.performRequest(
                url = "$URL$path",
                method = "POST",
                headers = mapOf("Content-Type" to "application/json"),
                body = json.encodeToString(body),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (expected: Exception) {
            Log.status(TAG, "request failed: ${expected.message}")
            null
        }

    @Serializable private data class RegisterReq(val id: String, val key: String)
    @Serializable private data class IdReq(val id: String)
    @Serializable private data class AppendReq(val channel: String, val actions: List<String>)
    @Serializable private data class PullReq(val channel: String, val since: Int)
    @Serializable private data class SeqResp(val seq: Int = 0)
    @Serializable internal data class PullResp(val actions: List<String> = emptyList(), val seq: Int = 0)

    @Serializable data class DocAction(val type: String = "snapshot", val flat: String = "")
    @Serializable data class Invite(
        val docId: String,
        val key: String,
        val title: String,
        val charMode: Boolean = false,
        val role: String = "editor",
        val ownerKey: String = "",
        val charKind: String = "",
    )

    /** A request to join a document, sealed to the owner. Public ids only — no key. */
    @Serializable data class JoinRequest(
        val kind: String = "req",
        val docId: String,
        val requesterId: String,
        val name: String = "",
    )

    /** Peeks the discriminator on an inbox blob so invites and requests can share the channel. */
    @Serializable private data class InboxEnvelope(val kind: String = "")

    class InvitesResult(val invites: List<Invite>, val seq: Int)
    class InboxResult(val invites: List<Invite>, val requests: List<JoinRequest>, val seq: Int)
    class DocActionsResult(val items: List<String>, val seq: Int)

    @Serializable private data class SubMsg(val t: String, val channel: String)
    @Serializable private data class PresenceMsg(val t: String, val channel: String, val data: String)
    @Serializable private data class AppendMsg(val t: String, val channel: String, val actions: List<String>)

    @Serializable internal data class LiveMsg(
        val t: String = "",
        val channel: String = "",
        val actions: List<String> = emptyList(),
        val seq: Int = 0,
        val data: String = "",
    )

    /** Parses an incoming live-channel message, or null when malformed. */
    internal fun parseLive(raw: String): LiveMsg? = runCatching { json.decodeFromString<LiveMsg>(raw) }.getOrNull()
}
