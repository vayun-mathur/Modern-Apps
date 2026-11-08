@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.games.voxels.network

import com.vayunmathur.library.log.Log
import kotlin.uuid.Uuid
import android.content.Context
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

/**
 * Encrypted relay client for online voxel worlds — the transport half of multiplayer.
 *
 * This is the voxels twin of [com.vayunmathur.office.util.OfficeSync]: same untrusted-relay model
 * (a dumb append-only log keyed by string channels over `/register|/getkey|/append|/pull` plus a
 * WebSocket), same PQC identity + AES-256-GCM content encryption. All confidentiality, authenticity
 * and authorization are enforced client-side; the relay only ever sees ciphertext.
 *
 * Channels used per world:
 *  - `world:<id>`     — the authoritative op/snapshot log (host writes snapshots, clients write intents)
 *  - `inbox:<device>` — PQC-sealed invites addressed to a specific device
 *  - `members:<id>`   — the owner-signed roster (who may join / edit)
 *
 * Player transforms ride the ephemeral WebSocket `presence` channel and are never persisted.
 */
// Sync protocol surface: one function per endpoint/operation by design.
@Suppress("TooManyFunctions")
object VoxelsSync {
    private const val URL = "https://api.maproj.org/voxels"
    private const val WS_URL = "wss://api.maproj.org/voxels/ws"
    private const val HTTP_OK = 200
    private const val RECONNECT_INITIAL_MS = 1000L
    private const val RECONNECT_MAX_MS = 15_000
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var identity: PqcIdentity
    var deviceId: String = ""
        private set
    private var initialized = false
    private val initMutex = Mutex()

    private class DataStoreKeyStore(private val ds: DataStoreUtils) : E2eeKeyStore {
        override suspend fun getBytes(name: String): ByteArray? = ds.getByteArrayAwait(name)
        override suspend fun setBytes(name: String, value: ByteArray, onlyIfAbsent: Boolean) =
            ds.setByteArray(name, value, onlyIfAbsent)
    }

    /** Idempotent: loads/creates this device's PQC identity + stable id and registers its public bundle. */
    suspend fun init(context: Context): Boolean {
        if (initialized) return true
        return initMutex.withLock {
            if (initialized) return@withLock true
            val ds = DataStoreUtils.getInstance(context)
            identity = PqcIdentity.loadOrCreate(DataStoreKeyStore(ds), "voxels")
            var id = ds.getStringAwait("voxelsDeviceId")
            if (id == null) {
                id = Uuid.random().toString()
                ds.setString("voxelsDeviceId", id, true)
            }
            deviceId = ds.getStringAwait("voxelsDeviceId") ?: id
            val registered = register()
            if (registered) initialized = true
            registered
        }
    }

    private suspend fun register(): Boolean =
        post("/register", RegisterReq(deviceId, Base64.encode(identity.publicBundle)))

    /** Fetches a peer's public bundle by device id (needed to seal invites / verify their signatures). */
    suspend fun getKey(id: String): ByteArray? {
        val r = raw("/getkey", IdReq(id)) ?: return null
        return if (r.status == HTTP_OK) Base64.decode(r.body) else null
    }

    fun newWorldKey(): ByteArray = E2ee.newContentKey()
    fun newWorldId(): String = Uuid.random().toString()

    // --- World op/snapshot log (AES-encrypted string ops, mirroring office doc actions) ---

    /** Appends AES-encrypted op/snapshot strings to a world's log; returns the new sequence, or null. */
    suspend fun appendWorldOps(worldId: String, key: ByteArray, items: List<String>): Int? {
        val blobs = items.map { Base64.encode(E2ee.aesEncrypt(key, it.encodeToByteArray())) }
        return append("world:$worldId", blobs)
    }

    /** Pulls + decrypts a world's op/snapshot log from [since]; undecryptable entries are skipped. */
    suspend fun pullWorldOps(worldId: String, key: ByteArray, since: Int): OpsResult {
        val p = pull("world:$worldId", since) ?: return OpsResult(emptyList(), since)
        val items = p.actions.mapNotNull { b ->
            runCatching { E2ee.aesDecrypt(key, Base64.decode(b)).decodeToString() }.getOrNull()
        }
        return OpsResult(items, p.seq)
    }

    // --- Invites (PQC-sealed to the recipient's inbox) ---

    /** Seals a world invite to [recipientId]'s inbox (carries name + seed + AES key + owner bundle). */
    suspend fun sendInvite(
        recipientId: String,
        worldId: String,
        key: ByteArray,
        name: String,
        seed: Int,
        role: String,
        ownerKeyB64: String,
        ownerDeviceId: String,
    ): Boolean {
        val peerBundle = getKey(recipientId) ?: return false
        val invite = json.encodeToString(
            Invite(worldId, Base64.encode(key), name, seed, role, ownerKeyB64, ownerDeviceId)
        )
        val blob = Base64.encode(Pqc.encryptTo(peerBundle, invite.encodeToByteArray()))
        return append("inbox:$recipientId", listOf(blob)) != null
    }

    /** Pulls + unseals invites addressed to this device from [since]. */
    suspend fun pullInvites(since: Int): InvitesResult {
        val inbox = pullInbox(since)
        return InvitesResult(inbox.invites, inbox.seq)
    }

    /**
     * Sends a PQC-sealed join request to a world owner's inbox. Carries only public ids
     * (never the AES world key); the host approves with one tap, which triggers the
     * existing seal-to-recipient [sendInvite]. Rides the same `inbox:<ownerId>` channel
     * as invites — distinguished by the `kind:"req"` discriminator (see [pullInbox]).
     */
    suspend fun sendJoinRequest(ownerId: String, worldId: String, requesterId: String, name: String): Boolean {
        val ownerBundle = getKey(ownerId) ?: return false
        val req = json.encodeToString(JoinRequest(worldId = worldId, requesterId = requesterId, name = name))
        val blob = Base64.encode(Pqc.encryptTo(ownerBundle, req.encodeToByteArray()))
        return append("inbox:$ownerId", listOf(blob)) != null
    }

    /**
     * Tolerant inbox reader: decrypts each blob and sorts it into invites (default kind,
     * for backward compatibility) vs join requests (`kind:"req"`). Unparseable/foreign
     * blobs are skipped so one bad item never drops the batch.
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

    // --- Owner-signed roster on the (world-key-encrypted) members channel ---

    /** Canonical bytes an owner signs for a member record (binds id + role to the world). */
    fun memberSigningBytes(worldId: String, m: Member): ByteArray =
        "$worldId|${m.id}|${m.role}".encodeToByteArray()

    /** Appends AES-encrypted member records to `members:<worldId>` (owner writes these). */
    suspend fun appendMembers(worldId: String, key: ByteArray, items: List<String>): Int? {
        val blobs = items.map { Base64.encode(E2ee.aesEncrypt(key, it.encodeToByteArray())) }
        return append("members:$worldId", blobs)
    }

    /** Pulls + decrypts all member records for a world (verification is the caller's job). */
    suspend fun pullMembers(worldId: String, key: ByteArray): List<String> {
        val p = pull("members:$worldId", 0) ?: return emptyList()
        return p.actions.mapNotNull { b ->
            runCatching { E2ee.aesDecrypt(key, Base64.decode(b)).decodeToString() }.getOrNull()
        }
    }

    /** Owner-signs + records a set of member records (owner only; enforced by whoever holds the key). */
    suspend fun recordMembers(worldId: String, key: ByteArray, members: List<Member>): Boolean {
        val items = members.map { m ->
            val sig = Base64.encode(sign(memberSigningBytes(worldId, m)))
            json.encodeToString(SignedMember(m, sig))
        }
        return appendMembers(worldId, key, items) != null
    }

    /** Reads + verifies the roster: only records with a valid OWNER signature are honored. Returns
     *  device id → role. */
    suspend fun fetchRoster(worldId: String, key: ByteArray, ownerBundle: ByteArray): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (item in pullMembers(worldId, key)) {
            val sm = runCatching { json.decodeFromString<SignedMember>(item) }.getOrNull() ?: continue
            if (verify(ownerBundle, memberSigningBytes(worldId, sm.member), Base64.decode(sm.sig))) {
                map[sm.member.id] = sm.member.role
            }
        }
        return map
    }

    // --- Identity / signing helpers (roster + signed-op authorization live in the consumer) ---

    suspend fun securityCode(peerBundle: ByteArray): String? =
        runCatching { Pqc.securityCode(identity.publicBundle, peerBundle) }.getOrNull()

    val publicBundle: ByteArray get() = identity.publicBundle
    suspend fun sign(data: ByteArray): ByteArray = identity.sign(data)
    suspend fun verify(publicBundle: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        Pqc.verify(publicBundle, data, signature)
    suspend fun seal(bundle: ByteArray, data: ByteArray): ByteArray = Pqc.encryptTo(bundle, data)
    suspend fun unseal(data: ByteArray): ByteArray = identity.decrypt(data)
    suspend fun appendRaw(channel: String, items: List<String>): Int? = append(channel, items)
    suspend fun pullRaw(channel: String, since: Int): List<String> = pull(channel, since)?.actions ?: emptyList()

    // --- Live sync + presence over WebSocket ---

    @Volatile private var wsSession: WsSession? = null
    private var liveJob: Job? = null
    private var liveChannel: String? = null

    /** Subscribes to [channel] over the relay WebSocket with exponential-backoff reconnects. */
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
            var backoff = RECONNECT_INITIAL_MS
            while (isActive) {
                runCatching {
                    webSocket(WS_URL) {
                        wsSession = this
                        send(json.encodeToString(SubMsg("sub", channel)))
                        backoff = RECONNECT_INITIAL_MS
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
                backoff = (backoff * 2).coerceAtMost(RECONNECT_MAX_MS.toLong())
            }
        }
    }

    fun stopLive() {
        liveJob?.cancel(); liveJob = null; wsSession = null; liveChannel = null
    }

    /** True while the live WebSocket is connected — the host-reachability signal for the join gate. */
    val isLive: Boolean get() = wsSession != null

    /** Broadcasts an AES-encrypted ephemeral presence frame (player transform); never persisted. */
    suspend fun sendPresence(channel: String, key: ByteArray, plaintext: String) {
        val data = Base64.encode(E2ee.aesEncrypt(key, plaintext.encodeToByteArray()))
        runCatching { wsSession?.send(json.encodeToString(PresenceMsg("presence", channel, data))) }
    }

    /** Appends AES-encrypted ops over the live socket (instant fan-out); false if not connected. */
    suspend fun liveAppend(channel: String, key: ByteArray, items: List<String>): Boolean {
        val session = wsSession ?: return false
        val blobs = items.map { Base64.encode(E2ee.aesEncrypt(key, it.encodeToByteArray())) }
        return runCatching {
            session.send(json.encodeToString(AppendMsg("append", channel, blobs)))
            true
        }.getOrDefault(false)
    }

    fun parseLive(raw: String): LiveMsg? = runCatching { json.decodeFromString<LiveMsg>(raw) }.getOrNull()
    fun decrypt(key: ByteArray, b64: String): String? =
        runCatching { E2ee.aesDecrypt(key, Base64.decode(b64)).decodeToString() }.getOrNull()

    // --- REST plumbing (identical relay contract to office) ---

    private suspend fun append(channel: String, blobs: List<String>): Int? {
        val r = raw("/append", AppendReq(channel, blobs)) ?: return null
        if (r.status != HTTP_OK) return null
        return runCatching { json.decodeFromString<SeqResp>(r.body).seq }.getOrNull()
    }

    private suspend fun pull(channel: String, since: Int): PullResp? {
        val r = raw("/pull", PullReq(channel, since)) ?: return null
        if (r.status != HTTP_OK) return null
        return runCatching { json.decodeFromString<PullResp>(r.body) }.getOrNull()
    }

    private suspend inline fun <reified T> post(path: String, body: T): Boolean {
        val r = raw(path, body) ?: return false
        return r.isSuccess
    }

    // Broad catch is deliberate: any transport/serialization failure mode means
    // "no response" per this function's contract (see comment in the catch below).
    @Suppress("TooGenericExceptionCaught")
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
            // Best-effort poll: any failure mode means "no response" and the
            // next tick retries. Cancellation is rethrown above so this never
            // swallows structured-concurrency signals.
            Log.status("VoxelsSync", "request failed: $path", e)
            null
        }

    @Serializable private data class RegisterReq(val id: String, val key: String)
    @Serializable private data class IdReq(val id: String)
    @Serializable private data class AppendReq(val channel: String, val actions: List<String>)
    @Serializable private data class PullReq(val channel: String, val since: Int)
    @Serializable private data class SeqResp(val seq: Int = 0)
    @Serializable private data class PullResp(val actions: List<String> = emptyList(), val seq: Int = 0)

    /** An invite to a shared world: carries everything a client needs to open it. */
    @Serializable data class Invite(
        val worldId: String,
        val key: String,
        val name: String,
        val seed: Int = 0,
        val role: String = VoxelsRoles.EDITOR,
        val ownerKey: String = "",
        val ownerDeviceId: String = "",
    )

    /** A request to join a world, sealed to the owner. Public ids only — no key. */
    @Serializable data class JoinRequest(
        val kind: String = "req",
        val worldId: String,
        val requesterId: String,
        val name: String = "",
    )

    /** Peeks the discriminator on an inbox blob so invites and requests can share the channel. */
    @Serializable private data class InboxEnvelope(val kind: String = "")

    /** An author-signed op/snapshot batch: author id, signature over [ops], and the ops JSON. */
    @Serializable data class SignedOp(val author: String, val sig: String, val ops: String)

    /** A world member + their role, distributed as owner-signed records on the members channel. */
    @Serializable data class Member(val id: String, val name: String = "", val role: String = VoxelsRoles.EDITOR)

    /** An owner-signed member record (only records with a valid owner signature are honored). */
    @Serializable data class SignedMember(val member: Member, val sig: String)

    class InvitesResult(val invites: List<Invite>, val seq: Int)
    class InboxResult(val invites: List<Invite>, val requests: List<JoinRequest>, val seq: Int)
    class OpsResult(val items: List<String>, val seq: Int)

    @Serializable private data class SubMsg(val t: String, val channel: String)
    @Serializable private data class PresenceMsg(val t: String, val channel: String, val data: String)
    @Serializable private data class AppendMsg(val t: String, val channel: String, val actions: List<String>)

    @Serializable data class LiveMsg(
        val t: String = "",
        val channel: String = "",
        val actions: List<String> = emptyList(),
        val seq: Int = 0,
        val data: String = "",
    )
}

/** World access roles — enforced entirely client-side via signature checks (relay is a pure log).
 *  Voxels worlds have no viewers: the owner (host) and invited editors can all build. */
object VoxelsRoles {
    const val OWNER = "owner"
    const val EDITOR = "editor"
    fun canEdit(role: String) = role == OWNER || role == EDITOR
}
