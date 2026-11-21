package com.vayunmathur.office.platform

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.office.sync.OfficeSync
import com.vayunmathur.office.sync.sign
import com.vayunmathur.office.sync.verify
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * A persisted record that an online document was deleted.
 *
 * Before this, deleting an online document had no representation at all:
 * the local index entry could go away, but the relay keeps the document's
 * whole op log forever, so the next pull by a stale peer (or a replayed
 * invite) rebuilt and re-listed a document everyone thought was gone.
 * A tombstone is the delete's durable proof, checked on every pull path:
 *
 *  - [OfficeViewModel.pullInboxInvites] never re-adds a tombstoned doc
 *    from an invite;
 *  - [OfficeViewModel.openOnlineDocument] refuses a tombstoned doc and
 *    honours a verified remote tombstone instead of pulling its ops;
 *  - [OfficeViewModel.syncDoc] / live merges no-op for a tombstoned doc,
 *    so ops pushed by a peer that has not seen the delete yet cannot
 *    resurrect it;
 *  - [OfficeViewModel.refreshOnline] sweeps the index for owner-signed
 *    remote tombstones.
 *
 * An owner's delete is broadcast as an owner-signed [SignedDocDelete] on
 * the `deleted:<docId>` channel (the same pattern as `title:<docId>`),
 * and only owner-signed records are honoured from the relay: a member
 * deleting a document only removes it for themselves (local tombstone,
 * no broadcast), so no editor can delete a document for everyone.
 *
 * Tombstones are never garbage-collected here. Each one is a doc id plus
 * a timestamp — bounded by the number of documents a user deletes, not
 * by edits — and expiring one would re-open exactly the resurrection
 * window it exists to close, because the relay op log outlives it.
 *
 * There is deliberately no CRDT op-log compaction alongside this:
 * pruning relay ops below a cursor is unsafe in this design. The only
 * cursor is `crdtCursor:<docId>` (what THIS device has pulled) — the
 * roster carries no per-peer ack, so there is no all-peers-synced point
 * to prune below; the relay client offers append/pull only (no
 * truncate), and fresh peers rebuild by pulling from 0; and in the Rust
 * tree CRDT, deleted nodes are still `parent`/`left` anchors for later
 * nodes while `apply` re-inserts unknown ids, so a stale peer
 * re-delivering pre-prune ops would resurrect pruned nodes. The
 * `toStateNodesJson` re-baseline (see key rotation) appends a snapshot
 * on top of the old log rather than replacing it, so it is not a
 * compaction watermark either.
 */
@Serializable
data class OfficeDocTombstone(
    val docId: String,
    val deletedAt: Long,
    val author: String = "",
)

/** An owner-signed [OfficeDocTombstone] record, as carried on `deleted:<docId>`. */
@Serializable
data class SignedDocDelete(val tombstone: OfficeDocTombstone, val sig: String)

/** Pure encode/decode/filter helpers for the persisted tombstone list. */
object OfficeDocTombstones {
    /** DataStore key holding the JSON tombstone list. */
    const val KEY = "officeDeletedDocs"

    /** Canonical bytes the owner signs for a delete (binds doc + instant). */
    fun signingBytes(docId: String, deletedAt: Long): ByteArray =
        "$docId|deleted|$deletedAt".encodeToByteArray()

    fun encode(tombstones: List<OfficeDocTombstone>): String =
        OfficeSync.json.encodeToString(tombstones)

    /** Tolerant decode: a corrupt store reads as "no tombstones", never a crash. */
    fun decode(raw: String?): List<OfficeDocTombstone> =
        raw?.let {
            runCatching { OfficeSync.json.decodeFromString<List<OfficeDocTombstone>>(it) }.getOrNull()
        } ?: emptyList()

    fun isDeleted(tombstones: List<OfficeDocTombstone>, docId: String): Boolean =
        tombstones.any { it.docId == docId }

    /** Adds (or replaces, keeping the newest stamp) [tombstone] in [tombstones]. */
    fun upsert(
        tombstones: List<OfficeDocTombstone>,
        tombstone: OfficeDocTombstone,
    ): List<OfficeDocTombstone> {
        val existing = tombstones.firstOrNull { it.docId == tombstone.docId }
        if (existing != null && existing.deletedAt >= tombstone.deletedAt) return tombstones
        return tombstones.filterNot { it.docId == tombstone.docId } + tombstone
    }

    /** Index entries that survive [tombstones] (order preserved). */
    fun filterLive(
        metas: List<OfficeDocMeta>,
        tombstones: List<OfficeDocTombstone>,
    ): List<OfficeDocMeta> {
        if (tombstones.isEmpty()) return metas
        val deleted = tombstones.map { it.docId }.toSet()
        return metas.filterNot { it.docId in deleted }
    }
}

internal fun OfficeViewModel.loadTombstones(ds: DataStoreUtils): List<OfficeDocTombstone> =
    OfficeDocTombstones.decode(ds.getString(OfficeDocTombstones.KEY))

internal suspend fun OfficeViewModel.saveTombstones(
    ds: DataStoreUtils,
    tombstones: List<OfficeDocTombstone>,
) {
    ds.setString(OfficeDocTombstones.KEY, OfficeDocTombstones.encode(tombstones))
}

internal fun OfficeViewModel.isDocTombstoned(ds: DataStoreUtils, docId: String): Boolean =
    OfficeDocTombstones.isDeleted(loadTombstones(ds), docId)

/**
 * Records [tombstone] and removes every local trace of the document:
 * index entry, persisted CRDT tree, pull cursor, and un-acked pending
 * ops. After this, no pull path can list or rebuild the document.
 */
internal suspend fun OfficeViewModel.applyDocTombstone(
    ds: DataStoreUtils,
    tombstone: OfficeDocTombstone,
) {
    saveTombstones(ds, OfficeDocTombstones.upsert(loadTombstones(ds), tombstone))
    indexMutex.withLock {
        val index = loadIndex(ds)
        val live = OfficeDocTombstones.filterLive(index, listOf(tombstone))
        if (live.size != index.size) saveIndex(ds, live)
    }
    ds.removeKeys(
        listOf(
            "crdt:${tombstone.docId}",
            "crdtCursor:${tombstone.docId}",
            "crdtPending:${tombstone.docId}",
        ),
    )
    if (currentDocId == tombstone.docId) {
        OfficeSync.stopLive()
        currentTree?.close()
        currentTree = null
        currentDocId = null
        currentDocKey = null
    }
}

/**
 * Reads the `deleted:<docId>` channel and returns the newest tombstone
 * whose signature verifies against [ownerKey], or null. Like
 * [OfficeViewModel.fetchTitle], a failed pull means "unknown" (null),
 * never "not deleted".
 */
internal suspend fun OfficeViewModel.fetchDocDelete(
    docId: String,
    key: ByteArray,
    ownerKey: ByteArray?,
): OfficeDocTombstone? {
    if (ownerKey == null) return null
    val res = OfficeSync.pullDocActions("deleted:$docId", key, 0) ?: return null
    var latest: OfficeDocTombstone? = null
    for (item in res.items) {
        val signed = runCatching {
            syncJson.decodeFromString<SignedDocDelete>(item)
        }.getOrNull() ?: continue
        val verified = runCatching {
            OfficeSync.verify(
                ownerKey,
                OfficeDocTombstones.signingBytes(
                    signed.tombstone.docId,
                    signed.tombstone.deletedAt,
                ),
                Base64.decode(signed.sig),
            )
        }.getOrDefault(false)
        if (verified && signed.tombstone.docId == docId &&
            (latest == null || signed.tombstone.deletedAt > latest.deletedAt)
        ) {
            latest = signed.tombstone
        }
    }
    return latest
}

/** Checks the open document for a remote tombstone (rides the live poll, like [pollTitle]). */
internal suspend fun OfficeViewModel.pollDocDelete(docId: String, key: ByteArray) {
    if (currentDocId != docId) return
    val ds = DataStoreUtils.getInstance(getApplication())
    if (isDocTombstoned(ds, docId)) return
    val tombstone = fetchDocDelete(docId, key, currentOwnerKey) ?: return
    applyDocTombstone(ds, tombstone)
}

/** Sweeps every indexed document for a verified remote tombstone (called on refresh). */
internal suspend fun OfficeViewModel.sweepDeletedDocs(ds: DataStoreUtils) {
    val index = indexMutex.withLock { loadIndex(ds) }
    for (meta in index) {
        if (isDocTombstoned(ds, meta.docId)) continue
        val key = runCatching { Base64.decode(meta.keyB64) }.getOrNull() ?: continue
        val ownerKey = if (meta.owner) {
            runCatching { OfficeSync.publicBundle }.getOrNull()
        } else {
            meta.ownerKeyB64.takeIf { it.isNotBlank() }
                ?.let { runCatching { Base64.decode(it) }.getOrNull() }
        }
        val tombstone = runCatching { fetchDocDelete(meta.docId, key, ownerKey) }
            .getOrNull() ?: continue
        applyDocTombstone(ds, tombstone)
    }
}

/**
 * Deletes an online document for this device: persists a tombstone,
 * drops the local index/CRDT state, and — for the owner only —
 * broadcasts the owner-signed tombstone so peers honour it on their
 * next pull/open/refresh. A non-owner's delete is local-only.
 * [onResult] receives whether the local deletion was recorded.
 */
fun OfficeViewModel.deleteOnlineDocument(docId: String, onResult: (Boolean) -> Unit = {}) {
    viewModelScope.launch(Dispatchers.IO) {
        val ok = runCatching {
            OfficeSync.init(getApplication<Application>())
            val ds = DataStoreUtils.getInstance(getApplication())
            val meta = indexMutex.withLock { loadIndex(ds) }
                .firstOrNull { it.docId == docId }
            val tombstone = OfficeDocTombstone(
                docId = docId,
                deletedAt = System.currentTimeMillis(),
                author = OfficeSync.deviceId,
            )
            applyDocTombstone(ds, tombstone)
            // Owner only: sign + publish so peers can verify and honour it.
            // Fire-and-forget like the signed title channel: the local
            // tombstone is already durable if the relay is unreachable.
            if (meta != null && meta.owner) {
                val key = runCatching { Base64.decode(meta.keyB64) }.getOrNull()
                if (key != null) {
                    val sig = Base64.encode(
                        OfficeSync.sign(
                            OfficeDocTombstones.signingBytes(docId, tombstone.deletedAt),
                        ),
                    )
                    OfficeSync.appendDocActions(
                        "deleted:$docId",
                        key,
                        listOf(syncJson.encodeToString(SignedDocDelete(tombstone, sig))),
                    )
                }
            }
            true
        }.getOrDefault(false)
        withContext(Dispatchers.Main) { onResult(ok) }
    }
}
