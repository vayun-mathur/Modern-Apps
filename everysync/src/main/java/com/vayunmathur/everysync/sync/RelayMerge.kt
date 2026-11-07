package com.vayunmathur.everysync.sync

import java.security.MessageDigest
import java.util.UUID

/**
 * Last agreed state of one relay record, written after a successful merge.
 *
 * Mirrors the passwords baseline: without it "present locally, absent remotely"
 * cannot be told apart from "created locally, not pushed yet", so deletions
 * could never propagate. [deleted] marks a retained tombstone alongside the
 * snapshot rows so a delete survives until both sides have acknowledged it.
 */
data class RelaySnapshot(
    val syncId: String,
    val kind: String,
    val contentHash: String,
    val localUpdatedAt: Long,
    val remoteModified: Long,
    val lastSyncedAt: Long,
    val deleted: Boolean = false,
)

/** Opaque 32-char identity shared between a local row and its relay record. */
fun newRelaySyncId(): String = UUID.randomUUID().toString().replace("-", "")

fun relayContentHash(canonical: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(canonical.toByteArray(Charsets.UTF_8))
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Local + remote access for one relay channel. This is the RelayMerge
 * replacement for the KDBX pair (`KdbxDocument` + `KdbxCodec`): the transport
 * itself ([com.vayunmathur.everysync.network.remote.RelaySync]) stays outside,
 * this interface only adapts typed entities to the string envelopes the relay
 * carries.
 *
 * Loss-prevention contract on [listLocal]: a failed local read must surface as
 * failure (throw `LocalReadException`, or a store whose `listLocalOk` returns
 * false), NEVER as an empty list. An empty list means "this vault provably
 * holds nothing locally" — merging a read failure as empty would delete every
 * baseline-present relay record.
 */
interface RelayStore<T> {
    suspend fun listLocal(): List<T>
    suspend fun upsertLocal(items: List<T>)
    suspend fun deleteLocal(items: List<T>)
    suspend fun loadBaseline(): Map<String, RelaySnapshot>
    suspend fun saveSnapshots(snapshots: List<RelaySnapshot>)
    suspend fun removeSnapshots(syncIds: List<String>)

    /**
     * True when the last [listLocal] observed a healthy read. Stores whose
     * read path can fail (ContentProvider IPC, system providers) track this;
     * [RelayMerge] consults it and the driver aborts on false.
     */
    val listLocalOk: Boolean
        get() = true

    /** Parse one decrypted relay envelope; null skips a foreign/corrupt entry. */
    fun parseRemote(json: String): T?

    /** Serialize one entity to a relay envelope (decrypted form). */
    fun serializeForRemote(entity: T): String
}

/** Thrown by [RelayStore.listLocal] when the local read cannot be trusted. */
class LocalReadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Per-type behaviour for the generic three-way merge. */
abstract class EntityKind<T> {
    abstract val kind: String
    abstract fun syncId(e: T): String
    abstract fun updatedAt(e: T): Long
    abstract fun isDeleted(e: T): Boolean
    abstract fun contentHash(e: T): String
    abstract fun contentKey(e: T): String
    abstract fun rebind(e: T, syncId: String): T
    abstract fun withDeleted(e: T, deleted: Boolean): T
    abstract fun withUpdatedAt(e: T, updatedAt: Long): T

    /**
     * Whether this store's local side carries authoritative local delete marks
     * that survive in [listLocal] output. When true (soft deletes: tombstone
     * rows from system-provider DELETED flags or app tombstone rows), an id
     * that is absent from [listLocal] WITHOUT a tombstone is never a local
     * delete — it is a missed read — so the merge resurrects the relay copy
     * instead of tombstoning it. When false (hard deletes: the row is gone and
     * only the baseline remembers it), a healthy-read absence is a genuine
     * local delete and the merge propagates a tombstone to the relay.
     */
    open val localDeletesAreAuthoritative: Boolean = true

    fun snapshot(
        syncId: String,
        hash: String,
        localUpdatedAt: Long,
        remoteModified: Long,
        now: Long,
        deleted: Boolean = false,
    ) = RelaySnapshot(syncId, kind, hash, localUpdatedAt, remoteModified, now, deleted)
}

/**
 * The merge outcome. [remoteOut] holds ONLY the records the driver must append
 * to the relay log ([RelayStore.serializeForRemote] each one) — the relay is
 * append-only, so already-logged records are never re-appended.
 */
class RelayPlan<T>(
    val localUpserts: List<T>,
    val localDeletes: List<T>,
    val remoteOut: List<T>,
    val snapshots: List<RelaySnapshot>,
    val removedSyncIds: List<String>,
    val remoteChanged: Boolean,
    val pushed: Int,
    val pulled: Int,
    val deletedLocal: Int,
    val deletedRemote: Int,
)

class MergeAccumulator<T> {
    val localUpserts = mutableListOf<T>()
    val localDeletes = mutableListOf<T>()
    val remoteOut = mutableListOf<T>()
    val snapshots = mutableListOf<RelaySnapshot>()
    val removed = mutableListOf<String>()
    val freshlyIdentified = mutableSetOf<String>()

    /** Remote ids whose envelope on the relay lacks our sync id and must be re-appended. */
    val adoptedRemote = mutableSetOf<String>()
    var remoteChanged = false
    var pushed = 0
    var pulled = 0
    var deletedLocal = 0
    var deletedRemote = 0

    fun toPlan(): RelayPlan<T> = RelayPlan(
        localUpserts = localUpserts,
        localDeletes = localDeletes,
        remoteOut = remoteOut,
        snapshots = snapshots,
        removedSyncIds = removed,
        remoteChanged = remoteChanged,
        pushed = pushed,
        pulled = pulled,
        deletedLocal = deletedLocal,
        deletedRemote = deletedRemote,
    )
}

/**
 * Generic three-way merge extracted from the passwords KDBX engine, without any
 * KDBX dependency.
 *
 * LOSS-PREVENTION RULE: only an explicit tombstone deletes. An id that is
 * present on one side but absent from the folded relay log — with a baseline
 * or without — is NEVER treated as a deletion: the append-only relay log may
 * be truncated, partially read, or mid-propagation, and none of those is proof
 * that anyone deleted anything. Deletion propagates exclusively through an
 * explicit `deleted=true` envelope for that syncId in the folded log
 * (`reconcilePresent` tombstone path). See the KDoc on `RelaySync` for the
 * key-hygiene invariant bounding who can author tombstones.
 *
 * Conflict rule is last-writer-wins by [EntityKind.updatedAt], tie-broken by
 * [EntityKind.contentHash] so equal timestamps stay deterministic across
 * devices. Tombstones ([EntityKind.isDeleted]) win or lose by the same LWW
 * comparison, so a delete races an edit exactly like two edits race.
 *
 * Driver contract (the relay is an append-only log — records are never
 * rewritten, only appended):
 *  0. NEVER call `merge` with a remote fold the transport could not prove:
 *     a failed pull must abort the cycle, not produce an empty [remoteEntities]
 *     list. (An empty list here means "the log provably holds nothing".)
 *  1. Fold the full decrypted record log into [remoteEntities] (tombstones
 *     included; this must be the FULL state, not a delta, so absence vs
 *     deletion stays decidable against the baseline).
 *  2. `val plan = RelayMerge.merge(kind, local, remote, baseline, now)`.
 *  3. Append `plan.remoteOut` (via [RelayStore.serializeForRemote]) to the
 *     relay BEFORE persisting locally, so a failed append never marks
 *     un-pushed records clean.
 *  4. `RelayMerge.applyLocal(store, plan)` to persist the local side.
 */
object RelayMerge {
    fun <T> merge(
        kind: EntityKind<T>,
        localEntities: List<T>,
        remoteEntities: List<T>,
        baseline: Map<String, RelaySnapshot>,
        now: Long,
    ): RelayPlan<T> {
        val accumulator = MergeAccumulator<T>()
        val local = indexLocal(kind, localEntities, accumulator)
        val remote = indexRemote(kind, local, remoteEntities, accumulator)
        // The authoritative-marks flag lives on the kind: one source of truth
        // per vault type (soft-delete stores resurrect unmarked absences;
        // hard-delete stores propagate them as genuine deletes).
        reconcileAll(
            kind, local, remote, baseline, now, accumulator,
            kind.localDeletesAreAuthoritative,
        )
        return accumulator.toPlan()
    }

    /** Persists the local half of [plan]: deletes, upserts, then snapshot bookkeeping. */
    suspend fun <T> applyLocal(store: RelayStore<T>, plan: RelayPlan<T>) {
        if (plan.localDeletes.isNotEmpty()) store.deleteLocal(plan.localDeletes)
        if (plan.localUpserts.isNotEmpty()) store.upsertLocal(plan.localUpserts)
        if (plan.removedSyncIds.isNotEmpty()) store.removeSnapshots(plan.removedSyncIds)
        if (plan.snapshots.isNotEmpty()) store.saveSnapshots(plan.snapshots)
    }

    private fun <T> indexLocal(
        kind: EntityKind<T>,
        localEntities: List<T>,
        accumulator: MergeAccumulator<T>,
    ): LinkedHashMap<String, T> {
        // Rows written before the sync columns existed have no identity yet.
        val local = LinkedHashMap<String, T>()
        for (raw in localEntities) {
            val entity = if (kind.syncId(raw).isBlank()) {
                kind.withUpdatedAt(kind.rebind(raw, newRelaySyncId()), kind.updatedAt(raw))
                    .also { accumulator.freshlyIdentified += kind.syncId(it) }
            } else {
                raw
            }
            local[kind.syncId(entity)] = entity
        }
        return local
    }

    private fun <T> indexRemote(
        kind: EntityKind<T>,
        local: Map<String, T>,
        remoteEntities: List<T>,
        accumulator: MergeAccumulator<T>,
    ): LinkedHashMap<String, T> {
        val remote = LinkedHashMap<String, T>()
        val unidentified = mutableListOf<T>()
        for (entity in remoteEntities) {
            val id = kind.syncId(entity).takeIf { it.isNotBlank() }
            if (id != null) {
                // The relay log is append-only: a later envelope for the same
                // id is the newer state of that record, so the fold is
                // latest-wins. (First-wins would shadow every edit and
                // tombstone behind the original append, and route the newer
                // envelope into the blank-id adoption path, duplicating the
                // record under a fresh random id.)
                remote[id] = entity
            } else {
                unidentified += entity
            }
        }
        adoptUnidentified(kind, local, remote, unidentified, accumulator)
        return remote
    }

    private fun <T> adoptUnidentified(
        kind: EntityKind<T>,
        local: Map<String, T>,
        remote: LinkedHashMap<String, T>,
        unidentified: List<T>,
        accumulator: MergeAccumulator<T>,
    ) {
        // Records written by a client that does not know our sync ids carry no
        // identity. Adopt the identity of the local row they describe so the
        // first sync against a populated vault does not duplicate everything.
        // Adopted envelopes must be re-appended with their new id (the relay
        // copy still lacks it).
        val adoptableByContentKey = local.values.filter { kind.syncId(it) !in remote }
            .associateBy({ kind.contentKey(it) }, { kind.syncId(it) })
        val claimed = mutableSetOf<String>()
        for (entity in unidentified) {
            val match = adoptableByContentKey[kind.contentKey(entity)]
            val id = match?.takeIf { it !in claimed && it !in remote } ?: newRelaySyncId()
            claimed += id
            remote[id] = kind.rebind(entity, id)
            accumulator.adoptedRemote += id
            accumulator.remoteChanged = true
        }
    }

    private fun <T> reconcileAll(
        kind: EntityKind<T>,
        local: Map<String, T>,
        remote: Map<String, T>,
        baseline: Map<String, RelaySnapshot>,
        now: Long,
        accumulator: MergeAccumulator<T>,
        localDeletesAreAuthoritative: Boolean,
    ) {
        for (id in local.keys + remote.keys) {
            reconcileOne(kind, id, local[id], remote[id], baseline[id], now, accumulator, localDeletesAreAuthoritative)
        }
    }

    private fun <T> reconcileOne(
        kind: EntityKind<T>,
        id: String,
        localEntity: T?,
        remoteEntity: T?,
        base: RelaySnapshot?,
        now: Long,
        accumulator: MergeAccumulator<T>,
        localDeletesAreAuthoritative: Boolean,
    ) {
        if (localEntity != null && remoteEntity != null) {
            reconcilePresent(kind, id, localEntity, remoteEntity, base, now, accumulator)
            return
        }
        if (localEntity != null) {
            reconcileLocalOnly(kind, id, localEntity, base, now, accumulator)
            return
        }
        reconcileRemoteOnly(kind, id, remoteEntity!!, base, now, accumulator, localDeletesAreAuthoritative)
    }

    private fun <T> reconcilePresent(
        kind: EntityKind<T>,
        id: String,
        localEntity: T,
        remoteEntity: T,
        base: RelaySnapshot?,
        now: Long,
        accumulator: MergeAccumulator<T>,
    ) {
        val localHash = kind.contentHash(localEntity)
        val remoteHash = kind.contentHash(remoteEntity)
        val localDeleted = kind.isDeleted(localEntity)
        val remoteDeleted = kind.isDeleted(remoteEntity)

        if (localDeleted || remoteDeleted) {
            resolveTombstone(
                kind, id, localEntity, remoteEntity, localDeleted, remoteDeleted,
                localHash, remoteHash, base, now, accumulator,
            )
            return
        }
        if (localHash == remoteHash) {
            if (id in accumulator.freshlyIdentified) accumulator.localUpserts += localEntity
            if (id in accumulator.adoptedRemote) {
                // The relay copy lacks our sync id; re-append it identified.
                accumulator.remoteOut += kind.rebind(remoteEntity, id)
            }
            accumulator.snapshots += kind.snapshot(
                id,
                localHash,
                kind.updatedAt(localEntity),
                kind.updatedAt(remoteEntity),
                now,
            )
            return
        }
        reconcileDiverged(kind, id, localEntity, remoteEntity, localHash, remoteHash, base, now, accumulator)
    }

    /** One side (or both) is a tombstone: LWW decides delete vs resurrect. */
    private fun <T> resolveTombstone(
        kind: EntityKind<T>,
        id: String,
        localEntity: T,
        remoteEntity: T,
        localDeleted: Boolean,
        remoteDeleted: Boolean,
        localHash: String,
        remoteHash: String,
        base: RelaySnapshot?,
        now: Long,
        accumulator: MergeAccumulator<T>,
    ) {
        val localStamp = kind.updatedAt(localEntity)
        val remoteStamp = kind.updatedAt(remoteEntity)
        // Whichever side changed last wins, whether it is the delete or the edit.
        // Deterministic contentHash tiebreak so two devices agree without talking.
        val localIsNewer = if (localStamp != remoteStamp) {
            localStamp > remoteStamp
        } else {
            ("$localDeleted$localHash") >= ("$remoteDeleted$remoteHash")
        }
        val deleteWins = if (localIsNewer) localDeleted else remoteDeleted
        val stamp = maxOf(localStamp, remoteStamp)
        if (deleteWins) {
            val tombstone = kind.withUpdatedAt(
                if (localDeleted) localEntity else remoteEntity,
                stamp,
            )
            accumulator.localUpserts += tombstone
            if (localHash != remoteHash) {
                accumulator.remoteOut += tombstone
                accumulator.remoteChanged = true
            }
            accumulator.snapshots += kind.snapshot(id, kind.contentHash(tombstone), stamp, stamp, now, true)
            if (base?.deleted != true) {
                accumulator.deletedLocal++
                accumulator.deletedRemote++
            }
        } else {
            val resurrected = kind.withUpdatedAt(
                if (localDeleted) remoteEntity else localEntity,
                stamp,
            )
            accumulator.localUpserts += resurrected
            accumulator.remoteOut += resurrected
            accumulator.remoteChanged = true
            accumulator.pulled++
            accumulator.snapshots += kind.snapshot(id, kind.contentHash(resurrected), stamp, stamp, now)
        }
    }

    private fun <T> reconcileDiverged(
        kind: EntityKind<T>,
        id: String,
        localEntity: T,
        remoteEntity: T,
        localHash: String,
        remoteHash: String,
        base: RelaySnapshot?,
        now: Long,
        accumulator: MergeAccumulator<T>,
    ) {
        val localDirty = base == null || localHash != base.contentHash
        val remoteDirty = base == null || remoteHash != base.contentHash
        val localStamp = kind.updatedAt(localEntity)
        val remoteStamp = kind.updatedAt(remoteEntity)
        // LWW by updatedAt, tiebreak by contentHash so equal timestamps converge.
        val localWins = when {
            localDirty && remoteDirty -> {
                if (localStamp != remoteStamp) localStamp > remoteStamp else localHash >= remoteHash
            }
            else -> localDirty
        }
        if (localWins) {
            applyLocalWins(kind, id, localEntity, localHash, now, accumulator)
        } else {
            applyRemoteWins(kind, id, localEntity, remoteEntity, remoteHash, now, accumulator)
        }
    }

    private fun <T> applyLocalWins(
        kind: EntityKind<T>,
        id: String,
        localEntity: T,
        localHash: String,
        now: Long,
        accumulator: MergeAccumulator<T>,
    ) {
        accumulator.remoteOut += localEntity
        accumulator.remoteChanged = true
        accumulator.pushed++
        if (id in accumulator.freshlyIdentified) accumulator.localUpserts += localEntity
        val stamp = kind.updatedAt(localEntity)
        accumulator.snapshots += kind.snapshot(id, localHash, stamp, stamp, now, kind.isDeleted(localEntity))
    }

    private fun <T> applyRemoteWins(
        kind: EntityKind<T>,
        id: String,
        localEntity: T,
        remoteEntity: T,
        remoteHash: String,
        now: Long,
        accumulator: MergeAccumulator<T>,
    ) {
        // Pulled rows keep the remote timestamp, so the next cycle sees them
        // as clean rather than bouncing the change back.
        val localId = kind.syncId(localEntity).ifBlank { id }
        accumulator.localUpserts += kind.rebind(remoteEntity, localId)
        accumulator.pulled++
        val stamp = kind.updatedAt(remoteEntity)
        accumulator.snapshots += kind.snapshot(id, remoteHash, stamp, stamp, now, kind.isDeleted(remoteEntity))
    }

    private fun <T> reconcileLocalOnly(
        kind: EntityKind<T>,
        id: String,
        localEntity: T,
        base: RelaySnapshot?,
        now: Long,
        accumulator: MergeAccumulator<T>,
    ) {
        if (base == null) {
            if (kind.isDeleted(localEntity)) {
                // A tombstone for something that never synced: just drop the row.
                accumulator.localDeletes += localEntity
                return
            }
            accumulator.remoteOut += localEntity
            accumulator.remoteChanged = true
            accumulator.pushed++
            if (id in accumulator.freshlyIdentified) accumulator.localUpserts += localEntity
            val stamp = kind.updatedAt(localEntity)
            accumulator.snapshots += kind.snapshot(
                id,
                kind.contentHash(localEntity),
                stamp,
                stamp,
                now,
                kind.isDeleted(localEntity),
            )
            return
        }
        if (base.deleted) {
            if (kind.isDeleted(localEntity)) {
                // Tombstone already acknowledged on both sides: garbage-collect it.
                accumulator.localDeletes += localEntity
                accumulator.removed += id
            } else {
                // Recreated locally after the delete settled: push it as live again.
                accumulator.remoteOut += localEntity
                accumulator.remoteChanged = true
                accumulator.pushed++
                val stamp = kind.updatedAt(localEntity)
                accumulator.snapshots += kind.snapshot(
                    id,
                    kind.contentHash(localEntity),
                    stamp,
                    stamp,
                    now,
                )
            }
            return
        }
        if (kind.isDeleted(localEntity)) {
            // Local tombstone, nothing on the relay: both sides deleted it.
            accumulator.localDeletes += localEntity
            accumulator.removed += id
            return
        }
        // Present at baseline but absent from the folded relay log WITHOUT an
        // explicit tombstone: NOT a remote delete. The log may be truncated or
        // partially read (which step 0 of the driver contract rules out only
        // for transport failure, not for a wiped/shortened log). Keep the
        // local row and the baseline untouched; the id will reconcile on a
        // later cycle once its record (or a real tombstone) reappears.
    }

    private fun <T> reconcileRemoteOnly(
        kind: EntityKind<T>,
        id: String,
        remoteEntity: T,
        base: RelaySnapshot?,
        now: Long,
        accumulator: MergeAccumulator<T>,
        localDeletesAreAuthoritative: Boolean,
    ) {
        if (base?.deleted == true) {
            // Tombstone already acknowledged: steady state, nothing to do.
            return
        }
        if (base != null && !kind.isDeleted(remoteEntity) && !localDeletesAreAuthoritative) {
            // Hard-delete store (passwords): rows vanish with only the baseline
            // remembering them, so a healthy-read absence IS a genuine local
            // delete. Propagate a tombstone so the delete does not resurrect.
            // (driveMerge aborts on failed local reads, so absence here is
            // evidence, not a missed read.)
            val tombstone = kind.withUpdatedAt(
                kind.withDeleted(kind.rebind(remoteEntity, id), true),
                maxOf(kind.updatedAt(remoteEntity), now),
            )
            accumulator.remoteOut += tombstone
            accumulator.remoteChanged = true
            accumulator.snapshots += kind.snapshot(
                id,
                kind.contentHash(tombstone),
                kind.updatedAt(tombstone),
                kind.updatedAt(tombstone),
                now,
                true,
            )
            accumulator.deletedRemote++
            return
        }
        if (base != null && !kind.isDeleted(remoteEntity)) {
            // Soft-delete store (notes, todos, contacts, calendar): local
            // deletes carry explicit marks (app tombstone rows or
            // system-provider DELETED flags surfaced via getLocalChanges), so
            // an absence WITHOUT a mark is never a local delete — it is a
            // missed read. Do NOT tombstone the relay on zero evidence.
            // Pull the relay copy back instead; a genuinely deleted row will
            // arrive with its mark and propagate through the normal path.
            val rebound = kind.rebind(remoteEntity, id)
            if (kind.isDeleted(rebound)) {
                accumulator.snapshots += kind.snapshot(
                    id,
                    kind.contentHash(rebound),
                    kind.updatedAt(rebound),
                    kind.updatedAt(rebound),
                    now,
                    true,
                )
                accumulator.deletedRemote++
                return
            }
            accumulator.localUpserts += rebound
            if (id in accumulator.adoptedRemote) {
                // The relay copy lacks our sync id; re-append it identified.
                accumulator.remoteOut += rebound
                accumulator.remoteChanged = true
            }
            accumulator.pulled++
            val stamp = kind.updatedAt(remoteEntity)
            accumulator.snapshots += kind.snapshot(
                id,
                kind.contentHash(rebound),
                stamp,
                stamp,
                now,
            )
            return
        }
        val rebound = kind.rebind(remoteEntity, id)
        if (kind.isDeleted(rebound)) {
            // Fresh remote tombstone with no local row: record it, nothing to pull.
            accumulator.snapshots += kind.snapshot(
                id,
                kind.contentHash(rebound),
                kind.updatedAt(rebound),
                kind.updatedAt(rebound),
                now,
                true,
            )
            accumulator.deletedRemote++
            return
        }
        accumulator.localUpserts += rebound
        if (id in accumulator.adoptedRemote) {
            // The relay copy lacks our sync id; re-append it identified.
            accumulator.remoteOut += rebound
            accumulator.remoteChanged = true
        }
        accumulator.pulled++
        val stamp = kind.updatedAt(remoteEntity)
        accumulator.snapshots += kind.snapshot(
            id,
            kind.contentHash(rebound),
            stamp,
            stamp,
            now,
        )
    }
}
