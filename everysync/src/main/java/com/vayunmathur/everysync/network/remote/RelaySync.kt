@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.everysync.network.remote

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
 * Encrypted relay client for personal vaults — the transport half of unified E2EE sync.
 *
 * Third twin of office `OfficeSync` and voxels `VoxelsSync`: same untrusted-relay model
 * (a dumb append-only log keyed by string channels over `/register|/getkey|/append|/pull`
 * plus a WebSocket), same PQC identity + AES-256-GCM content encryption. All
 * confidentiality, authenticity and authorization are enforced client-side; the relay
 * only ever sees ciphertext. Host migrated from findfamily.cc to api.maproj.org;
 * findfamily.cc remains valid for backward compatibility during the transition.
 *
 * Channels per vault:
 *  - `contacts:<vaultId>`, `calendar:<vaultId>`, `passwords:<id>`, `notes:<id>`
 *    — the authoritative record log (AES-encrypted envelopes)
 *  - `inbox:<device>` — PQC-sealed invites addressed to a specific device
 *  - `members:<vaultId>` — the owner-signed roster (who may read / write)
 *  - `keys:<vaultId>` — owner-signed key epochs (read-revocation)
 *  - `owner:<vaultId>` — owner-signed ownership transfers
 *  - `title:<vaultId>` — owner-signed vault title
 *
 * Presence is not used here (unlike office/voxels): vault sync is WorkManager-driven
 * with push-on-edit, so there is no ephemeral live channel to keep open.
 *
 * Key hygiene invariant: vault keys are never user-visible. Share links carry
 * only vault/owner ids; keys move exclusively via PQC-sealed inbox invites, and
 * rest encrypted at rest (see `TokenStore` / `VaultStore`). Roster, key epochs,
 * ownership and titles are owner-signed; record envelopes are encryption-only.
 * TRIGGER: if any future feature surfaces a vault key to the user (export, QR,
 * manual entry), record envelopes must gain a findfamily `PairAuth`-style
 * per-sender secret id (pinned trust-on-first-use, directional, never logged)
 * before that feature ships.
 *
 * Loss-prevention invariant: a failed pull is NEVER an empty log. `pullRecords`
 * and `pullRaw` return null on transport failure so the merge driver can abort
 * the cycle instead of reading absence as deletion (see `RelayMerge`).
 */
// Transport surface: one function per endpoint/operation by design.
@Suppress("TooManyFunctions")
object RelaySync {
    private const val URL = "https://api.maproj.org/personal"
    private const val WS_URL = "wss://api.maproj.org/personal/ws"
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
            identity = PqcIdentity.loadOrCreate(DataStoreKeyStore(ds), "personal")
            var id = ds.getStringAwait("personalDeviceId")
            if (id == null) {
                id = Uuid.random().toString()
                ds.setString("personalDeviceId", id, true)
            }
            deviceId = ds.getStringAwait("personalDeviceId") ?: id
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

    fun newVaultId(): String = Uuid.random().toString()
    fun newVaultKey(): ByteArray = E2ee.newContentKey()

    /** Channel name for a vault's record log, e.g. `passwords:<vaultId>`. */
    fun recordChannel(dataType: String, vaultId: String): String = "$dataType:$vaultId"

    // --- Record log (AES-encrypted string envelopes, mirroring office doc actions) ---

    /** Appends AES-encrypted envelopes to a vault's record log; returns the new sequence, or null. */
    suspend fun appendRecords(channel: String, key: ByteArray, items: List<String>): Int? {
        val blobs = items.map { Base64.encode(E2ee.aesEncrypt(key, it.encodeToByteArray())) }
        return append(channel, blobs)
    }

    /** Pulls + decrypts a vault's record log from [since]; null on transport failure.
     *
     * A null return is NOT an empty log: the caller must abort the cycle. Folding
     * a failure as empty would read every baseline-present id as deleted (see
     * `RelayMerge`). [rawCount] is how many envelopes the relay holds;
     * [items.size] is how many decrypted under [key] — a large gap means a
     * key/epoch problem, not an empty vault.
     */
    suspend fun pullRecords(channel: String, key: ByteArray, since: Int): RecordsResult? {
        val p = pull(channel, since) ?: return null
        var undecryptable = 0
        val items = p.actions.mapNotNull { b ->
            runCatching { E2ee.aesDecrypt(key, Base64.decode(b)).decodeToString() }.getOrNull()
                ?: run { undecryptable++; null }
        }
        return RecordsResult(items, p.seq, rawCount = p.actions.size, undecryptable = undecryptable)
    }

    // --- Invites (PQC-sealed to the recipient's inbox) ---

    /**
     * Seals a vault invite to [recipientId]'s inbox. Carries the vault id, the AES vault
     * key, the data type, role, and owner identity — everything a device needs to join.
     */
    suspend fun sendInvite(
        recipientId: String,
        vaultId: String,
        dataType: String,
        key: ByteArray,
        title: String,
        role: String,
        ownerKeyB64: String,
        ownerDeviceId: String,
        epoch: Int = 0,
    ): Boolean {
        val peerBundle = getKey(recipientId) ?: return false
        val invite = json.encodeToString(
            Invite(vaultId, Base64.encode(key), title, dataType, role, ownerKeyB64, ownerDeviceId, epoch)
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
     * Sends a PQC-sealed join request to a vault owner's inbox. Carries only public ids
     * (never the AES vault key); the owner approves with one tap, which triggers the
     * existing seal-to-recipient [sendInvite]. Rides the same `inbox:<ownerId>` channel
     * as invites — distinguished by the `kind:"req"` discriminator (see [pullInbox]).
     */
    suspend fun sendJoinRequest(
        ownerId: String,
        vaultId: String,
        dataType: String,
        requesterId: String,
        name: String,
    ): Boolean {
        val ownerBundle = getKey(ownerId) ?: return false
        val req = json.encodeToString(
            JoinRequest(vaultId = vaultId, dataType = dataType, requesterId = requesterId, name = name)
        )
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

    // --- Identity / signing helpers (roster + signed-envelope auth lives in the consumer) ---

    suspend fun securityCode(peerBundle: ByteArray): String? =
        runCatching { Pqc.securityCode(identity.publicBundle, peerBundle) }.getOrNull()

    val publicBundle: ByteArray get() = identity.publicBundle
    suspend fun sign(data: ByteArray): ByteArray = identity.sign(data)
    suspend fun verify(publicBundle: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        Pqc.verify(publicBundle, data, signature)
    suspend fun seal(bundle: ByteArray, data: ByteArray): ByteArray = Pqc.encryptTo(bundle, data)
    suspend fun unseal(data: ByteArray): ByteArray = identity.decrypt(data)
    suspend fun appendRaw(channel: String, items: List<String>): Int? = append(channel, items)
    /** Raw pull; null on transport failure (NOT an empty channel — abort, don't merge). */
    suspend fun pullRaw(channel: String, since: Int): List<String>? = pull(channel, since)?.actions
    fun decrypt(key: ByteArray, b64: String): String? =
        runCatching { E2ee.aesDecrypt(key, Base64.decode(b64)).decodeToString() }.getOrNull()

    // --- Live channel (optional push; vault sync is WorkManager-driven, WS is best-effort) ---

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

    /** True while the live WebSocket is connected. */
    val isLive: Boolean get() = wsSession != null

    /** Appends AES-encrypted envelopes over the live socket (instant fan-out); false if not connected. */
    suspend fun liveAppend(channel: String, key: ByteArray, items: List<String>): Boolean {
        val session = wsSession ?: return false
        val blobs = items.map { Base64.encode(E2ee.aesEncrypt(key, it.encodeToByteArray())) }
        return runCatching {
            session.send(json.encodeToString(AppendMsg("append", channel, blobs)))
            true
        }.getOrDefault(false)
    }

    fun parseLive(raw: String): LiveMsg? = runCatching { json.decodeFromString<LiveMsg>(raw) }.getOrNull()

    // --- REST plumbing (identical relay contract to office/voxels) ---

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
    // "no response" per this function's contract. Cancellation is rethrown so this
    // never swallows structured-concurrency signals.
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
            Log.status("RelaySync", "request failed: $path", e)
            null
        }

    @Serializable private data class RegisterReq(val id: String, val key: String)
    @Serializable private data class IdReq(val id: String)
    @Serializable private data class AppendReq(val channel: String, val actions: List<String>)
    @Serializable private data class PullReq(val channel: String, val since: Int)
    @Serializable private data class SeqResp(val seq: Int = 0)
    @Serializable private data class PullResp(val actions: List<String> = emptyList(), val seq: Int = 0)

    /**
     * An invite to a shared vault: carries everything a device needs to join it.
     * Sealed with the recipient's PQC bundle; the relay only sees ciphertext.
     */
    @Serializable data class Invite(
        val vaultId: String,
        val key: String,
        val title: String,
        val dataType: String = "",
        val role: String = RelayRoles.EDITOR,
        val ownerKey: String = "",
        val ownerDeviceId: String = "",
        val epoch: Int = 0,
    )

    /** A request to join a vault, sealed to the owner. Public ids only — no key. */
    @Serializable data class JoinRequest(
        val kind: String = "req",
        val vaultId: String,
        val dataType: String = "",
        val requesterId: String,
        val name: String = "",
    )

    /** Peeks the discriminator on an inbox blob so invites and requests can share the channel. */
    @Serializable private data class InboxEnvelope(val kind: String = "")

    /** An author-signed envelope batch: author id, signature over [payload], and the payload JSON. */
    @Serializable data class SignedEnvelope(val author: String, val sig: String, val payload: String)

    /** A vault member + their role, distributed as owner-signed records on the members channel. */
    @Serializable data class Member(val id: String, val name: String = "", val role: String = RelayRoles.EDITOR)

    /** An owner-signed member record (only records with a valid owner signature are honored). */
    @Serializable data class SignedMember(val member: Member, val sig: String)

    /** An owner-signed key epoch for cryptographic read-revocation (mirrors office KeyEpoch). */
    @Serializable data class KeyEpoch(val epoch: Int, val wraps: Map<String, String>, val sig: String)

    /**
     * An ownership handoff, signed by the *current* owner. Members follow the signed
     * chain from the original owner key to determine the current owner.
     */
    @Serializable data class OwnerTransfer(val newOwnerId: String, val newOwnerKey: String, val sig: String)

    /** An owner-signed vault title (latest valid one wins). */
    @Serializable data class SignedTitle(val title: String, val sig: String)

    class InvitesResult(val invites: List<Invite>, val seq: Int)
    class InboxResult(val invites: List<Invite>, val requests: List<JoinRequest>, val seq: Int)

    /**
     * One decrypted record-log pull. [rawCount] is the envelope count on the
     * relay; [undecryptable] is how many failed to decrypt under the vault key.
     * When most records fail to decrypt the key/epoch is suspect — the caller
     * must treat that as a failure (abort), not as an empty vault.
     */
    class RecordsResult(
        val items: List<String>,
        val seq: Int,
        val rawCount: Int = items.size,
        val undecryptable: Int = 0,
    ) {
        /** True when decryption failed for every record that exists: key suspect, abort. */
        val fullyUndecryptable: Boolean get() = rawCount > 0 && items.isEmpty()
    }

    @Serializable private data class SubMsg(val t: String, val channel: String)
    @Serializable private data class AppendMsg(val t: String, val channel: String, val actions: List<String>)

    @Serializable data class LiveMsg(
        val t: String = "",
        val channel: String = "",
        val actions: List<String> = emptyList(),
        val seq: Int = 0,
        val data: String = "",
    )
}

/**
 * Vault access roles — enforced entirely client-side via signature checks (relay is a pure log).
 * Passwords vaults never grant VIEWER: read-only passwords still disclose every secret.
 */
object RelayRoles {
    const val OWNER = "owner"
    const val EDITOR = "editor"
    const val VIEWER = "viewer"
    const val REVOKED = "revoked"

    fun canEdit(role: String) = role == OWNER || role == EDITOR
    fun canRead(role: String) = role == OWNER || role == EDITOR || role == VIEWER

    /** Roles offerable for a data type. Passwords exclude viewer (see above). */
    fun offerable(dataType: String): List<String> =
        if (dataType == "passwords") listOf(EDITOR) else listOf(EDITOR, VIEWER)
}

/** Deep-link / share-link helpers for personal vaults (mirrors office://join + voxels://join). */
object RelayLinks {
    const val SCHEME = "everysync"
    const val HOST_JOIN = "join"
    const val SHARE_BASE = "https://maproj.org/es/share"

    /** `https://maproj.org/es/share#id=<vault>&owner=<device>&type=<dataType>` — ids in fragment. */
    fun shareLinkFor(vaultId: String, ownerDeviceId: String, dataType: String): String =
        "$SHARE_BASE#id=$vaultId&owner=$ownerDeviceId&type=$dataType"

    /** `everysync://join/<vaultId>?owner=<device>&type=<dataType>`; null for any other intent. */
    fun parseJoinLink(scheme: String?, host: String?, vaultId: String?, ownerId: String?, dataType: String?)
        : Triple<String, String, String>? {
        if (scheme != SCHEME || host != HOST_JOIN) return null
        if (vaultId.isNullOrBlank() || ownerId.isNullOrBlank()) return null
        return Triple(vaultId, ownerId, dataType.orEmpty())
    }
}
