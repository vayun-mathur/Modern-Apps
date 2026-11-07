package com.vayunmathur.everysync.provider.impl

import android.content.Context
import androidx.compose.runtime.Composable
import com.vayunmathur.everysync.auth.AccountConfig
import com.vayunmathur.everysync.auth.TokenStore
import com.vayunmathur.everysync.data.RemoteContact
import com.vayunmathur.everysync.data.RemoteEvent
import com.vayunmathur.everysync.data.RemoteNote
import com.vayunmathur.everysync.data.RemotePassword
import com.vayunmathur.everysync.data.RemoteTodo
import com.vayunmathur.everysync.data.VaultMeta
import com.vayunmathur.everysync.data.VaultStore
import com.vayunmathur.everysync.data.readKey
import com.vayunmathur.everysync.data.sink.CalendarSink
import com.vayunmathur.everysync.data.sink.ContactsSink
import com.vayunmathur.everysync.network.remote.RelayCursorStore
import com.vayunmathur.everysync.network.remote.RelayRoles
import com.vayunmathur.everysync.network.remote.RelaySync
import com.vayunmathur.everysync.provider.AuthType
import com.vayunmathur.everysync.provider.DataType
import com.vayunmathur.everysync.provider.SyncDirection
import com.vayunmathur.everysync.provider.SyncProvider
import com.vayunmathur.everysync.provider.SyncState
import com.vayunmathur.everysync.sync.CalendarRelayStore
import com.vayunmathur.everysync.sync.ContactRelayStore
import com.vayunmathur.everysync.sync.EntityKind
import com.vayunmathur.everysync.sync.LocalReadException
import com.vayunmathur.everysync.sync.SnapshotStore
import com.vayunmathur.everysync.sync.NoteRelayStore
import com.vayunmathur.everysync.sync.OwnerTransfer
import com.vayunmathur.everysync.sync.PasswordRelayStore
import com.vayunmathur.everysync.sync.RelayMerge
import com.vayunmathur.everysync.sync.RelayPlan
import com.vayunmathur.everysync.sync.RelayStore
import com.vayunmathur.everysync.sync.TodoListRelayStore
import com.vayunmathur.everysync.sync.TodoRelayStore
import com.vayunmathur.everysync.sync.VaultKeys
import com.vayunmathur.everysync.sync.VaultRoster
import com.vayunmathur.everysync.sync.adapters.CalendarKind
import com.vayunmathur.everysync.sync.adapters.ContactKind
import com.vayunmathur.everysync.sync.adapters.NoteKind
import com.vayunmathur.everysync.sync.adapters.PasswordKind
import com.vayunmathur.everysync.sync.adapters.TodoKind
import com.vayunmathur.everysync.sync.adapters.TodoListKind
import com.vayunmathur.everysync.data.sink.NoteSink
import com.vayunmathur.everysync.data.sink.PasswordSink
import com.vayunmathur.everysync.data.sink.TodoIpcSink
import com.vayunmathur.everysync.data.sink.TodoListIpcSink
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.ui.IconProvider
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Personal E2EE vaults over the shared relay ([RelaySync]).
 *
 * One provider, five capabilities: CONTACTS + CALENDAR vaults land in the
 * on-device system providers (via [ContactsSink]/[CalendarSink]), PASSWORDS +
 * NOTES + TODOS vaults land in the passwords/notes/todo apps through their
 * relay ContentProviders (via [PasswordSink]/[NoteSink]/[TodoIpcSink]).
 *
 * Gating: a type syncs only when it is both in [capabilities] and in the
 * account's `enabledTypes`. Each (account, type) pair owns a single vault
 * (see [vaultIdFor]); extra vaults created through the share UI keep syncing
 * through their own account binding once adopted.
 *
 * Reconcile is single-sourced in [RelayMerge]: every `sync*Vault` folds the
 * FULL decrypted record log (tombstones included), runs
 * `RelayMerge.merge(kind, local, remote, baseline, now)` against the sink's
 * full local state and the [VaultBaseline], appends `plan.remoteOut` to the
 * relay BEFORE persisting locally, then applies the local half with
 * `RelayMerge.applyLocal`. Record envelopes are the adapters'
 * `toJson`/`fromJson` bytes on the
 * `contacts|calendar|passwords|notes|todos:<vaultId>` channels. Roster, key
 * epochs and ownership ride the `members|keys|owner|title:<vaultId>` channels
 * via [VaultRoster], [VaultKeys] and [OwnerTransfer].
 *
 * PUSH/PULL direction support: PULL skips the append step (remote changes are
 * still merged locally); PUSH skips applying pulled remote state locally but
 * still folds + appends so local edits propagate.
 *
 * Loss-prevention: a vault cycle that cannot prove what the relay holds aborts
 * via [VaultSyncAbort] with zero local writes, zero relay appends, and zero
 * baseline/cursor mutations (see `driveMerge`). Absence is never deletion:
 * only an explicit `deleted=true` envelope deletes (see `RelayMerge`).
 */
class E2eeProvider : SyncProvider {
    override val id = "e2ee"
    override val displayName = "Personal vaults"
    override val icon: @Composable () -> Unit = { IconProvider() }
    override val authType = AuthType.RELAY
    override val capabilities = setOf(
        DataType.CONTACTS,
        DataType.CALENDAR,
        DataType.PASSWORDS,
        DataType.NOTES,
        DataType.TODOS,
    )

    override suspend fun sync(context: Context, config: AccountConfig, direction: SyncDirection) {
        if (!RelaySync.init(context)) {
            Log.error(TAG, "sync: relay unavailable for '${config.accountName}'")
            throw VaultSyncAbort(CH_RELAY_UNAVAILABLE, "")
        }
        var firstAbort: VaultSyncAbort? = null
        for (type in capabilities) {
            if (type !in config.enabledTypes) continue
            try {
                when (type) {
                    DataType.CONTACTS -> syncContactsVault(context, config, direction)
                    DataType.CALENDAR -> syncCalendarVault(context, config, direction)
                    DataType.PASSWORDS -> syncPasswordsVault(context, config, direction)
                    DataType.NOTES -> syncNotesVault(context, config, direction)
                    DataType.TODOS -> syncTodosVault(context, config, direction)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (abort: VaultSyncAbort) {
                // One vault's abort must not block the others, but it must
                // surface: SyncEngine records lastSyncError from the throw.
                Log.error(TAG, "sync $type aborted for '${config.accountName}': ${abort.code}", abort)
                if (firstAbort == null) firstAbort = abort
            } catch (expected: Exception) {
                Log.error(TAG, "sync $type failed for '${config.accountName}'", expected)
            }
        }
        // Surface the first abort so lastSyncError reflects it; SyncEngine
        // records it via its existing catch path.
        firstAbort?.let { throw it }
    }

    // --- Contacts vault <-> ContactsContract via ContactsSink ---

    private suspend fun syncContactsVault(context: Context, config: AccountConfig, direction: SyncDirection) {
        val vault = resolveVault(context, config, DataType.CONTACTS) ?: return
        driveMerge(
            context = context,
            vault = vault,
            channelType = CH_CONTACTS,
            direction = direction,
            kind = ContactKind,
            store = ContactRelayStore(context, config.accountName),
            writeRemoteStamps = { item, stamp ->
                ContactsSink.upsert(
                    context,
                    config.accountName,
                    item.copy(etag = stamp.toString(), href = vault.vaultId),
                )
            },
        )
    }

    // --- Calendar vault <-> CalendarContract via CalendarSink ---

    private suspend fun syncCalendarVault(context: Context, config: AccountConfig, direction: SyncDirection) {
        val vault = resolveVault(context, config, DataType.CALENDAR) ?: return
        val calendarId = CalendarSink.getOrCreateCalendarId(
            context, config.accountName, vault.vaultId, vault.title, null,
        )
        if (calendarId == -1L) return
        driveMerge(
            context = context,
            vault = vault,
            channelType = CH_CALENDAR,
            direction = direction,
            kind = CalendarKind,
            store = CalendarRelayStore(context, config.accountName, calendarId),
            writeRemoteStamps = { item, stamp ->
                CalendarSink.upsertEvent(
                    context,
                    config.accountName,
                    calendarId,
                    item.copy(etag = stamp.toString(), href = vault.vaultId),
                )
            },
        )
    }

    // --- Passwords vault <-> passwords app via PasswordSink ---

    private suspend fun syncPasswordsVault(context: Context, config: AccountConfig, direction: SyncDirection) {
        val vault = resolveVault(context, config, DataType.PASSWORDS) ?: return
        driveMerge(
            context = context,
            vault = vault,
            channelType = CH_PASSWORDS,
            direction = direction,
            kind = PasswordKind,
            store = PasswordRelayStore(context, vault.vaultId),
        )
    }

    // --- Notes vault <-> notes app via NoteSink ---

    private suspend fun syncNotesVault(context: Context, config: AccountConfig, direction: SyncDirection) {
        val vault = resolveVault(context, config, DataType.NOTES) ?: return
        driveMerge(
            context = context,
            vault = vault,
            channelType = CH_NOTES,
            direction = direction,
            kind = NoteKind,
            store = NoteRelayStore(context, vault.vaultId),
        )
    }

    // --- Todos vault <-> todo app via TodoIpcSink ---

    private suspend fun syncTodosVault(context: Context, config: AccountConfig, direction: SyncDirection) {
        val vault = resolveVault(context, config, DataType.TODOS) ?: return
        driveMerge(
            context = context,
            vault = vault,
            channelType = CH_TODOS,
            direction = direction,
            kind = TodoKind,
            store = TodoRelayStore(context, vault.vaultId),
        )
        if (TodoListIpcSink.isSupported(context, vault.vaultId)) {
            driveMerge(
                context = context,
                vault = vault,
                channelType = CH_TODO_LISTS,
                direction = direction,
                kind = TodoListKind,
                store = TodoListRelayStore(context, vault.vaultId),
            )
        }
    }

    /**
     * One merge cycle for a vault, following the [RelayMerge] driver contract:
     * fold the FULL decrypted record log (tombstones included — this must be
     * full state, not a delta, so absence vs deletion stays decidable),
     * merge against the sink's full local state + baseline, append
     * `plan.remoteOut` BEFORE persisting locally, then apply the local half.
     *
     * [writeRemoteStamps] mirrors the relay `updatedAt` into the sink's
     * change-detection column for system-provider sinks (etag/SYNC_DATA1);
     * IPC sinks already persist `updatedAt` via `upsertRaw`, so they pass
     * null and the store writes carry the merged entities verbatim.
     */
    private suspend fun <T> driveMerge(
        context: Context,
        vault: ResolvedVault,
        channelType: String,
        direction: SyncDirection,
        kind: EntityKind<T>,
        store: RelayStore<T>,
        writeRemoteStamps: (suspend (item: T, stamp: Long) -> Unit)? = null,
    ) {
        val channel = RelaySync.recordChannel(channelType, vault.vaultId)
        val now = System.currentTimeMillis()
        // Full log pull (from 0): the merge needs full remote state, not a
        // delta. The pull cursor still advances so live-push paths can skip
        // re-reads; the merge itself never depends on it.
        //
        // A failed pull is NOT an empty log: abort with zero writes, zero
        // appends, zero baseline/cursor mutations. Merging a failure as empty
        // would delete every baseline-present row (see RelayMerge).
        val pulled = RelaySync.pullRecords(channel, vault.key, 0)
            ?: throw VaultSyncAbort(CH_PULL_TRANSPORT, channelType)
        if (pulled.fullyUndecryptable) {
            // Records exist but none decrypt under this epoch key: re-key or
            // epoch fetch is needed, not a merge of an "empty" vault.
            throw VaultSyncAbort(CH_UNDECRYPTABLE_LOG, channelType)
        }
        val remote = pulled.items.mapNotNull { store.parseRemote(it) }
        val local = try {
            store.listLocal()
        } catch (read: LocalReadException) {
            // A failed local read is not an empty vault: abort with zero
            // writes, zero appends, zero baseline/cursor mutations.
            throw VaultSyncAbort(CH_LOCAL_READ_FAILED, channelType)
        }
        if (!store.listLocalOk) {
            // The sink tracked a failed/partial read without throwing (null
            // cursor, swallowed IPC error): same abort, never merge.
            throw VaultSyncAbort(CH_LOCAL_READ_FAILED, channelType)
        }
        val baseline = store.loadBaseline()
        val plan = RelayMerge.merge(kind, local, remote, baseline, now)
        // When the append step is skipped (PULL-only cycle, or a viewer who
        // may not write), the records in remoteOut never reach the relay.
        // Their snapshots must NOT be persisted: a snapshot marks a record
        // as agreed with the relay, so a local-only record would become
        // reconcileLocalOnly's "baseline present, relay absent" case and
        // never push again, and a diverged local edit would read clean so
        // the next cycle lets the remote side overwrite it unpushed.
        val appendSkipped = direction == SyncDirection.PULL || !vault.canEdit
        if (!appendSkipped && plan.remoteOut.isNotEmpty()) {
            val blobs = plan.remoteOut.map { store.serializeForRemote(it) }
            if (RelaySync.appendRecords(channel, vault.key, blobs) == null) {
                // Append failed: never mark un-pushed records clean. Local
                // state is untouched, so the next cycle retries the same plan.
                Log.error(TAG, "append failed for $channel; retrying next cycle")
                return
            }
        }
        if (direction == SyncDirection.PUSH) return
        val unpushedIds = if (appendSkipped) {
            plan.remoteOut.map { kind.syncId(it) }.toSet()
        } else {
            emptySet()
        }
        val pendingSnapshots = if (unpushedIds.isEmpty()) {
            plan.snapshots
        } else {
            plan.snapshots.filterNot { it.syncId in unpushedIds }
        }
        if (writeRemoteStamps != null) {
            // System-provider sinks mirror the relay stamp into their
            // change-detection column on write; the IPC stores persist
            // updatedAt via upsertRaw, so they need no stamping.
            for (item in plan.localUpserts) {
                writeRemoteStamps(item, kind.updatedAt(item))
            }
            RelayMerge.applyLocal(
                store,
                RelayPlan(
                    localUpserts = emptyList(),
                    localDeletes = plan.localDeletes,
                    remoteOut = emptyList(),
                    snapshots = pendingSnapshots,
                    removedSyncIds = plan.removedSyncIds,
                    remoteChanged = false,
                    pushed = plan.pushed,
                    pulled = plan.pulled,
                    deletedLocal = plan.deletedLocal,
                    deletedRemote = plan.deletedRemote,
                ),
            )
        } else {
            RelayMerge.applyLocal(
                store,
                RelayPlan(
                    localUpserts = plan.localUpserts,
                    localDeletes = plan.localDeletes,
                    remoteOut = emptyList(),
                    snapshots = pendingSnapshots,
                    removedSyncIds = plan.removedSyncIds,
                    remoteChanged = plan.remoteChanged,
                    pushed = plan.pushed,
                    pulled = plan.pulled,
                    deletedLocal = plan.deletedLocal,
                    deletedRemote = plan.deletedRemote,
                ),
            )
            RelayCursorStore.advance(context, channelType, vault.vaultId, CH_RECORDS, pulled.seq)
            return
        }
        RelayCursorStore.advance(context, channelType, vault.vaultId, CH_RECORDS, pulled.seq)
    }

    // --- Vault resolution ---

    private class ResolvedVault(
        val vaultId: String,
        val key: ByteArray,
        val title: String,
        val canEdit: Boolean,
    )

    /**
     * Resolves the account's vault for [type]: looks up the stored vault id,
     * adopts the single existing vault of that type when unbound, else creates
     * a new owned vault. Returns null when the vault is unreadable (no key or
     * no read access) — the type is skipped, never failed.
     */
    private suspend fun resolveVault(context: Context, config: AccountConfig, type: DataType): ResolvedVault? {
        val rt = type.relayType() ?: return null
        var meta = vaultIdFor(context, config.accountName, rt)?.let { VaultStore.get(context, it) }
        if (meta == null) {
            val existing = VaultStore.getAll(context).filter { it.dataType == rt }
            meta = if (existing.size == 1) {
                existing.first()
            } else {
                createVault(context, config.accountName, rt) ?: return null
            }
            SyncState.set(context, config.accountName, vaultScope(rt), meta.vaultId)
        }
        // Unseal the Keystore-wrapped key; fail closed when it cannot be read.
        val baseKey = meta.readKey(context)
        if (baseKey == null || baseKey.isEmpty()) return null
        val baseOwnerKey = meta.ownerKeyB64
        val ownerKey = OwnerTransfer.fetchOwnerKey(meta.vaultId, baseOwnerKey)
            ?: runCatching { Base64.decode(baseOwnerKey) }.getOrNull()
        if (ownerKey == null || ownerKey.isEmpty()) return null
        val (epoch, key) = VaultKeys.fetchCurrentKey(meta.vaultId, baseKey, ownerKey, meta.epoch)
        if (epoch != meta.epoch) {
            meta = meta.copy(epoch = epoch)
            VaultStore.upsert(context, meta)
        }
        val role = VaultRoster.myRole(meta.vaultId, ownerKey)
        val canRead = role?.let { RelayRoles.canRead(it) } ?: meta.isOwner
        if (!canRead) return null
        val canEdit = role?.let { RelayRoles.canEdit(it) } ?: meta.isOwner
        return ResolvedVault(meta.vaultId, key, meta.title, canEdit)
    }

    private suspend fun createVault(context: Context, accountName: String, relayType: String): VaultMeta? {
        // upsert seals keyB64 at rest; the TokenStore copy stays Keystore-encrypted too.
        return runCatching {
            val vaultId = RelaySync.newVaultId()
            val key = RelaySync.newVaultKey()
            val meta = VaultMeta(
                vaultId = vaultId,
                dataType = relayType,
                title = "Personal $relayType",
                keyB64 = Base64.encode(key),
                ownerDeviceId = RelaySync.deviceId,
                ownerKeyB64 = Base64.encode(RelaySync.publicBundle),
                isOwner = true,
            )
            VaultStore.upsert(context, meta)
            TokenStore.getInstance(context).putSyncKey(accountName, relayType, Base64.encode(key))
            VaultRoster.recordMembers(vaultId, listOf(RelaySync.Member(RelaySync.deviceId, "", RelayRoles.OWNER)))
            publishTitle(meta)
            meta
        }.getOrNull()
    }

    /** Owner-signed title record, byte-identical to the share flow's publisher. */
    private suspend fun publishTitle(meta: VaultMeta) {
        val payload = json.encodeToString(mapOf("title" to meta.title))
        val sig = Base64.encode(RelaySync.sign(payload.encodeToByteArray()))
        val envelope = json.encodeToString(RelaySync.SignedEnvelope(RelaySync.deviceId, sig, payload))
        RelaySync.appendRaw("title:${meta.vaultId}", listOf(envelope))
    }

    companion object {
        private const val TAG = "E2eeProvider"
        const val CH_CONTACTS = "contacts"
        const val CH_CALENDAR = "calendar"
        const val CH_PASSWORDS = "passwords"
        const val CH_NOTES = "notes"
        const val CH_TODOS = "todos"
        /** Lists ride their own channel beside [CH_TODOS] under the same vault. */
        const val CH_TODO_LISTS = "todolists"
        private const val CH_RECORDS = "records"
        private val json = Json { ignoreUnknownKeys = true }

        fun DataType.relayType(): String? = when (this) {
            DataType.CONTACTS -> CH_CONTACTS
            DataType.CALENDAR -> CH_CALENDAR
            DataType.PASSWORDS -> CH_PASSWORDS
            DataType.NOTES -> CH_NOTES
            DataType.TODOS -> CH_TODOS
        }

        private fun vaultScope(relayType: String) = "e2ee_vault_$relayType"

        /** The account-bound vault id for [relayType], or null when unbound. */
        fun vaultIdFor(context: Context, accountName: String, relayType: String): String? =
            SyncState.get(context, accountName, vaultScope(relayType))?.ifBlank { null }

        /**
         * Removes a disabled type's local data for every vault of that type, so
         * it doesn't linger; it is pulled again if re-enabled. Called from
         * `EverySyncViewModel.toggleType`. Contacts/calendar purges are
         * account-wide by sink design (one call covers every vault).
         *
         * Takes a pre-purge snapshot of each affected vault first: a purge is
         * exactly the "everything vanished locally" shape the merge refuses to
         * infer, so the snapshot is the way back if the user re-enables and
         * the relay has moved on.
         */
        fun purgeType(context: Context, accountName: String, type: DataType) {
            val rt = type.relayType() ?: return
            // Snapshot first (fire-and-forget on failure: purge is explicit
            // user intent, the snapshot is only the way back).
            for (meta in VaultStore.getAll(context).filter { it.dataType == rt }) {
                runCatching {
                    kotlinx.coroutines.runBlocking {
                        SnapshotStore.snapshotVault(context, meta.vaultId, rt)
                    }
                }
            }
            when (type) {
                DataType.CONTACTS -> ContactsSink.purge(context, accountName)
                DataType.CALENDAR -> CalendarSink.purge(context, accountName)
                DataType.PASSWORDS ->
                    for (meta in VaultStore.getAll(context).filter { it.dataType == rt }) {
                        PasswordSink.purge(context, accountName, meta.vaultId)
                    }
                DataType.NOTES ->
                    for (meta in VaultStore.getAll(context).filter { it.dataType == rt }) {
                        NoteSink.purge(context, accountName, meta.vaultId)
                    }
                DataType.TODOS ->
                    for (meta in VaultStore.getAll(context).filter { it.dataType == rt }) {
                        TodoIpcSink.purge(context, accountName, meta.vaultId)
                        TodoListIpcSink.purge(context, meta.vaultId)
                    }
            }
        }
    }
}

/**
 * A vault sync cycle that cannot prove what the relay holds. Carries a stable
 * machine-readable [code] (surfaced via `AccountConfig.lastSyncError`) plus the
 * vault [channelType] for scoping. Throwing aborts the cycle with zero local
 * writes, zero relay appends, and zero baseline/cursor mutations — never
 * degrade an abort into a merge.
 */
class VaultSyncAbort(val code: String, val channelType: String) :
    Exception("vault sync aborted: $code ($channelType)")

/** Stable `VaultSyncAbort` codes, safe to persist and to show in UI. */
const val CH_PULL_TRANSPORT = "pull_transport_failed"
const val CH_UNDECRYPTABLE_LOG = "log_undecryptable"
const val CH_LOCAL_READ_FAILED = "local_read_failed"
const val CH_RELAY_UNAVAILABLE = "relay_unavailable"
