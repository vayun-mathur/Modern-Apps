package com.vayunmathur.everysync.sync

import com.vayunmathur.everysync.data.RemoteTodo
import com.vayunmathur.everysync.sync.adapters.TodoKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for the relay-log fold in [RelayMerge.merge]: the log is
 * append-only, so when one sync id appears more than once the LATER envelope
 * is the record's current state.
 */
class RelayMergeFoldTest {
    private fun todo(id: String, title: String, at: Long, deleted: Boolean = false) =
        RemoteTodo(uid = id, title = title, updatedAt = at, deleted = deleted)

    private fun base(e: RemoteTodo) = RelaySnapshot(
        syncId = e.uid, kind = TodoKind.kind, contentHash = TodoKind.contentHash(e),
        localUpdatedAt = e.updatedAt, remoteModified = e.updatedAt, lastSyncedAt = 0L,
        deleted = e.deleted,
    )

    @Test fun laterEnvelopeForSameIdWinsOverEarlier() {
        val v1 = todo("a", "Original", 100)
        val v2 = todo("a", "Edited", 200)
        val plan = RelayMerge.merge(TodoKind, listOf(v1), listOf(v1, v2), mapOf("a" to base(v1)), 1_000)
        // The edit is pulled as an update of "a" — not adopted as a new record
        assertEquals(1, plan.pulled)
        assertEquals(listOf(v2), plan.localUpserts)
        assertEquals(0, plan.pushed)
    }

    @Test fun laterTombstoneForSameIdDeletesLocally() {
        val live = todo("a", "Original", 100)
        val tomb = todo("a", "Original", 200, deleted = true)
        val plan = RelayMerge.merge(TodoKind, listOf(live), listOf(live, tomb), mapOf("a" to base(live)), 1_000)
        assertTrue(plan.localDeletes.any { it.uid == "a" } || plan.localUpserts.any { it.deleted },
            "deletes=${plan.localDeletes} upserts=${plan.localUpserts}")
    }

    @Test fun identicalDuplicatesStillMergeOnce() {
        val a = todo("x", "One", 100)
        val plan = RelayMerge.merge(TodoKind, emptyList(), listOf(a, a.copy()), emptyMap(), 1_000)
        assertEquals(1, plan.localUpserts.size)
        assertEquals("x", plan.localUpserts.single().uid)
    }
}
