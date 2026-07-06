package com.vayunmathur.office.util

import android.content.Context
import com.vayunmathur.e2ee.E2ee
import com.vayunmathur.e2ee.E2eeKeyStore
import com.vayunmathur.e2ee.Pqc
import com.vayunmathur.e2ee.PqcIdentity
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.util.DataStoreUtils
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
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
import java.util.UUID
import kotlin.io.encoding.Base64

/**
 * Client for the Office **pure-relay** server. The server only stores a key directory and an
 * append-only log of opaque encrypted "action" blobs per channel; ALL intelligence is here.
 *
 * Channels:
 *  - a **document** is a channel keyed by its opaque doc id; its actions are snapshots encrypted
 *    with the document's symmetric content key (shared only with members);
 *  - a **device inbox** is the channel `inbox:<deviceId>`; a "share" is an [Invite] action encrypted
 *    to that device's public key (carrying the doc id + content key + title) so a new member learns
 *    what they need without the server knowing anything.
 *
 * All key generation/crypto comes from `:library:e2ee-p2p`, identical to FindFamily.
 */
object OfficeSync {
    private const val URL = "https://findfamily.cc/office"
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var identity: PqcIdentity
    var deviceId: String = ""
        private set
    private var initialized = false
    private val initMutex = Mutex()

    private class DataStoreKeyStore(private val ds: DataStoreUtils) : E2eeKeyStore {
        override fun getBytes(name: String): ByteArray? = ds.getByteArray(name)
        override suspend fun setBytes(name: String, value: ByteArray, onlyIfAbsent: Boolean) =
            ds.setByteArray(name, value, onlyIfAbsent)
    }

    /** Loads/creates this device's identity + id and registers the public key in the directory. */
    suspend fun init(context: Context) {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            val ds = DataStoreUtils.getInstance(context)
            identity = PqcIdentity.loadOrCreate(DataStoreKeyStore(ds), "office")
            var id = ds.getString("officeDeviceId")
            if (id == null) {
                id = UUID.randomUUID().toString()
                ds.setString("officeDeviceId", id, true)
            }
            deviceId = ds.getString("officeDeviceId") ?: id
            register()
            initialized = true
        }
    }

    private suspend fun register(): Boolean =
        post("/register", RegisterReq(deviceId, Base64.encode(identity.publicBundle)))

    /** Fetches a peer's public key bundle by id from the directory. */
    suspend fun getKey(id: String): ByteArray? {
        val r = raw("/getkey", IdReq(id)) ?: return null
        return if (r.status == 200) Base64.decode(r.body) else null
    }

    /** A fresh random AES content key for a new document. */
    fun newDocumentKey(): ByteArray = E2ee.newContentKey()

    /** A fresh opaque document id. */
    fun newDocumentId(): String = UUID.randomUUID().toString()

    // --- Document channel (a log of AES-encrypted CRDT action batches) ---

    /** Encrypts and appends CRDT action batches to a document's log; returns the new cursor. */
    suspend fun appendDocActions(docId: String, key: ByteArray, items: List<String>): Int? {
        val blobs = items.map { Base64.encode(E2ee.aesEncrypt(key, it.encodeToByteArray())) }
        return append(docId, blobs)
    }

    /** Pulls + decrypts document action batches at/after [since]; returns items + new cursor. */
    suspend fun pullDocActions(docId: String, key: ByteArray, since: Int): DocActionsResult {
        val p = pull(docId, since) ?: return DocActionsResult(emptyList(), since)
        val items = p.actions.mapNotNull { b ->
            runCatching { E2ee.aesDecrypt(key, Base64.decode(b)).decodeToString() }.getOrNull()
        }
        return DocActionsResult(items, p.seq)
    }

    // --- Inbox channel (invites encrypted to the recipient's public key) ---

    /** Shares a document by dropping an encrypted invite into the recipient's inbox channel. */
    suspend fun sendInvite(recipientId: String, docId: String, key: ByteArray, title: String, charMode: Boolean, role: String, ownerKeyB64: String): Boolean {
        val peerBundle = getKey(recipientId) ?: return false
        val invite = json.encodeToString(Invite(docId, Base64.encode(key), title, charMode, role, ownerKeyB64))
        val blob = Base64.encode(Pqc.encryptTo(peerBundle, invite.encodeToByteArray()))
        return append("inbox:$recipientId", listOf(blob)) != null
    }

    /** Drains new invites from this device's inbox at/after [since]; returns invites + new cursor. */
    suspend fun pullInvites(since: Int): InvitesResult {
        val p = pull("inbox:$deviceId", since) ?: return InvitesResult(emptyList(), since)
        val invites = p.actions.mapNotNull { b ->
            val plain = runCatching { identity.decrypt(Base64.decode(b)) }.getOrNull() ?: return@mapNotNull null
            runCatching { json.decodeFromString<Invite>(plain.decodeToString()) }.getOrNull()
        }
        return InvitesResult(invites, p.seq)
    }

    /** Verification security code with a peer, given their public key bundle (compare out-of-band). */
    suspend fun securityCode(peerBundle: ByteArray): String? =
        runCatching { Pqc.securityCode(identity.publicBundle, peerBundle) }.getOrNull()

    /** This device's public key bundle (ML-KEM + ML-DSA), e.g. to record as a document owner. */
    val publicBundle: ByteArray get() = identity.publicBundle

    /** Signs [data] with this device's ML-DSA key (authenticates op/roster authorship). */
    suspend fun sign(data: ByteArray): ByteArray = identity.sign(data)

    /** Verifies a signature against a public key bundle. */
    suspend fun verify(publicBundle: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        Pqc.verify(publicBundle, data, signature)

    // --- Live sync + presence over WebSocket (receive live; send presence) ---

    private const val WS_URL = "wss://findfamily.cc/office/ws"
    private val wsClient by lazy { HttpClient(CIO) { install(WebSockets) } }
    @Volatile private var wsSession: DefaultClientWebSocketSession? = null
    private var liveJob: Job? = null
    private var liveChannel: String? = null

    /**
     * Opens a live subscription to [channel] with **automatic reconnect** (exponential backoff).
     * [onConnected] runs after each (re)subscribe so the caller can HTTP-pull anything missed while
     * disconnected; [onMessage] receives each server message (raw JSON; parse with [parseLive]).
     */
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
            var backoff = 1000L
            while (isActive) {
                runCatching {
                    wsClient.webSocket(urlString = WS_URL) {
                        wsSession = this
                        send(Frame.Text(json.encodeToString(SubMsg("sub", channel))))
                        backoff = 1000L
                        runCatching { onConnected() } // catch up on anything missed
                        for (frame in incoming) {
                            if (frame is Frame.Text) onMessage(frame.readText())
                        }
                    }
                }
                wsSession = null
                if (!isActive) break
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(15_000)
            }
        }
    }

    fun stopLive() {
        liveJob?.cancel(); liveJob = null; wsSession = null; liveChannel = null
    }

    /** Sends ephemeral presence (encrypted with the doc key) over the live socket, if connected. */
    suspend fun sendPresence(channel: String, key: ByteArray, plaintext: String) {
        val data = Base64.encode(E2ee.aesEncrypt(key, plaintext.encodeToByteArray()))
        runCatching { wsSession?.send(Frame.Text(json.encodeToString(PresenceMsg("presence", channel, data)))) }
    }

    /** Parses a raw live message. */
    fun parseLive(raw: String): LiveMsg? = runCatching { json.decodeFromString<LiveMsg>(raw) }.getOrNull()

    /** Decrypts one AES blob with the doc key (for actions or presence data). */
    fun decrypt(key: ByteArray, b64: String): String? =
        runCatching { E2ee.aesDecrypt(key, Base64.decode(b64)).decodeToString() }.getOrNull()

    // --- Generic relay primitives ---

    private suspend fun append(channel: String, blobs: List<String>): Int? {
        val r = raw("/append", AppendReq(channel, blobs)) ?: return null
        if (r.status != 200) return null
        return runCatching { json.decodeFromString<SeqResp>(r.body).seq }.getOrNull()
    }

    private suspend fun pull(channel: String, since: Int): PullResp? {
        val r = raw("/pull", PullReq(channel, since)) ?: return null
        if (r.status != 200) return null
        return runCatching { json.decodeFromString<PullResp>(r.body) }.getOrNull()
    }

    private suspend inline fun <reified T> post(path: String, body: T): Boolean {
        val r = raw(path, body) ?: return false
        return r.status in 200..299
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
        } catch (e: Exception) {
            null
        }

    @Serializable private data class RegisterReq(val id: String, val key: String)
    @Serializable private data class IdReq(val id: String)
    @Serializable private data class AppendReq(val channel: String, val actions: List<String>)
    @Serializable private data class PullReq(val channel: String, val since: Int)
    @Serializable private data class SeqResp(val seq: Int = 0)
    @Serializable private data class PullResp(val actions: List<String> = emptyList(), val seq: Int = 0)

    /** A document action (currently just a full snapshot; extensible via [type]). */
    @Serializable data class DocAction(val type: String = "snapshot", val flat: String = "")

    /** An invite delivered via a device's inbox channel: everything a new member needs. */
    @Serializable data class Invite(
        val docId: String,
        val key: String,
        val title: String,
        val charMode: Boolean = false,
        val role: String = "editor",
        val ownerKey: String = "",
    )

    class InvitesResult(val invites: List<Invite>, val seq: Int)
    class DocActionsResult(val items: List<String>, val seq: Int)

    @Serializable private data class SubMsg(val t: String, val channel: String)
    @Serializable private data class PresenceMsg(val t: String, val channel: String, val data: String)

    /** A live server message: `t` = "actions" (with [actions]+[seq]) or "presence" (with [data]). */
    @Serializable data class LiveMsg(
        val t: String = "",
        val channel: String = "",
        val actions: List<String> = emptyList(),
        val seq: Int = 0,
        val data: String = "",
    )
}
