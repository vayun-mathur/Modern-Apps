package com.vayunmathur.everysync.platform

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.vayunmathur.everysync.data.VaultMeta
import com.vayunmathur.everysync.data.VaultStore
import com.vayunmathur.everysync.data.readKey
import com.vayunmathur.everysync.network.remote.RelayLinks
import com.vayunmathur.everysync.network.remote.RelayRoles
import com.vayunmathur.everysync.network.remote.RelaySync
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.DataStoreUtils
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// --- Vault sharing: create-vault + invite + approve/deny + join (EverySync owns all of it) ---
//
// Vault creation and sharing live ONLY here. No per-app share buttons or
// dialogs: contacts/calendar/passwords/notes apps never name a vault, an
// invite, or a join link.
//
// Transport rides RelaySync: `sendInvite` seals the vault key to the
// recipient's inbox, `sendJoinRequest` seals public ids to the owner's inbox,
// and `pullInbox` sorts both apart. Members/keys/owner/title are
// owner-signed records on their own channels, verified client-side.

/** Everything the vault section of AccountDetailScreen draws for one vault. */
data class VaultUiState(
    val meta: VaultMeta,
    val shareLink: String = "",
    val members: List<VaultMemberUi> = emptyList(),
    val requests: List<VaultRequestUi> = emptyList(),
)

/** One member row: id, display name, role. */
data class VaultMemberUi(val id: String, val name: String = "", val role: String = RelayRoles.EDITOR)

/** One pending join request: public ids only, never key material. */
data class VaultRequestUi(val requesterId: String, val name: String = "")

/** Vault state owned by [EverySyncViewModel]: the vault list, this device's id, and per-vault UI. */
class EverySyncVaults(private val viewModel: EverySyncViewModel) {
    private val app: Application get() = viewModel.getApplication()

    private val _vaults = MutableStateFlow<List<VaultMeta>>(emptyList())
    val vaults: StateFlow<List<VaultMeta>> = _vaults.asStateFlow()

    private val _deviceId = MutableStateFlow("")
    val deviceId: StateFlow<String> = _deviceId.asStateFlow()

    private val _vaultUi = MutableStateFlow<Map<String, VaultUiState>>(emptyMap())
    val vaultUi: StateFlow<Map<String, VaultUiState>> = _vaultUi.asStateFlow()

    private val inboxJson = Json { ignoreUnknownKeys = true }

    fun load() {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            _vaults.value = VaultStore.getAll(app)
            if (runCatching { RelaySync.init(app) }.getOrDefault(false)) {
                _deviceId.value = RelaySync.deviceId
            } else {
                _deviceId.value = DataStoreUtils.getInstance(app).getStringAwait("personalDeviceId").orEmpty()
            }
            refreshAll()
        }
    }

    /** Creates a new owned vault for [dataType] with [title] and publishes its signed title. */
    fun createVault(dataType: String, title: String, onResult: (Boolean) -> Unit = {}) {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            // upsert seals keyB64 at rest.
            val ok = runCatching {
                if (!RelaySync.init(app)) return@runCatching false
                val vaultId = RelaySync.newVaultId()
                val key = RelaySync.newVaultKey()
                val meta = VaultMeta(
                    vaultId = vaultId,
                    dataType = dataType,
                    title = title.ifBlank { dataType },
                    keyB64 = Base64.encode(key),
                    ownerDeviceId = RelaySync.deviceId,
                    ownerKeyB64 = Base64.encode(RelaySync.publicBundle),
                    isOwner = true,
                )
                VaultStore.upsert(app, meta)
                writeMembers(vaultId, listOf(RelaySync.Member(RelaySync.deviceId, "", RelayRoles.OWNER)))
                publishTitle(meta)
                _vaults.value = VaultStore.getAll(app)
                refreshVault(vaultId)
                true
            }.getOrDefault(false)
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    /** Seals an invite (vault id + key + role + owner identity) to [recipientId]'s inbox. */
    fun sendInvite(vaultId: String, recipientId: String, role: String, onResult: (Boolean) -> Unit = {}) {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val meta = VaultStore.get(app, vaultId) ?: return@runCatching false
                if (!meta.isOwner) return@runCatching false
                val key = meta.readKey(app) ?: return@runCatching false
                RelaySync.sendInvite(
                    recipientId = recipientId,
                    vaultId = vaultId,
                    dataType = meta.dataType,
                    key = key,
                    title = meta.title,
                    role = role,
                    ownerKeyB64 = meta.ownerKeyB64,
                    ownerDeviceId = meta.ownerDeviceId,
                    epoch = meta.epoch,
                )
            }.getOrDefault(false)
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    /** Approves [requesterId] by sealing the vault invite to them and recording them on the roster. */
    fun approveRequest(vaultId: String, requesterId: String, name: String = "", role: String = RelayRoles.EDITOR) {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val meta = VaultStore.get(app, vaultId) ?: return@runCatching false
                if (!meta.isOwner) return@runCatching false
                val key = meta.readKey(app) ?: return@runCatching false
                writeMembers(vaultId, listOf(RelaySync.Member(requesterId, name, role)))
                RelaySync.sendInvite(
                    recipientId = requesterId,
                    vaultId = vaultId,
                    dataType = meta.dataType,
                    key = key,
                    title = meta.title,
                    role = role,
                    ownerKeyB64 = meta.ownerKeyB64,
                    ownerDeviceId = meta.ownerDeviceId,
                    epoch = meta.epoch,
                )
            }.getOrDefault(false)
            if (ok) removePendingRequest(vaultId, requesterId)
            if (ok) refreshVault(vaultId)
        }
    }

    /** Drops a pending join request locally without granting access. */
    fun denyRequest(vaultId: String, requesterId: String) {
        viewModel.viewModelScope.launch(Dispatchers.IO) { removePendingRequest(vaultId, requesterId) }
    }

    /** Sends a sealed join request (public ids only) to [ownerId]'s inbox. */
    fun requestToJoin(vaultId: String, ownerId: String, dataType: String, onResult: (Boolean) -> Unit = {}) {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                RelaySync.init(app)
                RelaySync.sendJoinRequest(ownerId, vaultId, dataType, RelaySync.deviceId, "")
            }.getOrDefault(false)
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    /** Pulls invites + join requests from this device's inbox; joins invites, stashes requests. */
    fun refreshInbox() {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (!RelaySync.init(app)) return@runCatching
                val ds = DataStoreUtils.getInstance(app)
                val since = ds.getLongAwait(KEY_INBOX_CURSOR)?.toInt() ?: 0
                val inbox = RelaySync.pullInbox(since)
                ds.setLong(KEY_INBOX_CURSOR, inbox.seq.toLong())
                for (invite in inbox.invites) {
                    if (VaultStore.get(app, invite.vaultId) == null) {
                        // upsert seals the plaintext invite key at rest.
                        VaultStore.upsert(
                            app,
                            VaultMeta(
                                vaultId = invite.vaultId,
                                dataType = invite.dataType,
                                title = invite.title,
                                keyB64 = invite.key,
                                ownerDeviceId = invite.ownerDeviceId,
                                ownerKeyB64 = invite.ownerKey,
                                epoch = invite.epoch,
                                isOwner = false,
                            ),
                        )
                    }
                }
                for (request in inbox.requests) {
                    addPendingRequest(request.vaultId, request.requesterId, request.name)
                }
                _vaults.value = VaultStore.getAll(app)
                refreshAll()
            }
        }
    }

    /** Computes the out-of-band safety code for [recipientId] (null when their key is unknown). */
    fun safetyCode(recipientId: String, onResult: (String?) -> Unit) {
        viewModel.viewModelScope.launch(Dispatchers.IO) {
            val code = runCatching {
                RelaySync.init(app)
                val bundle = RelaySync.getKey(recipientId) ?: return@runCatching null
                RelaySync.securityCode(bundle)
            }.getOrNull()
            withContext(Dispatchers.Main) { onResult(code) }
        }
    }

    /** `https://maproj.org/es/share#id=<vault>&owner=<device>&type=<dataType>` — ids in fragment. */
    fun shareLinkFor(meta: VaultMeta): String =
        RelayLinks.shareLinkFor(meta.vaultId, meta.ownerDeviceId, meta.dataType)

    private suspend fun refreshAll() {
        for (meta in _vaults.value) refreshVault(meta.vaultId)
    }

    private suspend fun refreshVault(vaultId: String) {
        val meta = VaultStore.get(app, vaultId) ?: return
        // Fail closed when the key cannot be unsealed: skip, never merge.
        if ((meta.readKey(app)?.size ?: 0) == 0) return
        val members = readMembers(vaultId).map { VaultMemberUi(it.id, it.name, it.role) }
        val requests = loadRequests(vaultId).map { VaultRequestUi(it.requesterId, it.name) }
        // Joined vaults follow the owner-signed title; owned vaults keep the local one.
        val title = if (meta.isOwner) meta.title else readTitle(vaultId) ?: meta.title
        if (title != meta.title) VaultStore.upsert(app, meta.copy(title = title))
        val current = _vaultUi.value.toMutableMap()
        current[vaultId] = VaultUiState(
            meta = meta.copy(title = title),
            shareLink = shareLinkFor(meta),
            members = members,
            requests = requests,
        )
        _vaultUi.value = current
    }

    // --- Owner-signed roster (only records with a valid owner signature are honored) ---

    private suspend fun writeMembers(vaultId: String, members: List<RelaySync.Member>) {
        val payload = inboxJson.encodeToString(members)
        val sig = Base64.encode(RelaySync.sign(payload.encodeToByteArray()))
        val envelope = inboxJson.encodeToString(RelaySync.SignedEnvelope(RelaySync.deviceId, sig, payload))
        RelaySync.appendRaw("members:$vaultId", listOf(envelope))
    }

    private suspend fun readMembers(vaultId: String): List<RelaySync.Member> {
        val ownerKey = runCatching { Base64.decode(VaultStore.get(app, vaultId)?.ownerKeyB64.orEmpty()) }
            .getOrNull() ?: return emptyList()
        // A failed pull keeps the last-known roster: no verified data means no
        // roster change, never an emptied roster.
        val raw = RelaySync.pullRaw("members:$vaultId", 0) ?: return emptyList()
        // The roster channel is append-only: a role change appends a new
        // record for the same member id, so the fold is last-wins per id
        // (as in VaultRoster.fetchRoster). Flattening without the fold
        // showed the member once per historical role, revoked included.
        val byId = LinkedHashMap<String, RelaySync.Member>()
        for (r in raw) {
            val envelope = runCatching { inboxJson.decodeFromString<RelaySync.SignedEnvelope>(r) }.getOrNull()
                ?: continue
            val valid = RelaySync.verify(ownerKey, envelope.payload.encodeToByteArray(), Base64.decode(envelope.sig))
            if (!valid) continue
            val members = runCatching {
                inboxJson.decodeFromString<List<RelaySync.Member>>(envelope.payload)
            }.getOrNull() ?: continue
            for (member in members) {
                if (member.id.isNotBlank()) byId[member.id] = member
            }
        }
        return byId.values.toList()
    }

    private suspend fun publishTitle(meta: VaultMeta) {
        val payload = inboxJson.encodeToString(mapOf("title" to meta.title))
        val sig = Base64.encode(RelaySync.sign(payload.encodeToByteArray()))
        val envelope = inboxJson.encodeToString(RelaySync.SignedEnvelope(RelaySync.deviceId, sig, payload))
        RelaySync.appendRaw("title:${meta.vaultId}", listOf(envelope))
    }

    private suspend fun readTitle(vaultId: String): String? {
        val ownerKey = runCatching { Base64.decode(VaultStore.get(app, vaultId)?.ownerKeyB64.orEmpty()) }
            .getOrNull() ?: return null
        // A failed pull keeps the last-known title: no verified data means no
        // title change.
        val raw = RelaySync.pullRaw("title:$vaultId", 0) ?: return null
        return raw.mapNotNull { r ->
            val envelope = runCatching { inboxJson.decodeFromString<RelaySync.SignedEnvelope>(r) }.getOrNull()
                ?: return@mapNotNull null
            val valid = RelaySync.verify(ownerKey, envelope.payload.encodeToByteArray(), Base64.decode(envelope.sig))
            if (!valid) return@mapNotNull null
            runCatching { inboxJson.decodeFromString<Map<String, String>>(envelope.payload)["title"] }.getOrNull()
        }.lastOrNull()
    }

    // --- Pending join requests, stashed per vault in DataStore ---

    @kotlinx.serialization.Serializable
    private data class StoredRequest(val requesterId: String, val name: String = "")

    private fun requestsKey(vaultId: String) = "everysync_requests:$vaultId"

    private suspend fun loadRequests(vaultId: String): List<StoredRequest> {
        val raw = DataStoreUtils.getInstance(app).getStringAwait(requestsKey(vaultId))
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { inboxJson.decodeFromString<List<StoredRequest>>(raw) }.getOrDefault(emptyList())
    }

    private suspend fun addPendingRequest(vaultId: String, requesterId: String, name: String) {
        val ds = DataStoreUtils.getInstance(app)
        val key = requestsKey(vaultId)
        val current = loadRequests(vaultId).filterNot { it.requesterId == requesterId }
        ds.setString(key, inboxJson.encodeToString(current + StoredRequest(requesterId, name)))
        AppMessages.show("Join request received")
        refreshVault(vaultId)
    }

    private suspend fun removePendingRequest(vaultId: String, requesterId: String) {
        val ds = DataStoreUtils.getInstance(app)
        val key = requestsKey(vaultId)
        ds.setString(
            key,
            inboxJson.encodeToString(loadRequests(vaultId).filterNot { it.requesterId == requesterId }),
        )
        refreshVault(vaultId)
    }

    companion object {
        private const val KEY_INBOX_CURSOR = "everysync_inbox_cursor"
    }
}
