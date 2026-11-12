package com.vayunmathur.everysync.data

import com.vayunmathur.everysync.data.sink.NoteSink
import com.vayunmathur.everysync.sync.adapters.NoteKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * [RemoteNote] carries note tags (notes #913) and must stay byte-compatible
 * with notes' `RelayNoteJson`. The two literals below are pinned verbatim in
 * notes' `NoteRelayTagsTest`; if either side reorders or renames a field one
 * of the two tests fails.
 */
class RemoteNoteTagsParityTest {

    /** RemoteNote exactly as it was before tags. */
    @Serializable
    private data class PreTagsRemoteNote(
        val uid: String,
        val title: String = "",
        val content: String = "",
        val blocksJson: String? = null,
        val position: Double = 0.0,
        val deleted: Boolean = false,
        val updatedAt: Long = 0L,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val untagged = RemoteNote(
        uid = "abc123", title = "Plan", content = "body", blocksJson = """{"blocks":[]}""",
        position = 2.5, deleted = false, updatedAt = 1_700_000_000_000,
    )

    @Test fun untaggedEnvelopeAndHashAreUnchanged() {
        assertEquals(UNTAGGED_GOLDEN, NoteKind.toJson(untagged))
        val before = PreTagsRemoteNote("abc123", "Plan", "body", """{"blocks":[]}""", 2.5, false, 1_700_000_000_000)
        assertEquals(json.encodeToString(PreTagsRemoteNote.serializer(), before), NoteKind.toJson(untagged))
        val canonicalBefore = json.encodeToString(
            PreTagsRemoteNote.serializer(),
            PreTagsRemoteNote("", "Plan", "body", """{"blocks":[]}"""),
        )
        assertEquals(canonicalBefore, NoteKind.canonical(untagged))
    }

    @Test fun taggedEnvelopeMatchesNotesAndRoundTrips() {
        val tagged = untagged.copy(tags = listOf("work", "home office"))
        assertEquals(TAGGED_GOLDEN, NoteKind.toJson(tagged))
        assertEquals(tagged, NoteKind.fromJson(TAGGED_GOLDEN))
        assertEquals(tagged, NoteSink.fromPayload(TAGGED_GOLDEN))
    }

    @Test fun aTagEditIsAContentChange() {
        val tagged = untagged.copy(tags = listOf("work"))
        assertNotEquals(NoteKind.contentHash(untagged), NoteKind.contentHash(tagged))
        // ...but a reorder still is not.
        assertEquals(NoteKind.contentHash(tagged), NoteKind.contentHash(tagged.copy(position = 99.0)))
    }

    @Test fun tagsPayloadFromAnOlderNotesBuildDecodesUntagged() {
        assertEquals(emptyList(), NoteKind.fromJson(UNTAGGED_GOLDEN)!!.tags)
    }

    private companion object {
        const val UNTAGGED_GOLDEN =
            """{"uid":"abc123","title":"Plan","content":"body","blocksJson":"{\"blocks\":[]}",""" +
                """"position":2.5,"updatedAt":1700000000000}"""
        const val TAGGED_GOLDEN =
            """{"uid":"abc123","title":"Plan","content":"body","blocksJson":"{\"blocks\":[]}",""" +
                """"position":2.5,"updatedAt":1700000000000,"tags":["work","home office"]}"""
    }
}
