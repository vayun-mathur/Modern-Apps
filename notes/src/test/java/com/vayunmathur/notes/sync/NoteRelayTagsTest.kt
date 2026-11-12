package com.vayunmathur.notes.sync

import com.vayunmathur.notes.data.Note
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Relay envelope compatibility for tags (#913).
 *
 * The same two literals are pinned on the everysync side
 * (`RemoteNoteTagsParityTest`), which is the cross-module parity check: both
 * shapes must produce these exact bytes, because the relay log stores them and
 * the merge driver hashes them.
 */
class NoteRelayTagsTest {

    /** RelayNoteJson exactly as it was before tags (main 34d3e1c9d). */
    @Serializable
    private data class PreTagsRelayNoteJson(
        val uid: String = "",
        val title: String = "",
        val content: String = "",
        val blocksJson: String? = null,
        val position: Double = 0.0,
        val deleted: Boolean = false,
        val updatedAt: Long = 0L,
    )

    // Same configuration as RelayNoteProvider's `json`.
    private val json = Json { ignoreUnknownKeys = true }

    private val note = Note(
        id = 9, title = "Plan", content = "body", position = 2.5,
        blocks = """{"blocks":[]}""", syncId = "abc123", updatedAt = 1_700_000_000_000, deleted = false,
    )

    @Test fun `untagged note encodes byte-identical to the pre-tags envelope`() {
        val now = json.encodeToString(RelayNoteJson.serializer(), note.toRelayJson())
        val before = json.encodeToString(
            PreTagsRelayNoteJson.serializer(),
            PreTagsRelayNoteJson("abc123", "Plan", "body", """{"blocks":[]}""", 2.5, false, 1_700_000_000_000),
        )
        assertEquals(before, now)
        assertEquals(UNTAGGED_GOLDEN, now)
        // The content hash is over the canonical bytes; identical bytes mean an identical hash.
        val canonicalNow = json.encodeToString(
            RelayNoteJson.serializer(),
            note.toRelayJson().copy(uid = "", position = 0.0, updatedAt = 0L, deleted = false),
        )
        val canonicalBefore = json.encodeToString(
            PreTagsRelayNoteJson.serializer(),
            PreTagsRelayNoteJson("", "Plan", "body", """{"blocks":[]}"""),
        )
        assertEquals(canonicalBefore, canonicalNow)
    }

    @Test fun `tagged note encodes tags last and round-trips`() {
        val tagged = note.copy(tags = listOf("work", "home office"))
        val payload = json.encodeToString(RelayNoteJson.serializer(), tagged.toRelayJson())
        assertEquals(TAGGED_GOLDEN, payload)
        val back = json.decodeFromString(RelayNoteJson.serializer(), payload).toNote(9)
        assertEquals(tagged, back)
    }

    @Test fun `an older peer's tagless payload decodes as untagged`() {
        val back = json.decodeFromString(RelayNoteJson.serializer(), UNTAGGED_GOLDEN).toNote(1)
        assertEquals(emptyList(), back.tags)
        assertEquals("Plan", back.title)
    }

    @Test fun `a tagged payload decodes on a pre-tags build by dropping the unknown key`() {
        // What an older notes build does with a newer envelope: ignoreUnknownKeys, tags lost.
        val old = json.decodeFromString(PreTagsRelayNoteJson.serializer(), TAGGED_GOLDEN)
        assertEquals("Plan", old.title)
        assertEquals("abc123", old.uid)
    }

    @Test fun `received tags are normalised`() {
        val remote = RelayNoteJson(uid = "x", tags = listOf("  Work ", "work", "", "a   b", "y".repeat(50)))
        val tags = remote.toNote(1).tags
        assertEquals(listOf("Work", "a b", "y".repeat(40)), tags)
        assertTrue(RelayNoteJson(uid = "x").toNote(1).tags.isEmpty())
    }

    companion object {
        const val UNTAGGED_GOLDEN =
            """{"uid":"abc123","title":"Plan","content":"body","blocksJson":"{\"blocks\":[]}",""" +
                """"position":2.5,"updatedAt":1700000000000}"""
        const val TAGGED_GOLDEN =
            """{"uid":"abc123","title":"Plan","content":"body","blocksJson":"{\"blocks\":[]}",""" +
                """"position":2.5,"updatedAt":1700000000000,"tags":["work","home office"]}"""
    }
}
