package com.vayunmathur.notes.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString

class WearNotesProtocolTest {

    private fun note(
        syncId: String,
        updatedAt: Long,
        title: String = "Note $syncId",
        body: String = "Body $syncId",
        deleted: Boolean = false,
    ) = WearNote(syncId = syncId, title = title, body = body, updatedAt = updatedAt, deleted = deleted)

    @Test
    fun goldenSnapshotJson() {
        val snapshot = WearNotesSnapshot(
            generatedAtMillis = 1_000L,
            notes = listOf(note("abc", 900L, title = "Shopping", body = "Milk")),
        )
        assertEquals(
            """{"generatedAtMillis":1000,"notes":[{"syncId":"abc","title":"Shopping",""" +
                """"body":"Milk","updatedAt":900}]}""",
            WearNotesProtocol.json.encodeToString(snapshot),
        )
    }

    @Test
    fun goldenOpJson() {
        val op = WearNoteOp(WearOpKind.UPSERT, note("abc", 900L, title = "Shopping", body = "Milk"))
        assertEquals(
            """{"kind":"UPSERT","note":{"syncId":"abc","title":"Shopping","body":"Milk","updatedAt":900}}""",
            WearNotesProtocol.json.encodeToString(op),
        )
    }

    @Test
    fun snapshotAndOpRoundTrip() {
        val snapshot = WearNotesSnapshot(9L, listOf(note("a", 1L), note("b", 2L)), truncated = true)
        assertEquals(
            snapshot,
            WearNotesProtocol.json.decodeFromString<WearNotesSnapshot>(
                WearNotesProtocol.json.encodeToString(snapshot),
            ),
        )
        val op = WearNoteOp(WearOpKind.DELETE, note("a", 3L, deleted = true))
        assertEquals(
            op,
            WearNotesProtocol.json.decodeFromString<WearNoteOp>(
                WearNotesProtocol.json.encodeToString(op),
            ),
        )
    }

    @Test
    fun buildKeepsMostRecentAndDropsDeleted() {
        val snapshot = buildWearSnapshot(
            candidates = listOf(note("old", 100L), note("new", 300L), note("gone", 400L, deleted = true)),
            generatedAtMillis = 500L,
        )
        assertEquals(listOf("new", "old"), snapshot.notes.map { it.syncId })
        assertFalse(snapshot.truncated)
    }

    @Test
    fun buildCapsCountFieldsAndBytes() {
        val candidates = (1..(WearNotesProtocol.MAX_NOTES + 20)).map {
            note("n$it", it.toLong(), title = "t".repeat(300), body = "b".repeat(5_000))
        }
        val snapshot = buildWearSnapshot(candidates, 0L)
        assertTrue(snapshot.notes.size <= WearNotesProtocol.MAX_NOTES)
        assertTrue(snapshot.truncated)
        assertTrue(snapshot.notes.all {
            it.title.length == WearNotesProtocol.MAX_TITLE_CHARS &&
                it.body.length == WearNotesProtocol.MAX_BODY_CHARS && it.bodyTruncated
        })
        val bytes = WearNotesProtocol.json.encodeToString(snapshot).encodeToByteArray().size
        assertTrue(bytes <= WearNotesProtocol.MAX_JSON_BYTES, "snapshot $bytes bytes over cap")
        // Most recent survives the caps.
        assertEquals("n${WearNotesProtocol.MAX_NOTES + 20}", snapshot.notes.first().syncId)
    }

    @Test
    fun lwwNewerWinsOlderLoses() {
        val incumbent = note("a", 200L, title = "Phone")
        assertTrue(wearNoteWins(note("a", 300L, title = "Watch"), incumbent))
        assertFalse(wearNoteWins(note("a", 100L, title = "Watch"), incumbent))
        assertFalse(wearNoteWins(incumbent, incumbent))
    }

    @Test
    fun lwwTieBreaksTombstoneThenLargerText() {
        val live = note("a", 200L, title = "Same", body = "same")
        assertTrue(wearNoteWins(live.copy(deleted = true), live))
        assertFalse(wearNoteWins(live, live.copy(deleted = true)))
        assertTrue(wearNoteWins(live.copy(title = "Zebra"), live))
        assertTrue(wearNoteWins(live.copy(body = "zebra"), live))
    }

    @Test
    fun mergeKeepsWinningPendingOpAndHidesPendingDelete() {
        val snapshot = WearNotesSnapshot(
            1_000L,
            listOf(note("a", 100L, title = "Phone"), note("b", 100L)),
        )
        val edited = note("a", 200L, title = "Watch edit")
        val merged = mergeWearSnapshot(
            snapshot,
            listOf(
                WearNoteOp(WearOpKind.UPSERT, edited),
                WearNoteOp(WearOpKind.DELETE, note("b", 200L, deleted = true)),
            ),
        )
        assertEquals(listOf(edited), merged)
    }

    @Test
    fun mergeDropsPendingOpThatLosesToSnapshot() {
        val phone = note("a", 300L, title = "Phone newer")
        val snapshot = WearNotesSnapshot(1_000L, listOf(phone))
        val stale = note("a", 200L, title = "Watch stale")
        assertEquals(
            listOf(phone),
            mergeWearSnapshot(snapshot, listOf(WearNoteOp(WearOpKind.UPSERT, stale))),
        )
        // Reflected: the stale op is safe to drop; a winning op is not.
        assertEquals(
            listOf(WearNoteOp(WearOpKind.UPSERT, stale)),
            reflectedOps(snapshot, listOf(WearNoteOp(WearOpKind.UPSERT, stale))),
        )
        assertEquals(
            emptyList(),
            reflectedOps(snapshot, listOf(WearNoteOp(WearOpKind.UPSERT, note("a", 400L)))),
        )
        // A delete is reflected once the note is absent from the live snapshot.
        assertEquals(
            1,
            reflectedOps(
                WearNotesSnapshot(1_000L, emptyList()),
                listOf(WearNoteOp(WearOpKind.DELETE, note("a", 200L, deleted = true))),
            ).size,
        )
    }
}
