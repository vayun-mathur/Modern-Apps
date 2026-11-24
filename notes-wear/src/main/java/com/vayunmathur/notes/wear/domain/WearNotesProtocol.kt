package com.vayunmathur.notes.wear.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Phone <-> watch sync protocol for the Wear OS Notes companion.
 *
 * SINGLE SOURCE OF TRUTH, DELIBERATELY DUPLICATED: Gradle cannot let one
 * application module depend on another, so the wear module (`:notes-wear`,
 * applicationId `com.vayunmathur.notes`) carries a copy of this file under
 * `com.vayunmathur.notes.wear.domain`. Change both copies together; each
 * side's unit tests pin the same golden JSON so a one-sided edit fails loudly.
 *
 * Transport (WearLink, :library:wearlink — RFCOMM, no Play services):
 * - Presence is a completed handshake for [CAPABILITY] (the appId).
 * - Data items: [SNAPSHOT_PATH] phone -> watch (bounded recent notes) and
 *   [WATCH_STATE_PATH] watch -> phone (what the watch applied + pending ops).
 * - Messages: [OP_PATH] watch -> phone (one create/edit/delete) and
 *   [REQUEST_SYNC_PATH] watch -> phone (ask for a fresh snapshot).
 *   Delivery needs a live session; nothing wakes a dead phone process.
 *
 * Conflict policy — last-writer-wins per note, stated plainly: for one
 * `syncId`, the version with the larger `updatedAt` wins on both sides.
 * Ties break deterministically: a tombstone beats a live note, then the
 * lexicographically larger (title, body) wins. Watch edits stamp
 * `max(now, editedVersion.updatedAt + 1)` so an edit always beats the
 * version it was made from, even with a skewed watch clock; an edit made
 * from a stale copy can still lose to a newer phone edit — that is LWW,
 * not merging, and no text merge is attempted or claimed.
 *
 * Honest write scope: the watch body is plain text (`Note.content`). A
 * note whose phone body contains image/ink blocks is flagged
 * `textOnly = false`: the watch renders its text but offers no edit (a
 * text-only replacement would destroy the blocks). Delete is offered for
 * every note — a tombstone destroys nothing the phone cannot still show
 * as deleted — and create/edit only ever produce a single text block on
 * the phone. No watch action claims a write until the phone applies it
 * and the next snapshot reflects it; unreflected ops stay pending.
 */
object WearNotesProtocol {
    /** WearLink appId the phone requires in the peer handshake. */
    const val CAPABILITY = "notes_wear_companion"

    /** Data-item path (phone -> watch): the [WearNotesSnapshot] JSON. */
    const val SNAPSHOT_PATH = "/notes/snapshot"

    /** Data-item path (watch -> phone): the [WearWatchState] JSON. */
    const val WATCH_STATE_PATH = "/notes/watch-state"

    /** Message path (watch -> phone): one [WearNoteOp] JSON. */
    const val OP_PATH = "/notes/op"

    /** Message path (watch -> phone): ask the phone to publish a fresh snapshot. */
    const val REQUEST_SYNC_PATH = "/notes/request-sync"

    /** Most-recent notes in one snapshot; a watch renders a short list. */
    const val MAX_NOTES = 50

    /** Largest snapshot JSON in UTF-8 bytes (near the old 100 KB DataItem ceiling). */
    const val MAX_JSON_BYTES = 90_000

    /** Field caps applied when a snapshot is built. */
    const val MAX_TITLE_CHARS = 120
    const val MAX_BODY_CHARS = 4_000

    /** Wire JSON: unknown keys ignored so a newer side never breaks an older one. */
    val json: Json = Json { ignoreUnknownKeys = true }
}

/** One note trimmed to watch size. [syncId] is the phone note's stable sync identity. */
@Serializable
data class WearNote(
    val syncId: String,
    val title: String,
    /** Plain-text body, capped at [WearNotesProtocol.MAX_BODY_CHARS]. */
    val body: String = "",
    /** Last-writer-wins stamp (epoch millis / hybrid stamp on the phone). */
    val updatedAt: Long = 0L,
    val deleted: Boolean = false,
    /** False when the phone body has image/ink blocks: watch is read-only for it. */
    val textOnly: Boolean = true,
    /** True when [body] was cut at the cap; the full text stays on the phone. */
    val bodyTruncated: Boolean = false,
)

/** The phone's bounded recent-notes snapshot for the watch ([WearNotesProtocol.SNAPSHOT_PATH]). */
@Serializable
data class WearNotesSnapshot(
    val generatedAtMillis: Long = 0L,
    /** Live notes only, most recently updated first. */
    val notes: List<WearNote> = emptyList(),
    /** True when the count/byte caps dropped notes. */
    val truncated: Boolean = false,
)

/** What the watch last applied, published at [WearNotesProtocol.WATCH_STATE_PATH]. */
@Serializable
data class WearWatchState(
    val appliedGeneratedAtMillis: Long = 0L,
    val noteCount: Int = 0,
    /** Watch-originated ops not yet reflected in a phone snapshot. */
    val pendingOps: Int = 0,
)

/** Kind of a watch-originated change. */
@Serializable
enum class WearOpKind { UPSERT, DELETE }

/** One watch-originated create/edit (UPSERT) or delete (DELETE), as [WearNotesProtocol.OP_PATH]. */
@Serializable
data class WearNoteOp(
    val kind: WearOpKind,
    val note: WearNote,
)

/**
 * True when [candidate] wins over [incumbent] under the stated LWW policy
 * (see [WearNotesProtocol]): larger `updatedAt`, then tombstone, then the
 * lexicographically larger (title, body). Identical versions never win.
 */
fun wearNoteWins(candidate: WearNote, incumbent: WearNote): Boolean {
    if (candidate.updatedAt != incumbent.updatedAt) return candidate.updatedAt > incumbent.updatedAt
    if (candidate.deleted != incumbent.deleted) return candidate.deleted
    if (candidate.title != incumbent.title) return candidate.title > incumbent.title
    return candidate.body > incumbent.body
}

/**
 * Builds a bounded snapshot: most-recently-updated live notes first, field
 * caps applied, then [WearNotesProtocol.MAX_NOTES] and
 * [WearNotesProtocol.MAX_JSON_BYTES] enforced by dropping the oldest kept
 * notes (recent notes — the watch's main screen — survive longest).
 */
fun buildWearSnapshot(candidates: List<WearNote>, generatedAtMillis: Long): WearNotesSnapshot {
    val sorted = candidates
        .filter { !it.deleted }
        .sortedWith(compareByDescending<WearNote> { it.updatedAt }.thenBy { it.syncId })
        .map { note ->
            note.copy(
                title = note.title.take(WearNotesProtocol.MAX_TITLE_CHARS),
                body = note.body.take(WearNotesProtocol.MAX_BODY_CHARS),
                bodyTruncated = note.bodyTruncated || note.body.length > WearNotesProtocol.MAX_BODY_CHARS,
            )
        }
    var kept = sorted.take(WearNotesProtocol.MAX_NOTES)
    var truncated = kept.size < sorted.size
    var snapshot = WearNotesSnapshot(generatedAtMillis, kept, truncated)
    while (kept.isNotEmpty() &&
        WearNotesProtocol.json.encodeToString(snapshot).encodeToByteArray().size >
        WearNotesProtocol.MAX_JSON_BYTES
    ) {
        kept = kept.dropLast(1)
        truncated = true
        snapshot = snapshot.copy(notes = kept, truncated = true)
    }
    return snapshot
}

/**
 * Merges a phone [snapshot] into the watch's local view: a pending
 * watch-originated op wins over the snapshot copy of the same note when
 * [wearNoteWins] says so (the phone has not applied/reflected it yet);
 * otherwise the snapshot is truth. Pending deletes hide their note.
 * Result is most-recent first and contains no tombstones.
 */
fun mergeWearSnapshot(
    snapshot: WearNotesSnapshot,
    pendingOps: List<WearNoteOp>,
): List<WearNote> {
    val byId = snapshot.notes.associateBy { it.syncId }.toMutableMap()
    for (op in pendingOps) {
        val local = op.note
        val remote = byId[local.syncId]
        if (remote == null || wearNoteWins(local, remote)) {
            if (local.deleted) byId.remove(local.syncId) else byId[local.syncId] = local
        }
    }
    return byId.values
        .filter { !it.deleted }
        .sortedWith(compareByDescending<WearNote> { it.updatedAt }.thenBy { it.syncId })
}

/** Ops already reflected by [snapshot] (snapshot version is at least as new) and safe to drop. */
fun reflectedOps(snapshot: WearNotesSnapshot, pendingOps: List<WearNoteOp>): List<WearNoteOp> =
    pendingOps.filter { op ->
        val remote = snapshot.notes.firstOrNull { it.syncId == op.note.syncId }
        when {
            // A delete is reflected when the note is gone from the live snapshot
            // at a stamp that cannot beat it, or simply absent entirely.
            op.kind == WearOpKind.DELETE -> remote == null
            remote == null -> false
            else -> !wearNoteWins(op.note, remote)
        }
    }
