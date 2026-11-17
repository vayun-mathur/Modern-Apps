@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.notes.data

import kotlin.uuid.Uuid
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.ink.SerializedStroke
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One piece of a note's body. A note is an ordered list of blocks so it can mix
 * typed text, inserted images, and handwriting like OneNote / Apple Notes.
 *
 * Legacy text-only notes have no stored blocks; they are read back as a single
 * [Text] block built from [Note.content] (see [Note.blockList]).
 */
@Serializable
sealed interface NoteBlock {
    val id: String

    /** Text block holding markdown content. */
    @Serializable
    @SerialName("text")
    data class Text(
        val markdown: String = "",
        override val id: String = randomBlockId(),
    ) : NoteBlock

    /** [fileName] names an image file in the note-images dir (see [com.vayunmathur.notes.platform.NoteImageStore]). */
    @Serializable
    @SerialName("image")
    data class Image(
        val fileName: String,
        /** Display width as a fraction of the editor width, so images can be resized. */
        val widthFraction: Float = 1f,
        override val id: String = randomBlockId(),
    ) : NoteBlock

    /** Handwriting strokes drawn on a canvas [heightDp] tall. */
    @Serializable
    @SerialName("ink")
    data class Ink(
        val strokes: List<SerializedStroke> = emptyList(),
        val heightDp: Int = 300,
        override val id: String = randomBlockId(),
    ) : NoteBlock
}

fun randomBlockId(): String = Uuid.random().toString()

/**
 * The full body of a note: an ordered list of inline [blocks]. Serialized to the
 * [Note.blocks] JSON column.
 */
@Serializable
data class NoteBody(
    val blocks: List<NoteBlock> = emptyList(),
)

// ignoreUnknownKeys lets us drop any legacy fields (e.g. an old "floating" list
// from earlier builds) so older notes still parse instead of crashing.
private val blockJson = Json { ignoreUnknownKeys = true }

private const val TAG = "NoteBody"

/** The note body: parses stored [Note.blocks], or falls back to a single text block. */
fun Note.body(): NoteBody {
    val json = blocks
    if (json.isNullOrBlank()) return NoteBody(blocks = listOf(NoteBlock.Text(content)))
    return try {
        blockJson.decodeFromString<NoteBody>(json)
    } catch (e: SerializationException) {
        Log.status(TAG, "unparseable blocks JSON, falling back to plain text", e)
        NoteBody(blocks = listOf(NoteBlock.Text(content)))
    }
}

/**
 * Returns a copy storing [newBody], keeping [Note.content] in sync with the text
 * blocks so search, list previews, sharing, and the assistant intents keep working
 * on plain text.
 */
fun Note.withBody(newBody: NoteBody): Note = copy(
    blocks = blockJson.encodeToString(newBody),
    // Trim each block, not just the whole: trailing spaces at a block edge
    // are invisible in the editor but would otherwise leak into the search
    // index and list previews.
    content = newBody.blocks.filterIsInstance<NoteBlock.Text>()
        .joinToString("\n\n") { it.markdown.trim() }
        .trim(),
)
